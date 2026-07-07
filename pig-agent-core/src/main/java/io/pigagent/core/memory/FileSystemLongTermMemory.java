package io.pigagent.core.memory;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * File-system-backed long-term memory.
 * Persists extracted knowledge to workspace/context/memory.md.
 * Each record is timestamped and appended as Markdown.
 */
public final class FileSystemLongTermMemory implements LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(FileSystemLongTermMemory.class);
    private static final int MIN_TEXT_LENGTH = 20;
    private static final int MAX_RETRIEVE_CHARS = 3000;
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Path memoryFile;

    public FileSystemLongTermMemory(Path memoryFile) {
        this.memoryFile = memoryFile;
    }

    @Override
    public Mono<Void> record(List<Msg> messages) {
        return Mono.fromRunnable(() -> {
            List<String> entries = extractEntries(messages);
            if (entries.isEmpty()) return;

            StringBuilder sb = new StringBuilder();
            sb.append("\n## ").append(LocalDateTime.now().format(TIMESTAMP_FMT)).append("\n");
            for (String entry : entries) {
                sb.append("- ").append(entry).append("\n");
            }

            try {
                Files.createDirectories(memoryFile.getParent());
                Files.writeString(memoryFile, sb.toString(),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("Failed to write memory: {}", e.getMessage());
            }
        });
    }

    @Override
    public Mono<String> retrieve(Msg query) {
        return Mono.fromSupplier(() -> {
            if (!Files.exists(memoryFile)) return "";

            try {
                String content = Files.readString(memoryFile);
                if (content.isBlank()) return "";

                if (content.length() <= MAX_RETRIEVE_CHARS) {
                    return content;
                }
                return content.substring(content.length() - MAX_RETRIEVE_CHARS);
            } catch (IOException e) {
                log.warn("Failed to read memory: {}", e.getMessage());
                return "";
            }
        });
    }

    private List<String> extractEntries(List<Msg> messages) {
        return messages.stream()
                .filter(this::isSubstantive)
                .map(Msg::getTextContent)
                .filter(text -> text != null && text.length() >= MIN_TEXT_LENGTH)
                .map(this::summarize)
                .toList();
    }

    private boolean isSubstantive(Msg msg) {
        if (msg.getRole() == MsgRole.TOOL) return false;
        String text = msg.getTextContent();
        if (text == null || text.length() < MIN_TEXT_LENGTH) return false;
        if (text.startsWith("{") && text.contains("\"tool_call")) return false;
        return true;
    }

    private String summarize(String text) {
        text = text.replace('\n', ' ').trim();
        if (text.length() > 200) {
            text = text.substring(0, 200) + "...";
        }
        return text;
    }
}
