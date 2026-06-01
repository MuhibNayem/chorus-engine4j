package com.chorus.engine.sample.claude;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.memory.ShortTermMemory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class MemoryManager {

    private final ShortTermMemory shortTerm;
    private final Path memoryFile;

    MemoryManager(int maxTokens, int maxMessages) {
        this.shortTerm = new ShortTermMemory(maxTokens, maxMessages);
        this.memoryFile = Path.of(".chorus/memory.json");
    }

    void store(Message message, int tokenEstimate) {
        shortTerm.add(message, tokenEstimate);
    }

    List<Message> getRecent(int n) {
        return shortTerm.getRecent(n);
    }

    List<Message> getAll() {
        return shortTerm.getAll();
    }

    List<Message> search(String query, int topK) {
        return shortTerm.search(query, topK);
    }

    int currentTokens() {
        return shortTerm.currentTokens();
    }

    void clear() {
        shortTerm.clear();
    }

    void saveToDisk() {
        try {
            Files.createDirectories(memoryFile.getParent());
            StringBuilder sb = new StringBuilder();
            for (Message m : shortTerm.getAll()) {
                sb.append(m.content().replace("\n", "\\n")).append("\n");
            }
            Files.writeString(memoryFile, sb.toString());
        } catch (IOException ignored) {}
    }

    String getMemoryContext() {
        List<Message> recent = shortTerm.getRecent(10);
        if (recent.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("=== RECENT CONTEXT (auto-memory) ===\n");
        for (Message m : recent) {
            sb.append("[").append(m.role().name()).append("] ")
              .append(m.content().length() > 300
                      ? m.content().substring(0, 300) + "..."
                      : m.content())
              .append("\n");
        }
        return sb.toString();
    }
}
