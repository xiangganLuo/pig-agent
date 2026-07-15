package io.pigagent.tool.filesystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 敏感文件黑名单：{@code readFile}/{@code writeFile} 必须拒绝触碰工作区凭据文件
 * （models.json / mcp.json / .bak），且 {@code ../} 与符号链接间接指向同样被拦；
 * 普通项目文件不受影响；缺省空名单不拦（向后兼容）。
 */
class FileSystemToolsTest {

    @TempDir
    Path tmp;

    private Path ws;
    private Path modelsJson;
    private Path mcpJson;
    private FileSystemTools guarded;

    @BeforeEach
    void setUp() throws IOException {
        ws = tmp.resolve("workspace");
        Files.createDirectories(ws);
        modelsJson = ws.resolve("models.json");
        mcpJson = ws.resolve("mcp.json");
        Files.writeString(modelsJson, "{\"apiKey\":\"sk-super-secret-value\"}");
        Files.writeString(mcpJson, "{\"servers\":[]}");
        guarded = new FileSystemTools(Set.of(
                modelsJson, mcpJson,
                ws.resolve("models.json.bak"), ws.resolve("mcp.json.bak")));
    }

    @Test
    void readFile_deniesCredentialFile_andDoesNotLeakContent() {
        String result = guarded.readFile(modelsJson.toString());

        assertThat(result).contains("\"error\"").contains("access denied");
        assertThat(result).doesNotContain("sk-super-secret-value");
    }

    @Test
    void readFile_deniesViaRelativeTraversal() {
        // workspace/nope/../models.json -> normalizes to workspace/models.json
        String tricky = ws.resolve("nope").resolve("..").resolve("models.json").toString();

        String result = guarded.readFile(tricky);

        assertThat(result).contains("access denied");
    }

    @Test
    void writeFile_deniesCredentialFile_andDoesNotModify() throws IOException {
        String before = Files.readString(modelsJson);

        String result = guarded.writeFile(modelsJson.toString(), "tampered");

        assertThat(result).contains("\"error\"").contains("access denied");
        assertThat(Files.readString(modelsJson)).isEqualTo(before);
    }

    @Test
    void writeFile_deniesCredentialFileEvenWhenAbsent() throws IOException {
        Files.deleteIfExists(mcpJson);

        String result = guarded.writeFile(mcpJson.toString(), "tampered");

        assertThat(result).contains("access denied");
        assertThat(Files.exists(mcpJson)).isFalse();
    }

    @Test
    void readFile_allowsNormalProjectFile() throws IOException {
        Path normal = tmp.resolve("project").resolve("pom.xml");
        Files.createDirectories(normal.getParent());
        Files.writeString(normal, "<project/>");

        String result = guarded.readFile(normal.toString());

        assertThat(result).isEqualTo("<project/>");
    }

    @Test
    void writeFile_allowsNormalProjectFile() {
        Path normal = tmp.resolve("out.txt");

        String result = guarded.writeFile(normal.toString(), "hello");

        assertThat(result).contains("Written");
        assertThat(normal).exists();
    }

    @Test
    void emptyBlacklist_isBackwardCompatible() throws IOException {
        FileSystemTools open = new FileSystemTools();
        Path anyFile = tmp.resolve("anything.txt");
        Files.writeString(anyFile, "readable");

        assertThat(open.readFile(anyFile.toString())).isEqualTo("readable");
    }
}
