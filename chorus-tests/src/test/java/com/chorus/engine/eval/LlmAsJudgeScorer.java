package com.chorus.engine.eval;

import com.chorus.engine.core.event.ChatMessage;
import com.chorus.engine.core.llm.ChorusChatModel;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uses an LLM to judge how well the actual output satisfies a rubric.
 * The LLM is prompted to return a score between 0 and 1.
 */
public class LlmAsJudgeScorer implements Scorer {

    private static final Pattern SCORE_PATTERN = Pattern.compile("(0(\\.\\d+)?|1(\\.0+)?)");
    private static final String DEFAULT_SYSTEM_PROMPT = """
        You are an expert evaluator. Your task is to score how well a given output satisfies a rubric.
        Respond with only a single numeric score between 0.0 and 1.0, where 1.0 means perfect satisfaction.
        Do not include any explanation or additional text.
        """;

    private final ChorusChatModel chatModel;
    private final String model;
    private final String systemPrompt;

    public LlmAsJudgeScorer(ChorusChatModel chatModel, String model) {
        this(chatModel, model, DEFAULT_SYSTEM_PROMPT);
    }

    public LlmAsJudgeScorer(ChorusChatModel chatModel, String model, String systemPrompt) {
        this.chatModel = chatModel;
        this.model = model;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public double score(String expected, String actual) {
        if (expected == null || actual == null || chatModel == null) {
            return 0.0;
        }
        String prompt = buildPrompt(expected, actual);
        try {
            ChorusChatModel.ModelResponse response = chatModel.generate(
                List.of(ChatMessage.user(prompt)),
                systemPrompt,
                List.of(),
                model
            ).join();
            return parseScore(response.content());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String buildPrompt(String rubric, String output) {
        return "Rubric:\n" + rubric + "\n\nOutput:\n" + output + "\n\nScore:";
    }

    private double parseScore(String content) {
        if (content == null || content.isBlank()) {
            return 0.0;
        }
        Matcher matcher = SCORE_PATTERN.matcher(content.trim());
        if (matcher.find()) {
            try {
                double score = Double.parseDouble(matcher.group());
                return Math.max(0.0, Math.min(1.0, score));
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }
}
