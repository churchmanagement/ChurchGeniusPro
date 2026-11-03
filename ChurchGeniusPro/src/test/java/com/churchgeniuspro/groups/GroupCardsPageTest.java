package com.churchgeniuspro.groups;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /groups shows groups as cards (2026-11-02, same look as /viewfamily) while every
 * existing action keeps its handler and permission rule: View Members toggles the
 * members panel, Edit opens the edit popup, Delete keeps its confirm, and the edit /
 * delete icons follow admin.groups.edit / admin.groups.delete exactly as before.
 */
@DisplayName("/groups — group cards keep every action")
class GroupCardsPageTest {

    @Test
    void cardsKeepTheExistingActionsAndRules() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/group.html"));
        assertThat(html).contains("id=\"groupsGrid\"").doesNotContain("id=\"groupsTableBody\"")
                .contains("onclick=\"toggleMembers(${g.id})\"")
                .contains("onclick=\"openEditGroupModal(${g.id})\"")
                .contains("onclick=\"deleteGroup(${g.id})\"")
                .contains("window.CGP_PERMS['admin.groups.edit'] === false")
                .contains("window.CGP_PERMS['admin.groups.delete'] === false")
                .contains("confirm('Are you sure you want to delete this group?')")
                .contains("id=\"memberCount-${g.id}\"")
                .contains("function memberCountText(n)")
                .contains("title=\"Edit group\"").contains("title=\"Delete group\"")
                .contains("@container (min-width: 1080px)");
        // no photos / images on group cards
        String render = html.substring(html.indexOf("function renderGroupsTable()"), html.indexOf("async function loadMemberCount"));
        assertThat(render).doesNotContain("<img");
    }
}
