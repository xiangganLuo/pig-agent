package io.pigagent.tool.filesystem;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FileSystemTools {
    @Tool(description = "Read the contents of a file")
    public String readFile(@ToolParam(name = "path", description = "Absolute file path") String path) {
        try { return Files.readString(Path.of(path)); }
        catch (IOException e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "Write content to a file (creates or overwrites)")
    public String writeFile(
            @ToolParam(name = "path", description = "Absolute file path") String path,
            @ToolParam(name = "content", description = "Content to write") String content) {
        try {
            Files.createDirectories(Path.of(path).getParent());
            Files.writeString(Path.of(path), content);
            return "Written %d bytes to %s".formatted(content.length(), path);
        } catch (IOException e) { return "Error: " + e.getMessage(); }
    }

    @Tool(description = "List files in a directory")
    public String listDirectory(@ToolParam(name = "path", description = "Directory path") String path) {
        try (var stream = Files.list(Path.of(path))) {
            return stream.map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""))
                    .reduce((a, b) -> a + "\n" + b).orElse("Empty directory");
        } catch (IOException e) { return "Error: " + e.getMessage(); }
    }
}
