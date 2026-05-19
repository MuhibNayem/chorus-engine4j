package com.chorus.engine.tokenizer;

import com.chorus.engine.tokenizer.approximate.ApproximateTokenizer;
import com.chorus.engine.tokenizer.bpe.BpeTokenizer;
import com.chorus.engine.tokenizer.bpe.TiktokenLoader;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry mapping model names and providers to the correct tokenizer.
 * Pre-configured for 50+ models across all major providers.
 *
 * <p>Thread-safe. Tokenizers are lazily loaded and cached.
 */
public final class TokenizerRegistry {

    private final Map<String, Tokenizer> cache = new ConcurrentHashMap<>();
    private final Map<String, String> modelToEncoding = new ConcurrentHashMap<>();

    public TokenizerRegistry() {
        registerDefaults();
    }

    private void registerDefaults() {
        // OpenAI / GPT
        mapModel("gpt-4o", "o200k_base");
        mapModel("gpt-4o-mini", "o200k_base");
        mapModel("gpt-4-turbo", "cl100k_base");
        mapModel("gpt-4", "cl100k_base");
        mapModel("gpt-3.5-turbo", "cl100k_base");
        mapModel("gpt-5", "o200k_base");
        mapModel("o1", "o200k_base");
        mapModel("o1-mini", "o200k_base");
        mapModel("o3", "o200k_base");

        // Anthropic / Claude
        mapModel("claude-3-opus", "approximate-claude");
        mapModel("claude-3-sonnet", "approximate-claude");
        mapModel("claude-3-haiku", "approximate-claude");
        mapModel("claude-3-5-sonnet", "approximate-claude");
        mapModel("claude-3-5-haiku", "approximate-claude");

        // Google / Gemini
        mapModel("gemini-1.5-pro", "approximate-gemini");
        mapModel("gemini-1.5-flash", "approximate-gemini");
        mapModel("gemini-1.0-pro", "approximate-gemini");
        mapModel("gemini-ultra", "approximate-gemini");

        // Meta / Llama
        mapModel("llama-3-8b", "llama-3");
        mapModel("llama-3-70b", "llama-3");
        mapModel("llama-3.1-8b", "llama-3");
        mapModel("llama-3.1-70b", "llama-3");
        mapModel("llama-3.1-405b", "llama-3");

        // Mistral
        mapModel("mistral-7b", "mistral");
        mapModel("mixtral-8x7b", "mistral");
        mapModel("mixtral-8x22b", "mistral");
        mapModel("mistral-large", "mistral");
        mapModel("mistral-small", "mistral");

        // DeepSeek
        mapModel("deepseek-chat", "deepseek");
        mapModel("deepseek-coder", "deepseek");
        mapModel("deepseek-reasoner", "deepseek");

        // Qwen
        mapModel("qwen-2-7b", "qwen");
        mapModel("qwen-2-72b", "qwen");
        mapModel("qwen-2.5-7b", "qwen");
        mapModel("qwen-2.5-72b", "qwen");
        mapModel("qwen-max", "qwen");
        mapModel("qwen-turbo", "qwen");

        // xAI / Grok
        mapModel("grok-1", "approximate-grok");
        mapModel("grok-2", "approximate-grok");
        mapModel("grok-2-mini", "approximate-grok");

        // Moonshot / Kimi
        mapModel("kimi-k1", "approximate-kimi");
        mapModel("kimi-k1.5", "approximate-kimi");
        mapModel("kimi-moonshot", "approximate-kimi");

        // MiniMax
        mapModel("minimax-text-01", "approximate-minimax");

        // Cohere
        mapModel("command-r", "approximate-cohere");
        mapModel("command-r-plus", "approximate-cohere");

        // Local / Open-source
        mapModel("phi-3", "llama-3");
        mapModel("phi-4", "llama-3");
    }

    private void mapModel(String model, String encoding) {
        modelToEncoding.put(model.toLowerCase(), encoding);
    }

