package com.churchgeniuspro.songbook;

import com.churchgeniuspro.controller.SongBookController;
import com.churchgeniuspro.controller.SongBookPublicController;
import com.churchgeniuspro.hibernate.SongBook;
import com.churchgeniuspro.hibernate.SongBookAd;
import com.churchgeniuspro.hibernate.SongBookPublish;
import com.churchgeniuspro.repository.SongBookRepository;
import com.churchgeniuspro.service.SongBookAccessService;
import com.churchgeniuspro.service.SongBookService;
import com.churchgeniuspro.service.SongExtractionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * App Store readiness, Option A (user-approved 2026-11-06): Song Book sponsor / advertisement
 * pages are never shown or managed inside the iOS / Android app, so the age rating can answer
 * Advertising = No. The website is unchanged and saved ads are kept.
 */
class SongBookAdsAppShellTest {

    private static final String CHURCH = "USRchurch-a";
    private static final String APP_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 ChurchGeniusProApp/1.0 (ios)";
    private static final String WEB_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile Safari/604.1";
    private static final String SNAPSHOT =
            "{\"bookTitle\":\"Musical Night\",\"sections\":[{\"name\":\"Hymns\",\"songs\":[{\"title\":\"Amazing Grace\"}]}],"
          + "\"ads\":[{\"id\":7,\"title\":\"Sponsor\",\"slot\":\"end\"}]}";

    private SongBookService service;
    private SongBookAccessService access;
    private SongBookController staff;
    private SongBookPublicController pub;

    @BeforeEach
    void setUp() {
        service = mock(SongBookService.class);
        access = mock(SongBookAccessService.class);
        when(access.canView(anyString())).thenReturn(true);
        when(access.canEdit(anyString())).thenReturn(true);
        SongBookRepository books = mock(SongBookRepository.class);
        SongBook def = new SongBook();
        def.setClientId(CHURCH); def.setDefaultBook(true); def.setName("Musical Night");
        when(books.findByClientIdOrderBySortOrderAscIdAsc(CHURCH)).thenReturn(List.of(def));
        staff = new SongBookController(service, access, mock(SongExtractionService.class), books);
        pub = new SongBookPublicController(service);

        SongBookAd ad = new SongBookAd();
        ad.setData(new byte[] {1, 2, 3});
        ad.setContentType("image/png");
        when(service.getAd(anyString(), anyLong())).thenReturn(ad);
        when(service.listAds(CHURCH)).thenReturn(List.of(Map.of("id", 7, "title", "Sponsor")));
        SongBookPublish p = new SongBookPublish();
        p.setClientId(CHURCH); p.setSnapshot(SNAPSHOT); p.setBookTitle("Musical Night");
        when(service.publicByToken("tok")).thenReturn(p);
    }

    private static MockHttpServletRequest req(String ua) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.addHeader("User-Agent", ua);
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("role", "Admin");
        s.setAttribute("username", "admin");
        s.setAttribute("appClientId", CHURCH);
        r.setSession(s);
        return r;
    }

    private static JsonNode json(Object body) throws Exception {
        return new ObjectMapper().readTree(String.valueOf(body));
    }

    @Test
    @DisplayName("public Song Book in the app: same songs, no ads; the website gets the snapshot unchanged")
    void publicSnapshot() throws Exception {
        JsonNode app = json(pub.view("tok", req(APP_UA)).getBody());
        assertEquals(0, app.get("ads").size());
        assertEquals("Amazing Grace", app.get("sections").get(0).get("songs").get(0).get("title").asText());
        assertEquals("Musical Night", app.get("bookTitle").asText());

        assertEquals(SNAPSHOT, pub.view("tok", req(WEB_UA)).getBody(), "website: verbatim");
    }

    @Test
    @DisplayName("ad images are 404 in the app (public and staff), served on the website")
    void adImages() {
        assertEquals(404, pub.adImage("tok", 7L, req(APP_UA)).getStatusCode().value());
        assertEquals(404, staff.adImage(7L, req(APP_UA)).getStatusCode().value());
        assertEquals(200, pub.adImage("tok", 7L, req(WEB_UA)).getStatusCode().value());
        assertEquals(200, staff.adImage(7L, req(WEB_UA)).getStatusCode().value());
    }

    @Test
    @DisplayName("staff ad list is empty in the app and unchanged on the website")
    void adList() {
        assertEquals(List.of(), staff.listAds(req(APP_UA)).getBody());
        assertEquals(1, ((List<?>) staff.listAds(req(WEB_UA)).getBody()).size());
    }

    @Test
    @DisplayName("ad changes are refused in the app (403, website message) and nothing is changed")
    void mutationsRefused() throws Exception {
        org.springframework.mock.web.MockMultipartFile file =
                new org.springframework.mock.web.MockMultipartFile("file", "a.png", "image/png", new byte[] {1});
        List<ResponseEntity<?>> all = List.of(
                staff.uploadAd(file, "T", "end", req(APP_UA)),
                staff.updateAd(7L, Map.of("title", "X"), req(APP_UA)),
                staff.replaceAd(7L, file, req(APP_UA)),
                staff.reorderAds(Map.of("ids", List.of(7)), req(APP_UA)),
                staff.deleteAd(7L, req(APP_UA)));
        for (ResponseEntity<?> r : all) {
            assertEquals(403, r.getStatusCode().value());
            assertTrue(String.valueOf(r.getBody()).contains("managed on the ChurchGeniusPro website"));
        }
        verify(service, never()).addAds(anyString(), any(), any(), any(), any(), any(), any(), any());
        verify(service, never()).updateAd(anyString(), anyLong(), any(), any(), any(), any());
        verify(service, never()).replaceAd(anyString(), anyLong(), any(), any(), any(), any(), any());
        verify(service, never()).reorderAds(anyString(), anyList(), any(), any());
        verify(service, never()).deleteAd(anyString(), anyLong(), any(), any());

        assertEquals(200, staff.deleteAd(7L, req(WEB_UA)).getStatusCode().value(), "website still manages ads");
    }

    @Test
    @DisplayName("snapshotWithoutAds: empties ads, keeps everything else, never leaks on bad JSON")
    void stripHelper() throws Exception {
        JsonNode n = json(SongBookService.snapshotWithoutAds(SNAPSHOT));
        assertEquals(0, n.get("ads").size());
        assertEquals("Hymns", n.get("sections").get(0).get("name").asText());
        String bad = SongBookService.snapshotWithoutAds("{not json \"ads\":[1]");
        assertEquals(0, json(bad).get("ads").size());
        assertEquals(null, SongBookService.snapshotWithoutAds(null));
    }

    @Test
    @DisplayName("staff editor hides the Ads tab in the app only")
    void editorTabHidden() throws Exception {
        String page = Files.readString(Path.of("src/main/resources/static/songbook.html"));
        assertTrue(page.contains("html.cgp-app-noads #sbTabBtn_ads, html.cgp-app-noads #sbPane_ads { display: none !important; }"));
        assertTrue(page.contains("/ChurchGeniusProApp\\//.test(navigator.userAgent"));
    }
}
