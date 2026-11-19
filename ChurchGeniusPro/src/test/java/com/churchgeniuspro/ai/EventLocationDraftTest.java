package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.write.AiFormDraftService;
import com.churchgeniuspro.ai.write.EventLocationParser;
import com.churchgeniuspro.service.OpenAiVoiceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-11-19: "Describe your event" filled every field but the Location
 * ("Thanksgiving Dinner on Nov 26th at 7:30 pm at Location: 16127 S Bradley Dr Olathe KS 66062.").
 */
class EventLocationDraftTest {

    static final ObjectMapper M = new ObjectMapper();
    static JsonNode json(String s) { try { return M.readTree(s); } catch (Exception e) { throw new RuntimeException(e); } }
    static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    static final String THANKSGIVING = "Thanksgiving Dinner on Nov 26th at 7:30 pm at Location: 16127 S Bradley Dr Olathe KS 66062.";
    static final String REST = "\"eventName\":\"Thanksgiving Dinner\",\"eventType\":\"One Day\",\"eventDate\":\"2026-11-26\",\"startTime\":\"19:30\",\"endTime\":\"21:30\","
            + "\"registrationEndDate\":\"2026-11-25\",\"fee\":\"Free\",\"allowMaybeRsvp\":false,\"showRegistrants\":false,\"foodAvailable\":true,\"foodLabel\":\"Dinner\","
            + "\"accommodationAvailable\":false,\"note\":\"Join us for a Thanksgiving Dinner.\"";

    @SuppressWarnings("unchecked")
    static Map<String, Object> draft(String description, String modelEvent) {
        OpenAiVoiceService ai = mock(OpenAiVoiceService.class);
        when(ai.isEnabled()).thenReturn(true);
        when(ai.chatJson(anyString(), anyString(), anyDouble())).thenReturn(json("{\"event\":{" + modelEvent + "},\"questions\":[],\"unsupported\":[]}"));
        Map<String, Object> d = new AiFormDraftService(ai).draft(description, TODAY);
        assertThat(d).as("draft for: " + description).isNotNull();
        return (Map<String, Object>) d.get("event");
    }

    static void assertBradley(Map<String, ?> ev) {
        assertThat(ev.get("address1")).isEqualTo("16127 S Bradley Dr");
        assertThat(ev.get("city")).isEqualTo("Olathe");
        assertThat(ev.get("state")).isEqualTo("KS");
        assertThat(ev.get("pinCode")).isEqualTo("66062");
        assertThat(ev.get("country")).isEqualTo("USA");
        assertThat(ev.containsKey("address2")).isFalse();
    }

    @Test @DisplayName("the reported description: the model leaves the location out → it is read from the description, every other field unchanged")
    void reportedCase() {
        Map<String, Object> ev = draft(THANKSGIVING, REST);
        assertBradley(ev);
        assertThat(ev).containsEntry("eventName", "Thanksgiving Dinner").containsEntry("eventType", "One Day").containsEntry("eventDate", "2026-11-26")
                .containsEntry("startTime", "19:30").containsEntry("endTime", "21:30").containsEntry("registrationEndDate", "2026-11-25").containsEntry("fee", "Free")
                .containsEntry("allowMaybeRsvp", false).containsEntry("showRegistrants", false).containsEntry("foodAvailable", true).containsEntry("foodLabel", "Dinner")
                .containsEntry("accommodationAvailable", false).containsEntry("note", "Join us for a Thanksgiving Dinner.");
    }