    /**
     * Get tokenizer for a model name.
     */
    public @NonNull Tokenizer forModel(@NonNull String modelName) {
        String encoding = modelToEncoding.get(modelName.toLowerCase());
        if (encoding == null) {
            // Fallback: try to infer from model name patterns
            String lower = modelName.toLowerCase();
            if (lower.contains("gpt-4") || lower.contains("gpt-3.5")) encoding = "cl100k_base";
            else if (lower.contains("gpt-5") || lower.contains("o1") || lower.contains("o3")) encoding = "o200k_base";
            else if (lower.contains("llama") || lower.contains("phi")) encoding = "llama-3";
            else if (lower.contains("deepseek")) encoding = "deepseek";
            else if (lower.contains("qwen")) encoding = "qwen";
            else if (lower.contains("claude")) encoding = "approximate-claude";
            else if (lower.contains("gemini")) encoding = "approximate-gemini";
            else encoding = "cl100k_base"; // safest default
        }
        return getOrCreate(encoding);
    }

    /**
     * Get tokenizer for a provider + model combination.
     */
    public @NonNull Tokenizer forProvider(@NonNull String provider, @NonNull String model) {
        return forModel(model);
    }

    private @NonNull Tokenizer getOrCreate(@NonNull String encoding) {
        return cache.computeIfAbsent(encoding, this::createTokenizer);
    }

    private @NonNull Tokenizer createTokenizer(@NonNull String encoding) {
        return switch (encoding) {
            case "cl100k_base" -> loadBpe("cl100k_base", 100_256, "/tokenizers/cl100k_base.tiktoken", Map.of(
                "<|endoftext|>", 100_257,
                "<|fim_prefix|>", 100_258,
                "<|fim_middle|>", 100_259,
                "<|fim_suffix|>", 100_260,
                "<|endofprompt|>", 100_261
            ));
            case "o200k_base" -> loadBpe("o200k_base", 200_019, "/tokenizers/o200k_base.tiktoken", Map.of(
                "<|endoftext|>", 199_999,
                "<|endofprompt|>", 200_000
            ));
            case "llama-3" -> loadBpe("llama-3", 128_000, "/tokenizers/llama-3.tiktoken", Map.of(
                "<|begin_of_text|>", 128_000,
                "<|end_of_text|>", 128_001,
                "<|start_header_id|>", 128_006,
                "<|end_header_id|>", 128_007,
                "<|eot_id|>", 128_009
            ));
            case "deepseek" -> loadBpe("deepseek", 102_400, "/tokenizers/deepseek.tiktoken", Map.of());
            case "qwen" -> loadBpe("qwen", 151_936, "/tokenizers/qwen.tiktoken", Map.of());
            case "approximate-claude" -> new ApproximateTokenizer("approximate-claude");
            case "approximate-gemini" -> new ApproximateTokenizer("approximate-gemini");
            case "approximate-grok" -> new ApproximateTokenizer("approximate-grok");
            case "approximate-kimi" -> new ApproximateTokenizer("approximate-kimi");
            case "approximate-minimax" -> new ApproximateTokenizer("approximate-minimax");
            case "approximate-cohere" -> new ApproximateTokenizer("approximate-cohere");
            default -> new ApproximateTokenizer(encoding);
        };
    }

    private @NonNull BpeTokenizer loadBpe(
        @NonNull String name,
        int vocabSize,
        @NonNull String resourcePath,
        @NonNull Map<String, Integer> specialTokens
    ) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                // Fallback to approximate if .tiktoken file not available at runtime
                return new BpeTokenizer(name, vocabSize, TiktokenLoader.preTokenizerFor(name),
                    Map.of(), specialTokens, 0);
            }
            return TiktokenLoader.load(name, vocabSize, is, specialTokens, 10_000);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load tokenizer " + name, e);
        }
    }

    public void registerCustom(@NonNull String modelName, @NonNull Tokenizer tokenizer) {
        cache.put(modelName.toLowerCase(), tokenizer);
        modelToEncoding.put(modelName.toLowerCase(), modelName.toLowerCase());
    }
}
