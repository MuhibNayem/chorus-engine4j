package com.chorus.engine.telemetry;

import com.chorus.engine.telemetry.redaction.RedactionProcessor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class RedactionProcessorTest {

    private final RedactionProcessor processor = new RedactionProcessor();

    @Test
    void redactsEmail() {
        String input = "Contact me at alice@example.com for details.";
        String result = processor.redact(input);
        assertThat(result).doesNotContain("alice@example.com");
        assertThat(result).contains("[REDACTED]");
    }

    @Test
    void redactsSsn() {
        String input = "My SSN is 123-45-6789.";
        String result = processor.redact(input);
        assertThat(result).doesNotContain("123-45-6789");
        assertThat(result).contains("[REDACTED]");
    }

    @Test
    void redactsCreditCard() {
        String input = "Card: 4111-1111-1111-1111";
        String result = processor.redact(input);
        assertThat(result).doesNotContain("4111-1111-1111-1111");
        assertThat(result).contains("[REDACTED]");
    }

    @Test
    void redactsPhoneNumberDash() {
        String input = "Call 555-123-4567 today.";
        String result = processor.redact(input);
        assertThat(result).doesNotContain("555-123-4567");
        assertThat(result).contains("[REDACTED]");
    }

    @Test
    void redactsPhoneNumberParens() {
        String input = "Call (555) 123-4567 today.";
        String result = processor.redact(input);
        assertThat(result).doesNotContain("(555) 123-4567");
        assertThat(result).contains("[REDACTED]");
    }

    @Test
    void originalStringNotModified() {
        String original = "Email: test@example.com";
        String result = processor.redact(original);
        assertThat(original).isEqualTo("Email: test@example.com");
        assertThat(result).isNotEqualTo(original);
    }

    @Test
    void redactMapPreservesNonStringValues() {
        Map<String, Object> input = Map.of(
            "email", "user@example.com",
            "count", 42,
            "active", true
        );
        Map<String, Object> result = processor.redactMap(input);
        assertThat(result.get("email")).isEqualTo("[REDACTED]");
        assertThat(result.get("count")).isEqualTo(42);
        assertThat(result.get("active")).isEqualTo(true);
    }

    @Test
    void noMatchReturnsOriginal() {
        String input = "Hello world, no PII here.";
        String result = processor.redact(input);
        assertThat(result).isEqualTo(input);
    }
}
