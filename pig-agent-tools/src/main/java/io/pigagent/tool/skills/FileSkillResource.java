package io.pigagent.tool.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A {@link SkillResource} backed by a real file inside a workspace skill directory. Content is read
 * lazily via {@link #read()} so a descriptor can be listed by path/size without touching the bytes.
 */
final class FileSkillResource implements SkillResource {

    private final String path;
    private final Path file;
    private final long size;
    private final boolean text;

    FileSkillResource(String path, Path file, long size, boolean text) {
        this.path = path;
        this.file = file;
        this.size = size;
        this.text = text;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public boolean isText() {
        return text;
    }

    @Override
    public String read() throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
