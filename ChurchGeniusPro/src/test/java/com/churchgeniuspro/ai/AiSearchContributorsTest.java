package com.churchgeniuspro.ai;

import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AI assistant: "Who gave tithe this month?" was read as a search for a contributor
 * named "Given"/"Who", so a church with three tithers was told there were none.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AI search — who gave / contributor-list questions")
class AiSearchContributorsTest {

    private static final String CID = "CHR-1";

    @Mock IncomeRepository incomeRepo;
    @Mock ExpenseRepository expenseRepo;
    @Mock FamilyMemberRepository memberRepo;

    AiSearchService service;

    /** [id, date, first, last, main, sub, amount, method, ref, note] as searchIncome returns. */
    private static Object[] row(String first, String last, String amount) {
        return new Object[]{ 1, Date.valueOf(LocalDate.of(2026, 10, 5)), first, last,
                             "Church Fund", "Tithe", new BigDecimal(amount), "Cash", null, null };
    }

    @BeforeEach
    void setUp() {
        service = new AiSearchService(incomeRepo, expenseRepo, memberRepo);
        when(incomeRepo.searchIncome(eq(CID), isNull(), any(), any(), any()))
                .thenReturn(List.<Object[]>of(row("Gladwine", "Ben", "100.00"), row("Godwine", "Ben", "50.00"),
                                    row("Dany", "Jacob", "25.00"), row("Gladwine", "Ben", "20.00")));
        when(incomeRepo.searchIncome(eq(CID), isNotNull(), any(), any(), any())).thenReturn(List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Who gave tithe this month?",
            "Who paid tithe this month?",
            "Who gave offerings this month?",
            "Who contributed to tithe this month?",
            "Show me who gave tithe this month.",
            "Please list who all are given tithe this month?",
            "who gave tithe"
    })
    @DisplayName("lists the contributors for the category and period, never a person called Given/Who")
    void listsContributors(String question) {
        Map<String, Object> r = service.search(question, acc("Accountant"), CID);

        assertThat(r.get("intent")).isEqualTo("income");
        String ans = String.valueOf(r.get("answer"));
        assertThat(ans).contains("3 people gave", "Gladwine Ben ($120.00)", "Godwine Ben ($50.00)", "Dany Jacob ($25.00)");
        assertThat(ans).doesNotContain("'Given'", "'Who'", "'Gave'", "'Paid'");
        // The query asked for no name at all (nothing matched as a person).
        verify(incomeRepo).searchIncome(eq(CID), isNull(), any(), any(), any());
        verify(incomeRepo, never()).searchIncome(eq(CID), isNotNull(), any(), any(), any());
    }

    @Test
    @DisplayName("the category still narrows the query (tithe → 'tithe', offerings → 'offering')")
    void categoryIsPassed() {
        service.search("Who gave offerings this month?", acc("Accountant"), CID);
        ArgumentCaptor<String> cat = ArgumentCaptor.forClass(String.class);
        verify(incomeRepo).searchIncome(eq(CID), isNull(), cat.capture(), any(), any());
        assertThat(cat.getValue()).isEqualTo("offering");
    }

    @Test
    @DisplayName("nobody gave → says so for that category and month")
    void nobodyGave() {
        when(incomeRepo.searchIncome(eq(CID), isNull(), any(), any(), any())).thenReturn(List.of());
        Map<String, Object> r = service.search("Who gave tithe this month?", acc("SuperAdmin"), CID);
        assertThat(String.valueOf(r.get("answer"))).startsWith("No one gave Tithe in ");
    }

    @Test
    @DisplayName("a real person's name still searches for that person")
    void personNameStillWorks() {
        when(incomeRepo.searchIncome(eq(CID), eq("anson"), any(), any(), any()))
                .thenReturn(List.<Object[]>of(row("Anson", "Mathew", "10.00")));
        Map<String, Object> r = service.search("Did Anson give tithe this month?", acc("Accountant"), CID);
        assertThat(String.valueOf(r.get("answer"))).contains("Found 1 income record(s) for 'Anson'");
        verify(incomeRepo).searchIncome(eq(CID), eq("anson"), eq("tithe"), any(), any());
    }

    /** Access as AiAccessPolicy resolves it for a staff session with no permission restrictions. */
    static AiAccess acc(String role) {
        boolean fin = List.of("SuperAdmin", "Admin", "Accountant").contains(role);
        return new AiAccess(role, false, "Member".equals(role), false, fin, fin, fin, fin,
                            List.of("SuperAdmin", "Admin", "User").contains(role));
    }

    @Test
    @DisplayName("financial authorization follows the Income page rule: SuperAdmin, Admin and Accountant; everyone else is denied")
    void financialAccessFollowsThePageRule() {
        for (String role : List.of("User", "Member", "Limited")) {
            Map<String, Object> r = service.search("Who gave tithe this month?", acc(role), CID);
            assertThat(r.get("intent")).as(role).isEqualTo("denied");
        }
        verifyNoInteractions(incomeRepo);
        assertThat(service.search("Who gave tithe this month?", acc("Accountant"), CID).get("intent")).isEqualTo("income");
        assertThat(service.search("Who gave tithe this month?", acc("SuperAdmin"), CID).get("intent")).isEqualTo("income");
        assertThat(service.search("Who gave tithe this month?", acc("Admin"), CID).get("intent")).isEqualTo("income");
        assertThat(service.search("Who gave tithe this month?", AiAccess.none("Member"), CID).get("intent")).as("Kids Portal").isEqualTo("denied");
    }
}