    @Test @DisplayName("Location: / Venue: / Address: / at — the same address is found each way, with and without commas")
    void everyWayOfSayingIt() {
        for (String d : new String[] {
                "Thanksgiving Dinner on Nov 26th at 7:30 pm at Location: 16127 S Bradley Dr Olathe KS 66062.",
                "Thanksgiving Dinner on Nov 26th at 7:30 pm. Location: 16127 S Bradley Dr Olathe KS 66062",
                "Thanksgiving Dinner on Nov 26th at 7:30 pm at 16127 S Bradley Dr Olathe KS 66062.",
                "Thanksgiving Dinner on Nov 26th at 7:30 pm. Venue: 16127 S Bradley Dr Olathe KS 66062",
                "Thanksgiving Dinner on Nov 26th at 7:30 pm\nAddress: 16127 S Bradley Dr Olathe KS 66062\nFree, dinner provided",
                "Thanksgiving Dinner at 16127 S Bradley Dr, Olathe, KS 66062 on Nov 26th at 7:30 pm",
                "Thanksgiving Dinner on Nov 26th at 7:30 pm @ 16127 S Bradley Dr Olathe KS 66062" }) {
            assertBradley(draft(d, REST));
            assertBradley(EventLocationParser.fromDescription(d));
        }
        // abbreviations with full stops are kept as typed, and do not end the address early
        assertThat(EventLocationParser.fromDescription("Thanksgiving Dinner, Nov 26th, 7:30 pm, address: 16127 S. Bradley Dr., Olathe, Kansas 66062, USA."))
                .containsEntry("address1", "16127 S. Bradley Dr.").containsEntry("city", "Olathe").containsEntry("state", "KS").containsEntry("pinCode", "66062").containsEntry("country", "USA");
    }

    @Test @DisplayName("a venue name with no street goes into Address 1 as written; a venue with a street keeps both")
    void venueNames() {
        assertThat(draft("Youth night at Grace Fellowship Hall on Friday at 6 pm", REST)).containsEntry("address1", "Grace Fellowship Hall").doesNotContainKeys("city", "state", "pinCode", "country");
        assertThat(draft("Christmas play, Dec 20 at 6 pm. Venue: Olathe Community Center", REST)).containsEntry("address1", "Olathe Community Center");
        assertThat(draft("Potluck after service in the Fellowship Hall, bring a dish", REST)).containsEntry("address1", "the Fellowship Hall");
        assertThat(draft("Location: Fellowship Hall", REST)).containsEntry("address1", "Fellowship Hall");
        Map<String, Object> both = draft("Concert on Dec 5 at 7 pm. Venue: Grace Hall, 120 Main St, Olathe, KS 66061", REST);
        assertThat(both).containsEntry("address1", "120 Main St").containsEntry("address2", "Grace Hall").containsEntry("city", "Olathe").containsEntry("state", "KS").containsEntry("pinCode", "66061");
    }

    @Test @DisplayName("what the model returns is used and repaired: one-line address split, 'location' key accepted, state names → codes, its own parts never overwritten")
    void modelOutput() {
        // whole address in address1
        assertBradley(draft(THANKSGIVING, REST + ",\"address1\":\"16127 S Bradley Dr Olathe KS 66062\""));
        // a key the form does not have
        assertBradley(draft(THANKSGIVING, REST + ",\"location\":\"16127 S Bradley Dr, Olathe, KS 66062\""));
        assertBradley(draft(THANKSGIVING, REST + ",\"location\":{\"street\":\"16127 S Bradley Dr\",\"city\":\"Olathe\",\"state\":\"Kansas\",\"zip\":\"66062\"}"));
        // already split, with slips
        assertBradley(draft(THANKSGIVING, REST + ",\"address1\":\"16127 S Bradley Dr\",\"city\":\"Olathe\",\"state\":\"KS - Kansas\",\"pinCode\":66062,\"country\":\"United States\",\"address2\":\"\""));
        // the model's own answer wins over the description
        Map<String, Object> own = draft(THANKSGIVING, REST + ",\"address1\":\"1 Church Rd\",\"city\":\"Lenexa\",\"state\":\"KS\",\"pinCode\":\"66215\"");
        assertThat(own).containsEntry("address1", "1 Church Rd").containsEntry("city", "Lenexa").containsEntry("pinCode", "66215");
        // an unusable state is dropped, not guessed; the rest stays
        Map<String, Object> bad = draft("Dinner at 5 pm", REST + ",\"address1\":\"1 Church Rd\",\"city\":\"Lenexa\",\"state\":\"Narnia\"");
        assertThat(bad).containsEntry("address1", "1 Church Rd").containsEntry("city", "Lenexa").doesNotContainKey("state");
    }

