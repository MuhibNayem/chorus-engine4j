package com.chorus.engine.telemetry;

import com.chorus.engine.telemetry.logging.StructuredLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.*;

class StructuredLoggerTest {

    private StructuredLogger logger;
    private CapturingHandler handler;

    @BeforeEach
    void setUp() {
        logger = new StructuredLogger();
        handler = new CapturingHandler();

        Logger fallback = Logger.getLogger(StructuredLogger.class.getName());
        fallback.setLevel(Level.ALL);
        for (Handler h : fallback.getHandlers()) {
            fallback.removeHandler(h);
        }
        fallback.addHandler(handler);
    }

    @Test
    void infoOutputsJson() {
        logger.info("Test message");
        String json = handler.lastMessage();
        assertThat(json).startsWith("{").endsWith("}");
        assertThat(json).contains("\"message\":\"Test message\"");
        assertThat(json).contains("\"level\":\"INFO\"");
        assertThat(json).contains("\"timestamp\":\"");
    }

    @Test
    void warnOutputsCorrectLevel() {
        logger.warn("Warning message");
        String json = handler.lastMessage();
        assertThat(json).contains("\"level\":\"WARN\"");
    }

    @Test
    void errorOutputsCorrectLevel() {
        logger.error("Error message");
        String json = handler.lastMessage();
        assertThat(json).contains("\"level\":\"ERROR\"");
    }

    @Test
    void fieldsIncludedInOutput() {
        logger.info("With fields", Map.of("runId", "run-123", "agentId", "agent-a"));
        String json = handler.lastMessage();
        assertThat(json).contains("\"runId\":\"run-123\"");
        assertThat(json).contains("\"agentId\":\"agent-a\"");
    }

    @Test
    void mdcContextIncluded() {
        logger.putContext("runId", "run-456");
        logger.info("Context test");
        String json = handler.lastMessage();
        assertThat(json).contains("\"runId\":\"run-456\"");
    }

    @Test
    void mdcCanBeCleared() {
        logger.putContext("runId", "run-456");
        logger.clearContext();
        logger.info("After clear");
        String json = handler.lastMessage();
        assertThat(json).doesNotContain("\"runId\":\"run-456\"");
    }

    @Test
    void numericFieldsNotQuoted() {
        logger.info("Numbers", Map.of("count", 42, "rate", 0.95));
        String json = handler.lastMessage();
        assertThat(json).contains("\"count\":42");
        assertThat(json).contains("\"rate\":0.95");
    }

    @Test
    void booleanFieldsNotQuoted() {
        logger.info("Bools", Map.of("ok", true));
        String json = handler.lastMessage();
        assertThat(json).contains("\"ok\":true");
    }

    @Test
    void jsonEscapesQuotes() {
        logger.info("Say \"hello\"");
        String json = handler.lastMessage();
        assertThat(json).contains("Say \\\"hello\\\"");
    }

    static class CapturingHandler extends Handler {
        private volatile String lastMessage;

        @Override
        public void publish(java.util.logging.LogRecord record) {
            lastMessage = record.getMessage();
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}

        String lastMessage() {
            return lastMessage;
        }
    }
}
