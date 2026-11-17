package com.churchgeniuspro.enhancements;

import com.churchgeniuspro.workflow.WorkflowSchema;
import com.churchgeniuspro.workflow.WorkflowTemplates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 2026-11-16 (items 7 + 10) — the Automations Help window stays in step with the engine; the Genius launcher can be moved. */
@DisplayName("Automations help covers every trigger, action and template; draggable Genius launcher")
class WorkflowsHelpTest {

    static final Path STATIC = Path.of("src/main/resources/static");

    @Test @DisplayName("every template, trigger and action of the engine is explained, with who receives it and when")
    void helpCoversTheEngine() throws Exception {
        String js = Files.readString(STATIC.resolve("workflows-help.js"));
        for (WorkflowTemplates.Template t : WorkflowTemplates.ALL) {
            assertThat(js).as("template %s", t.key()).contains("key: '" + t.key() + "'").contains("name: '" + t.name() + "'");
        }
        assertThat(js.split("key: '").length - 1).as("no template documented that does not exist").isEqualTo(WorkflowTemplates.ALL.size());
        for (WorkflowSchema.Trigger t : WorkflowSchema.TRIGGERS.values()) {
            assertThat(js).as("trigger %s", t.key()).contains("['" + t.label().replace("'", "’") + "'");
        }
        for (WorkflowSchema.ActionType a : WorkflowSchema.ACTIONS.values()) {
            assertThat(js).as("action %s", a.key()).contains("['" + a.label() + "'");
        }
        for (String op : WorkflowSchema.OPS) assertThat(js).as("comparison %s", op).contains(op);
        assertThat(js).contains("does:", "trigger:", "condition:", "who:", "when:", "result:")
                .contains("Test run").contains("switched OFF").contains("never sends an e-mail, text or notification to a member")
                .contains("Done").contains("Skipped").contains("Denied").contains("Capped").contains("Failed")
                .doesNotContain("innerHTML");
        assertThat(Files.readString(STATIC.resolve("workflows.html"))).contains("<script src=\"/workflows-help.js\"></script>").contains("id=\"newBtn\"");
    }

    @Test @DisplayName("the ✦ Genius launcher can be dragged (mouse / touch / keyboard), stays on screen and remembers its place")
    void launcherIsDraggable() throws Exception {
        String js = Files.readString(STATIC.resolve("ai-chat.js"));
        assertThat(js).contains("function makeFabDraggable(fab)").contains("makeFabDraggable(fab);")
                .contains("'pointerdown'").contains("'pointermove'").contains("'pointerup'").contains("'pointercancel'")
                .contains("setPointerCapture").contains("fab.style.touchAction = 'none'")
                .contains("localStorage.setItem(FAB_KEY").contains("env(safe-area-inset-bottom,0px)")
                .contains("window.addEventListener('resize', again)")
                .contains("if (fab._dragged) { fab._dragged = false;")           // the end of a drag never opens the panel
                .contains("fabReset(fab, true)");                                 // double-click / Shift+Home → default corner
        // the default position is still the stylesheet's bottom-right corner
        assertThat(js).contains(".ga-fab{position:fixed;right:18px;bottom:18px;");
    }
}