    @Test @DisplayName("nothing is invented: no place in the description → no location; times, 'at no cost', 'at least' are not places")
    void nothingInvented() {
        for (String d : new String[] {
                "Thanksgiving Dinner on Nov 26th at 7:30 pm",
                "VBS June 8-12 2027 9am to noon free max 60",
                "Dinner at 7 pm at no cost, at least 10 people",
                "Movie night at 6:30pm, popcorn provided",
                "Prayer breakfast at noon on Saturday" }) {
            assertThat(EventLocationParser.fromDescription(d)).as(d).isEmpty();
            assertThat(draft(d, REST)).as(d).doesNotContainKeys("address1", "address2", "city", "state", "pinCode", "country");
        }
    }

    @Test @DisplayName("address splitting: units, directions after the street type, full state names, zip+4, other countries")
    void splitting() {
        assertThat(EventLocationParser.parse("500 W Santa Fe St Suite 200 Olathe KS 66061"))
                .containsEntry("address1", "500 W Santa Fe St").containsEntry("address2", "Suite 200").containsEntry("city", "Olathe").containsEntry("state", "KS");
        assertThat(EventLocationParser.parse("1200 Main St NW Kansas City MO 64105-1234"))
                .containsEntry("address1", "1200 Main St NW").containsEntry("city", "Kansas City").containsEntry("state", "MO").containsEntry("pinCode", "64105-1234");
        assertThat(EventLocationParser.parse("45 Oak Avenue, Charleston, West Virginia 25301"))
                .containsEntry("address1", "45 Oak Avenue").containsEntry("city", "Charleston").containsEntry("state", "WV");
        assertThat(EventLocationParser.parse("12 Park Lane, Albany, New York")).containsEntry("city", "Albany").containsEntry("state", "NY").containsEntry("country", "USA");
        assertThat(EventLocationParser.parse("22 King St, Toronto, Canada")).containsEntry("country", "CAN").doesNotContainKey("state");
        // no street type to split on: kept whole rather than split wrongly
        assertThat(EventLocationParser.parse("77 Broadway Olathe KS 66061")).containsEntry("address1", "77 Broadway Olathe").containsEntry("state", "KS").doesNotContainKey("city");
        assertThat(EventLocationParser.stateCode("ks")).isEqualTo("KS");
        assertThat(EventLocationParser.stateCode("New Hampshire")).isEqualTo("NH");
        assertThat(EventLocationParser.stateCode("Ontario")).isNull();
    }

    @Test @DisplayName("client JSON stays strictly checked; the prompt asks for the location; the page fills the Location fields and never saves")
    void schemaPromptAndPage() throws Exception {
        assertThat(AiFormDraftService.validate(json("{\"event\":{\"address1\":\"1 Church Rd\",\"city\":\"Lenexa\",\"state\":\"KS\",\"country\":\"USA\",\"pinCode\":\"66215\"}}")).get("event"))
                .asString().contains("Lenexa");
        assertThatThrownBy(() -> AiFormDraftService.validate(json("{\"event\":{\"state\":\"Narnia\"}}"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiFormDraftService.validate(json("{\"event\":{\"country\":\"Mars\"}}"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AiFormDraftService.validate(json("{\"event\":{\"location\":\"x\"}}"))).isInstanceOf(IllegalArgumentException.class);
        String prompt = AiFormDraftService.systemPrompt(TODAY);
        assertThat(prompt).contains("address1").contains("pinCode").contains("Location:").contains("Venue:").contains("Address:").contains("\"at 7:30 pm\" is a time");

        String page = Files.readString(Path.of("src/main/resources/static/event.html"));
        String apply = page.substring(page.indexOf("function aiFormApply()"));
        assertThat(apply).contains("aiSet('address1', ev.address1)").contains("aiSet('city', ev.city)").contains("aiSet('pinCode', ev.pinCode)")
                .contains("aiSetState(ev.state)").contains("aiSetCountry(ev.country)");
        assertThat(apply.substring(0, apply.indexOf("</script>"))).doesNotContain("fetch(").doesNotContain("CGP_api(").doesNotContain("saveEvent");
        assertThat(page).contains("function aiLocationLine(ev)").contains("<b>Location:</b>");
    }
}
