package com.chorus.engine.core.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

class TokenCountTest {

    @Test
    void constructionAndTotal() {
        TokenCount tc = new TokenCount(10, 5, "gpt-4");
        assertThat(tc.inputTokens()).isEqualTo(10);
        assertThat(tc.outputTokens()).isEqualTo(5);
        assertThat(tc.tokenizerName()).isEqualTo("gpt-4");
        assertThat(tc.total()).isEqualTo(15);
    }

    @Test
    void plusCombinesCounts() {
        TokenCount a = new TokenCount(10, 5, "gpt-4");
        TokenCount b = new TokenCount(3, 2, "gpt-4");
        TokenCount sum = a.plus(b);
        assertThat(sum.inputTokens()).isEqualTo(13);
        assertThat(sum.outputTokens()).isEqualTo(7);
        assertThat(sum.total()).isEqualTo(20);
    }

    @Test
    void plusRejectsDifferentTokenizer() {
        TokenCount a = new TokenCount(10, 5, "gpt-4");
        TokenCount b = new TokenCount(3, 2, "claude");
        assertThatThrownBy(() -> a.plus(b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different tokenizers");
    }

    @Test
    void withOutputCreatesCopyWithNewOutput() {
        TokenCount original = new TokenCount(10, 5, "gpt-4");
        TokenCount updated = original.withOutput(20);
        assertThat(updated.inputTokens()).isEqualTo(10);
        assertThat(updated.outputTokens()).isEqualTo(20);
        assertThat(updated.tokenizerName()).isEqualTo("gpt-4");
        assertThat(original.outputTokens()).isEqualTo(5);
    }

    @Test
    void negativeInputTokensRejected() {
        assertThatThrownBy(() -> new TokenCount(-1, 0, "gpt-4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputTokens");
    }

    @Test
    void negativeOutputTokensRejected() {
        assertThatThrownBy(() -> new TokenCount(0, -1, "gpt-4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outputTokens");
    }

    @Test
    void nullTokenizerNameRejected() {
        assertThatThrownBy(() -> new TokenCount(0, 0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void zeroTokensAllowed() {
        TokenCount tc = new TokenCount(0, 0, "test");
        assertThat(tc.total()).isZero();
    }

    @ParameterizedTest
    @CsvSource({
            "2147483647, 0, 2147483647",
            "0, 2147483647, 2147483647",
            "1000000, 2000000, 3000000"
    })
    void largeValues(int input, int output, int expectedTotal) {
        TokenCount tc = new TokenCount(input, output, "big");
        assertThat(tc.inputTokens()).isEqualTo(input);
        assertThat(tc.outputTokens()).isEqualTo(output);
        assertThat(tc.total()).isEqualTo(expectedTotal);
    }
}
