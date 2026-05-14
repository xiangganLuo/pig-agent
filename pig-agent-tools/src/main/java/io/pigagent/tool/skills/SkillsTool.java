package io.pigagent.tool.skills;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class SkillsTool {
    private final Path skillsDir;

    public SkillsTool(Path skillsDir) { this.skillsDir = skillsDir; }

    @Tool(description = "List available skills from the skills directory")
    public String listSkills() {
        try (Stream<Path> stream = Files.list(skillsDir)) {
            return stream.filter(Files::isDirectory).map(p -> "- " + p.getFileName())
                    .reduce((a, b) -> a + "\n" + b).orElse("No skills found.");
        } catch (IOException e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Load and read a skill's content by name")
    public String loadSkill(@ToolParam(name = "skill_name", description = "Skill name") String skillName) {
        Path skillFile = skillsDir.resolve(skillName).resolve("SKILL.md");
        try { return Files.exists(skillFile) ? Files.readString(skillFile) : "Skill not found: " + skillName; }
        catch (IOException e) { return "Error: " + e.getMessage(); }
    }
}
