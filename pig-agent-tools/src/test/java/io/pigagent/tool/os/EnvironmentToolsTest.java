package io.pigagent.tool.os;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EnvironmentTools} driven by an injected environment map (offline, deterministic): reads a
 * plain variable, masks secret-named ones, sanitizes secret-shaped values, lists names only, and
 * locates an executable on a controlled PATH.
 */
class EnvironmentToolsTest {

    @Test
    void getEnvironment_readsPlainVariable() {
        EnvironmentTools tools = new EnvironmentTools(Map.of("EDITOR", "vim"));

        assertThat(tools.getEnvironment("EDITOR")).isEqualTo("EDITOR=vim");
    }

    @Test
    void getEnvironment_masksSecretNamedVariable() {
        EnvironmentTools tools = new EnvironmentTools(Map.of("ANTHROPIC_API_KEY", "sk-secret-value-123456"));

        String out = tools.getEnvironment("ANTHROPIC_API_KEY");

        assertThat(out).contains("***").doesNotContain("sk-secret-value-123456");
    }

    @Test
    void getEnvironment_sanitizesSecretShapedValueEvenForPlainName() {
        // A non-secret name whose value looks like a key is still redacted by CredentialSanitizer.
        EnvironmentTools tools = new EnvironmentTools(Map.of("NOTES", "my key is sk-abcdef123456 keep safe"));

        assertThat(tools.getEnvironment("NOTES")).doesNotContain("sk-abcdef123456").contains("***");
    }

    @Test
    void getEnvironment_unsetVariable() {
        EnvironmentTools tools = new EnvironmentTools(Map.of());

        assertThat(tools.getEnvironment("NOPE")).isEqualTo("NOPE is not set");
    }

    @Test
    void getEnvironment_blankListsNamesOnly_notValues() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("EDITOR", "vim");
        env.put("SECRET_TOKEN", "should-not-appear");
        EnvironmentTools tools = new EnvironmentTools(env);

        String out = tools.getEnvironment("");

        assertThat(out).contains("EDITOR", "SECRET_TOKEN");
        assertThat(out).doesNotContain("vim", "should-not-appear");
    }

    @Test
    void whichCommand_findsExecutableOnPath(@TempDir Path dir) throws IOException {
        File exe = dir.resolve("mytool").toFile();
        Files.writeString(exe.toPath(), "#!/bin/sh\n");
        EnvironmentTools tools = new EnvironmentTools(Map.of("PATH", dir.toString()));

        String out = tools.whichCommand("mytool");

        assertThat(out).isEqualTo(exe.getAbsolutePath());
    }

    @Test
    void whichCommand_notFound() {
        EnvironmentTools tools = new EnvironmentTools(Map.of("PATH", System.getProperty("java.io.tmpdir")));

        assertThat(tools.whichCommand("definitely-not-a-real-cmd-xyz")).endsWith("not found on PATH");
    }

    @Test
    void whichCommand_blankAndNoPathAreCanonicalErrors() {
        assertThat(new EnvironmentTools(Map.of("PATH", "/x")).whichCommand("  "))
                .startsWith("{\"error\":").contains("empty command name");
        assertThat(new EnvironmentTools(Map.of()).whichCommand("git"))
                .startsWith("{\"error\":").contains("PATH is not set");
    }

    @Test
    void isSecretName_matchesCredentialLikeNames() {
        for (String name : new String[]{"API_KEY", "apikey", "MY_TOKEN", "db_secret", "PASSWORD",
                "PASSWD", "AWS_CREDENTIAL", "SIGNING_KEY"}) {
            assertThat(EnvironmentTools.isSecretName(name)).as("%s is secret", name).isTrue();
        }
        for (String name : new String[]{"EDITOR", "PATH", "HOME", "LANG"}) {
            assertThat(EnvironmentTools.isSecretName(name)).as("%s is not secret", name).isFalse();
        }
    }
}
