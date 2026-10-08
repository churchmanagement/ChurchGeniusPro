package com.churchgeniuspro.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Kids Portal's tab bar — what a child may reach, and what happens to
 * everything else.
 *
 * <p>The Kids Portal is the same page as the Member Portal with the sidebar
 * hidden and a small pill bar injected by {@code applyChildMode()}. Guess It was
 * in that bar, and on a trial it could not work: Guess It belongs to Activity
 * Corner, which no evaluation account has, so pressing it lit the pill and
 * rendered the Upcoming screen — an adult member's home page — behind it.
 *
 * <p>The page is seven thousand lines of script with three layers of
 * {@code showTab} wrapper, so this reads the file rather than the DOM. That is
 * blunt, but the thing worth pinning is structural: the list of tabs a child may
 * reach, and the guard that catches anything outside it. Both are one-line edits
 * away from coming back.
 */
@DisplayName("Kids Portal — tab bar")
class KidsPortalNavigationTest {

    private static final Path PAGE =
            Paths.get("src/main/resources/static/memberHome.html");

    private static String page() throws IOException {
        return Files.readString(PAGE);
    }

    /** The contents of a {@code new Set([...])} or {@code [...]} literal in the page. */
    private static String literal(String source, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(source);
        assertThat(m.find())
                .withFailMessage("memberHome.html no longer contains %s — the Kids Portal nav was "
                               + "restructured, so this test needs rewriting rather than deleting",
                                 pattern)
                .isTrue();
        return m.group(1);
    }

    @Nested
    @DisplayName("what a child may reach")
    class Allowed {

        @Test
        @DisplayName("Guess It is not one of the child's tabs")
        void guessItIsNotAChildTab() throws IOException {
            String tabs = literal(page(), "var CHILD_TABS = new Set\\(\\[([^\\]]*)\\]\\)");
            assertThat(tabs).doesNotContain("guessit");
            // …and the tabs a child does have are still there.
            assertThat(tabs).contains("groups", "sundaySchool");
        }

        @Test
        @DisplayName("Guess It is not drawn in the child's nav bar")
        void guessItIsNotInTheNavBar() throws IOException {
            String order = literal(page(), "var NAV_ORDER = \\[([^\\]]*)\\]");
            assertThat(order).doesNotContain("guessit");
            assertThat(order).contains("sundaySchool", "groups", "worship");
        }

        @Test
        @DisplayName("a tab a child may not reach lands on the Kids Portal's own home screen")
        void unknownTabsFallBackToTheChildsHome() throws IOException {
            String src = page();
            // The guard, inside the child-mode showTab wrapper: a bookmarked
            // ?tab=guessit, or any redirect aimed at an adult member's section,
            // resolves to the child's home tab before anything is rendered.
            assertThat(src).contains("if (!CHILD_TABS.has(tab)) tab = CHILD_HOME_TAB;");
            assertThat(src).contains("var CHILD_HOME_TAB = 'sundaySchool';");
        }

        @Test
        @DisplayName("the nav bar highlights the tab that was shown, not the one that was asked for")
        void highlightFollowsTheResolvedTab() throws IOException {
            String src = page();
            // showTab returns the tab it actually showed; the wrapper highlights
            // that. Without it a pill sat lit over another tab's contents, which
            // is exactly what the Kids Portal looked like on a trial.
            assertThat(src).contains("tab = _origShowTabChild(tab) || tab;");
            assertThat(src).contains("tab = _origShowTab(tab) || tab;");
        }
    }

    @Nested
    @DisplayName("what must not change")
    class Unaffected {

        @Test
        @DisplayName("Guess It still exists for ordinary members")
        void guessItStillExistsForMembers() throws IOException {
            String src = page();
            assertThat(src).contains("id=\"tabGuessit\"");
            assertThat(src).contains("data-feature=\"activityCorner\"");
            assertThat(src).contains("showTab('guessit')");
            assertThat(src).contains("guessit:      'sectionGuessit'");
        }

        @Test
        @DisplayName("a member on a plan without Activity Corner is still redirected, not shown a dead tab")
        void planGateIsStillInPlace() throws IOException {
            assertThat(page()).contains(
                "if (tab === 'guessit' && window.CGP_hasFeature && "
              + "!window.CGP_hasFeature('activityCorner')) {");
        }

        @Test
        @DisplayName("the public Guess It page is untouched")
        void publicPageUntouched() {
            assertThat(Paths.get("src/main/resources/static/guessIt.html")).exists();
        }
    }
}
