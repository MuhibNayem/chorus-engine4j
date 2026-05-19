package com.chorus.engine.core.tokenizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that maps model names to their tokenizers.
 * Supports lazy loading of exact BPE tokenizers and built-in approximate tokenizers.
 */
public class TokenizerRegistry {

    private static final Logger log = LoggerFactory.getLogger(TokenizerRegistry.class);

    private final Map<String, ChorusTokenizer> tokenizers = new ConcurrentHashMap<>();

    public TokenizerRegistry() {
        registerDefaults();
    }

    private void registerDefaults() {
        // OpenAI / GPT models (exact when vocab loaded, otherwise approximate)
        registerApproximate("gpt-4", ApproximateTokenizer.grok()); // cl100k_base proxy
        registerApproximate("gpt-4-turbo", ApproximateTokenizer.grok());
        registerApproximate("gpt-4o", ApproximateTokenizer.grok()); // o200k_base proxy
        registerApproximate("gpt-4o-mini", ApproximateTokenizer.grok());
        registerApproximate("gpt-5", ApproximateTokenizer.grok());
        registerApproximate("gpt-5-mini", ApproximateTokenizer.grok());
        registerApproximate("o3", ApproximateTokenizer.grok());
        registerApproximate("o4-mini", ApproximateTokenizer.grok());

        // Anthropic Claude
        registerApproximate("claude", ApproximateTokenizer.claude());
        registerApproximate("claude-3", ApproximateTokenizer.claude());
        registerApproximate("claude-3-5", ApproximateTokenizer.claude());
        registerApproximate("claude-sonnet", ApproximateTokenizer.claude());
        registerApproximate("claude-sonnet-4", ApproximateTokenizer.claude());
        registerApproximate("claude-sonnet-4-6", ApproximateTokenizer.claude());
        registerApproximate("claude-opus", ApproximateTokenizer.claude());
        registerApproximate("claude-opus-4", ApproximateTokenizer.claude());
        registerApproximate("claude-opus-4-6", ApproximateTokenizer.claude());
        registerApproximate("claude-opus-4-7", ApproximateTokenizer.claudeOpus47());
        registerApproximate("claude-haiku", ApproximateTokenizer.claude());
        registerApproximate("claude-3-haiku", ApproximateTokenizer.claude());

        // Google Gemini
        registerApproximate("gemini", ApproximateTokenizer.gemini());
        registerApproximate("gemini-2", ApproximateTokenizer.gemini());
        registerApproximate("gemini-2-5", ApproximateTokenizer.gemini());
        registerApproximate("gemini-2-5-pro", ApproximateTokenizer.gemini());
        registerApproximate("gemini-2-5-flash", ApproximateTokenizer.gemini());
        registerApproximate("gemini-3", ApproximateTokenizer.gemini());
        registerApproximate("gemini-3-pro", ApproximateTokenizer.gemini());

        // Meta Llama
        registerApproximate("llama", ApproximateTokenizer.grok());
        registerApproximate("llama-3", ApproximateTokenizer.grok());
        registerApproximate("llama-3-1", ApproximateTokenizer.grok());
        registerApproximate("llama-4", ApproximateTokenizer.grok());
        registerApproximate("llama-4-scout", ApproximateTokenizer.grok());
        registerApproximate("llama-4-maverick", ApproximateTokenizer.grok());

        // Google Gemma
        registerApproximate("gemma", ApproximateTokenizer.grok());
        registerApproximate("gemma-2", ApproximateTokenizer.grok());
        registerApproximate("gemma-3", ApproximateTokenizer.grok());

        // Mistral
        registerApproximate("mistral", ApproximateTokenizer.grok());
        registerApproximate("mixtral", ApproximateTokenizer.grok());

        // Alibaba Qwen
        registerApproximate("qwen", ApproximateTokenizer.qwen());
        registerApproximate("qwen-2", ApproximateTokenizer.qwen());
        registerApproximate("qwen-2-5", ApproximateTokenizer.qwen());
        registerApproximate("qwen-3", ApproximateTokenizer.qwen());
        registerApproximate("qwen-max", ApproximateTokenizer.qwen());

        // DeepSeek
        registerApproximate("deepseek", ApproximateTokenizer.deepseek());
        registerApproximate("deepseek-v3", ApproximateTokenizer.deepseek());
        registerApproximate("deepseek-r1", ApproximateTokenizer.deepseek());
        registerApproximate("deepseek-chat", ApproximateTokenizer.deepseek());
        registerApproximate("deepseek-coder", ApproximateTokenizer.deepseek());

        // xAI Grok
        registerApproximate("grok", ApproximateTokenizer.grok());
        registerApproximate("grok-4", ApproximateTokenizer.grok());
        registerApproximate("grok-4-1", ApproximateTokenizer.grok());

        // MiniMax
        registerApproximate("minimax", ApproximateTokenizer.minimax());

        // Moonshot Kimi
        registerApproximate("kimi", ApproximateTokenizer.kimi());
        registerApproximate("kimi-k2", ApproximateTokenizer.kimi());
        registerApproximate("kimi-k2-6", ApproximateTokenizer.kimi());

        // Xiaomi
        registerApproximate("mimo", ApproximateTokenizer.xiaomiMimo());
        registerApproximate("xiaomi-mimo", ApproximateTokenizer.xiaomiMimo());
    }

    public void register(String modelName, ChorusTokenizer tokenizer) {
        tokenizers.put(normalize(modelName), tokenizer);
        log.debug("Registered tokenizer for model: {}", modelName);
    }

    private void registerApproximate(String modelName, ChorusTokenizer tokenizer) {
        tokenizers.put(normalize(modelName), tokenizer);
    }

    public ChorusTokenizer get(String modelName) {
        ChorusTokenizer tokenizer = tokenizers.get(normalize(modelName));
        if (tokenizer == null) {
            log.warn("No exact tokenizer for model '{}', using generic approximation", modelName);
            return ApproximateTokenizer.generic();
        }
        return tokenizer;
    }

    public boolean hasTokenizer(String modelName) {
        return tokenizers.containsKey(normalize(modelName));
    }

    public ChorusTokenizer remove(String modelName) {
        return tokenizers.remove(normalize(modelName));
    }

    private static String normalize(String modelName) {
        return modelName.toLowerCase().trim();
    }

    /**
     * Global singleton instance.
     */
    public static final TokenizerRegistry GLOBAL = new TokenizerRegistry();
}
