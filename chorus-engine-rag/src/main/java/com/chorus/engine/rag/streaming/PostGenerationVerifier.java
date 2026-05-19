package com.chorus.engine.rag.streaming;

import com.chorus.engine.core.context.Message;
import com.chorus.engine.core.reactive.CancellationToken;
import com.chorus.engine.llm.ChatRequest;
import com.chorus.engine.llm.ChatResponse;
import com.chorus.engine.llm.LlmClient;
import com.chorus.engine.rag.document.Chunk;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Verifies whether an already-generated answer is consistent with
 * supplemental chunks that arrived after generation started.
 *
 * <p>Used by {@link GenerationStrategy#PIPELINE} and {@link GenerationStrategy#ADAPTIVE}
 * strategies. When late retrieval waves bring new information, this verifier
 * checks (using a cheap/fast model) whether the original answer:
 * <ul>
 *   <li>Is factually consistent with the new sources ({@link VerificationResult#VERIFIED})</li>
 *   <li>Contains a minor omission ({@link VerificationResult#NEEDS_CORRECTION})</li>
 *   <li>Contains a factual error ({@link VerificationResult#CONTRADICTED})</li>
 * </ul>
 *
 * <p>The verifier is intentionally lightweight. It uses a small/cheap model
 * (e.g., GPT-4o-mini, Claude Haiku, local 3B parameter model) because it only
 * needs to compare an existing answer against a few new chunks — not generate
 * a full answer from scratch.
 *
 * <p>If the answer is contradicted or needs correction, the verifier produces
 * a concise correction paragraph and supporting citations. The UI/app layer
 * can choose to display this inline, as a footnote, or discard it based on
 * the confidence score.
 */
public final class PostGenerationVerifier {

    private final LlmClient verifierClient;
    private final String verifierModel;
    private final double confidenceThreshold;

    public PostGenerationVerifier(
        @NonNull LlmClient verifierClient,
        @NonNull String verifierModel,
        double confidenceThreshold
    ) {
        this.verifierClient = verifierClient;
        this.verifierModel = verifierModel;
        this.confidenceThreshold = confidenceThreshold;
    }

    /**
     * Verify an answer against supplemental chunks.
     *
     * @param query               the original user query
     * @param answer              the generated answer
     * @param supplementalChunks  chunks that arrived after generation started
     * @param token               cancellation token
     * @return verification result
     */
    public @NonNull VerificationResult verify(
        @NonNull String query,
        @NonNull String answer,
        @NonNull List<Chunk> supplementalChunks,
        @NonNull CancellationToken token
    ) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(supplementalChunks, "supplementalChunks");
        Objects.requireNonNull(token, "token");
        if (supplementalChunks.isEmpty()) {
            return new VerificationResult(ResultType.VERIFIED, 1.0, null, List.of(),
                "No supplemental chunks to verify against.");
        }

        String context = buildSupplementalContext(supplementalChunks);
        String prompt = buildPrompt(query, answer, context);

        try {
            ChatRequest request = ChatRequest.builder()
                .model(verifierModel)
                .messages(List.of(Message.user(prompt)))
                .temperature(0.0)
                .maxTokens(512)
                .build();

            ChatResponse response = verifierClient.complete(request, token);
            String verdict = response.message().content().trim();

            return parseVerdict(verdict, supplementalChunks);
        } catch (Exception e) {
            return new VerificationResult(ResultType.VERIFIED, 0.0, null, List.of(),
                "Verification failed: " + e.getMessage());
        }
    }

    private @NonNull String buildSupplementalContext(@NonNull List<Chunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            sb.append("[").append(i + 1).append("] ")
              .append(chunks.get(i).text().trim())
              .append("\n\n");
        }
        return sb.toString().trim();
    }

    private @NonNull String buildPrompt(@NonNull String query, @NonNull String answer, @NonNull String context) {
        return """
            You are a factual verifier. Compare the ANSWER against the SUPPLEMENTAL CONTEXT.
            The supplemental context arrived after the answer was generated.

            Respond in this exact format:
            VERDICT: <VERIFIED | NEEDS_CORRECTION | CONTRADICTED>
            CONFIDENCE: <0.0 to 1.0>
            CORRECTION: <if NEEDS_CORRECTION or CONTRADICTED, provide a concise correction paragraph. If VERIFIED, write "None">
            REASONING: <one-sentence explanation>

            Question: %s

            Answer: %s

            Supplemental Context:
            %s
            """.formatted(query, answer, context);
    }

    private @NonNull VerificationResult parseVerdict(
        @NonNull String verdict,
        @NonNull List<Chunk> supplementalChunks
    ) {
        ResultType type = ResultType.VERIFIED;
        double confidence = 0.5;
        String correction = null;
        String reasoning = "Unable to parse verifier output.";

        String[] lines = verdict.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("VERDICT:")) {
                String v = trimmed.substring(8).trim().toUpperCase();
                type = switch (v) {
                    case "CONTRADICTED" -> ResultType.CONTRADICTED;
                    case "NEEDS_CORRECTION" -> ResultType.NEEDS_CORRECTION;
                    default -> ResultType.VERIFIED;
                };
            } else if (trimmed.startsWith("CONFIDENCE:")) {
                try {
                    confidence = Double.parseDouble(trimmed.substring(11).trim());
                } catch (NumberFormatException ignored) {}
            } else if (trimmed.startsWith("CORRECTION:")) {
                correction = trimmed.substring(11).trim();
                if ("None".equalsIgnoreCase(correction) || "N/A".equalsIgnoreCase(correction)) {
                    correction = null;
                }
            } else if (trimmed.startsWith("REASONING:")) {
                reasoning = trimmed.substring(10).trim();
            }
        }

        boolean actionable = (type == ResultType.CONTRADICTED || type == ResultType.NEEDS_CORRECTION)
            && confidence >= confidenceThreshold;

        List<RagStreamEvent.Citation> citations = supplementalChunks.stream()
            .map(c -> new RagStreamEvent.Citation(0, c.id(), c.documentId(), c.text(), 0.5, "supplemental"))
            .toList();

        return new VerificationResult(type, confidence,
            actionable ? correction : null, citations, reasoning);
    }

    public record VerificationResult(
        @NonNull ResultType type,
        double confidence,
        @Nullable String correction,
        @NonNull List<RagStreamEvent.Citation> supportingCitations,
        @NonNull String reasoning
    ) {
        public boolean isActionable() {
            return (type == ResultType.CONTRADICTED || type == ResultType.NEEDS_CORRECTION)
                && correction != null && !correction.isBlank();
        }
    }

    public enum ResultType {
        VERIFIED,
        NEEDS_CORRECTION,
        CONTRADICTED
    }
}
