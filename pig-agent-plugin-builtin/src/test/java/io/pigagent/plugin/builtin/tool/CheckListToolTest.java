package io.pigagent.plugin.builtin.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CheckListTool} round-trip after extraction into the built-in plugin module: create a
 * checklist, complete an item, and render its state; invalid names/indices degrade gracefully.
 */
class CheckListToolTest {

    @Test
    void createCompleteAndShowRoundTrip() {
        // Arrange
        CheckListTool tool = new CheckListTool();

        // Act
        String created = tool.createChecklist("release", "build, test, ship");
        String completed = tool.completeItem("release", 1);
        String shown = tool.showChecklist("release");

        // Assert
        assertThat(created).contains("release").contains("3");
        assertThat(completed).contains("complete");
        assertThat(shown).contains("## release")
                .contains("- [ ] build")
                .contains("- [x] test")
                .contains("- [ ] ship");
    }

    @Test
    void unknownChecklistAndBadIndexReportGracefully() {
        // Arrange
        CheckListTool tool = new CheckListTool();
        tool.createChecklist("todo", "a,b");

        // Act + Assert
        assertThat(tool.showChecklist("missing")).contains("not found");
        assertThat(tool.completeItem("missing", 0)).contains("not found");
        assertThat(tool.completeItem("todo", 9)).contains("Invalid index");
    }
}
