package com.chorus.engine.sample.claude;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.context.Role;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

final class SessionStore {

    private static final Path SESSION_FILE = Path.of(".chorus/session.json");
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    void save(CliSession session) {
        try {
            Files.createDirectories(SESSION_FILE.getParent());
            List<Map<String, Object>> messages = new ArrayList<>();
            for (Message m : session.getHistory()) {
                messages.add(Map.of(
                        "role", m.role().name(),
                        "content", m.content(),
                        "name", Objects.requireNonNullElse(m.name(), ""),
                        "toolCallId", Objects.requireNonNullElse(m.toolCallId(), "")
                ));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("runId", session.getRunId());
            data.put("messages", messages);
            data.put("agentsMd", session.getAgentsMd());
            data.put("claudeMd", session.getClaudeMd());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(SESSION_FILE.toFile(), data);
        } catch (IOException e) {
            System.err.println("  [WARN] Could not save session: " + e.getMessage());
        }
    }

    Optional<CliSession> load() {
        if (!Files.exists(SESSION_FILE)) return Optional.empty();
        try {
            Map<String, Object> data = MAPPER.readValue(SESSION_FILE.toFile(),
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            List<Map<String, Object>> messages = cast(data.get("messages"));
            if (messages == null) return Optional.empty();

            List<Message> history = new ArrayList<>();
            for (Map<String, Object> m : messages) {
                Role role = Role.valueOf(String.valueOf(m.get("role")));
                String content = String.valueOf(m.get("content"));
                String name = String.valueOf(m.get("name"));
                String toolCallId = String.valueOf(m.get("toolCallId"));
                history.add(new Message(role, content,
                        name.isEmpty() ? null : name,
                        toolCallId.isEmpty() ? null : toolCallId,
                        null));
            }

            CliSession session = new CliSession();
            for (Message msg : history) session.addToHistory(msg);
            session.setAgentsMd(Objects.toString(data.get("agentsMd"), ""));
            session.setClaudeMd(Objects.toString(data.get("claudeMd"), ""));
            return Optional.of(session);
        } catch (IOException e) {
            System.err.println("  [WARN] Could not load session: " + e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cast(Object obj) {
        return obj instanceof List ? (List<Map<String, Object>>) obj : null;
    }
}
