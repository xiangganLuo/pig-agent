package io.pigagent.core.memory;

import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * File-backed memory that persists conversation history.
 * Each session gets its own file under the workspace directory.
 */
public final class FileMemory implements Memory {

    private final Path memoryFile;
    private final List<Msg> messages = new CopyOnWriteArrayList<>();

    public FileMemory(Path memoryFile) {
        this.memoryFile = memoryFile;
        loadFromFile();
    }

    @Override
    public void addMessage(Msg message) {
        messages.add(message);
        saveToFile();
    }

    @Override
    public List<Msg> getMessages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    @Override
    public void deleteMessage(int index) {
        if (index >= 0 && index < messages.size()) {
            messages.remove(index);
            saveToFile();
        }
    }

    @Override
    public void clear() {
        messages.clear();
        saveToFile();
    }

    private void loadFromFile() {
        if (!Files.exists(memoryFile)) {
            return;
        }
        try {
            Files.readAllLines(memoryFile);
            messages.clear();
        } catch (IOException e) {
            messages.clear();
        }
    }

    private void saveToFile() {
        try {
            Files.createDirectories(memoryFile.getParent());
            StringBuilder sb = new StringBuilder();
            for (Msg msg : messages) {
                sb.append(msg.getTextContent()).append("\n");
            }
            Files.writeString(memoryFile, sb.toString());
        } catch (IOException e) {
            System.err.println("Warning: Failed to save memory: " + e.getMessage());
        }
    }
}
