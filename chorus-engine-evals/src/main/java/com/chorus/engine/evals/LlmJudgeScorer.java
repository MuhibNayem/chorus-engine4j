package com.chorus.engine.evals;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.llm.LlmClient;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-as-judge scorer.
 * Asks an LLM to rate how well the actual output answers the question compared to expected.
 * Rating is 0-10, which is normalized to a score in [0, 1].
 */
public final class LlmJudgeScorer implements EvalScorer {

    private final LlmClient llmClient;
    private final String model;
    private final double passThreshold;

    private static final Pattern RATING_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    public LlmJudgeScorer(@NonNull LlmClient llmClient, @NonNull String model, double passThreshold) {
        this.llmClient = Objects.requireNonNull(llmClient);
        this.model = Objects.requireNonNull(model);
        if (passThreshold < 0 || passThreshold > 1) {
            throw new IllegalArgumentException("passThreshold must be in [0, 1]");
        }
        this.passThreshold = passThreshold;
    }

    @Override
    public @NonNull EvalResult score(@NonNull EvalCase testCase, @NonNull String actualOutput) {
        String prompt = buildPrompt(testCase.input(), testCase.expectedOutput(), actualOutput);

        ChatRequest request = ChatRequest.builder()
            .model(model)
            .messages(List.of(Message.user(prompt)))
            .temperature(0.0)
            .maxTokens(256)
            .build();

        ChatResponse response = llmClient.complete(request, CancellationToken.create());
        String content = response.message().content().trim();

        double rating = extractRating(content);
        double score = rating / 10.0;
        boolean passed = score >= passThreshold;

        String reasoning = String.format(
            "LLM judge rated %s/10 (threshold: %.1f/10). Reasoning: %s",
            rating, passThreshold * 10, content);

        return new EvalResult(testCase.id(), passed, score, actualOutput, reasoning);
    }

    private @NonNull String buildPrompt(@NonNull String question, @NonNull String expected, @NonNull String actual) {
        return """
            You are an expert evaluator. Rate how well the ACTUAL OUTPUT answers the QUESTION compared to the EXPECTED OUTPUT.

            QUESTION: %s

            EXPECTED OUTPUT: %s

            ACTUAL OUTPUT: %s

            Provide a rating from 0 to 10, where 10 means the actual output is as good as or better than the expected output.
            Start your response with the numeric rating, followed by a brief explanation.
            """.formatted(question, expected, actual);
    }

    private double extractRating(@NonNull String content) {
        Matcher matcher = RATING_PATTERN.matcher(content);
        if (matcher.find()) {
            try {
                double rating = Double.parseDouble(matcher.group(1));
                return Math.max(0, Math.min(10, rating));
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        return 0.0;
    }
}
