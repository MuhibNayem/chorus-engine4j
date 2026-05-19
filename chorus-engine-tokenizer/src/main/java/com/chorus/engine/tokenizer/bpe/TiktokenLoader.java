package com.chorus.engine.tokenizer.bpe;

import org.jspecify.annotations.NonNull;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.Base64;

/**
 * Loads tokenizer data from .tiktoken files and special_tokens_map.json.
 *
 * <p>.tiktoken format: each line is {@code base64(token_bytes)} + space + rank.
 * Pre-tokenizer regex is hardcoded per encoding name (cl100k_base, o200k_base, etc.).
 */
public final class TiktokenLoader {

    private TiktokenLoader() {}

    public static @NonNull BpeTokenizer load(
        @NonNull String name,
        int vocabularySize,
        @NonNull InputStream tiktokenStream,
        @NonNull Map<String, Integer> specialTokens,
        int cacheSize
    ) throws IOException {
        Map<List<Byte>, Integer> tokenToRank = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(tiktokenStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int space = line.indexOf(' ');
                if (space < 0) continue;
                String b64 = line.substring(0, space);
                int rank = Integer.parseInt(line.substring(space + 1).trim());
                byte[] tokenBytes = Base64.getDecoder().decode(b64);
                List<Byte> key = new ArrayList<>(tokenBytes.length);
                for (byte b : tokenBytes) key.add(b);
                tokenToRank.put(key, rank);
            }
        }
        return new BpeTokenizer(name, vocabularySize, preTokenizerFor(name), tokenToRank, specialTokens, cacheSize);
    }

    /**
     * Returns the standard pre-tokenizer regex for known encodings.
     */
    public static @NonNull Pattern preTokenizerFor(@NonNull String name) {
        return switch (name) {
            case "cl100k_base", "o200k_base" -> Pattern.compile(
                "'(?i:[sdmt]|ll|ve|re)|[^\\r\\n\\p{L}\\p{N}]?+\\p{L}++|\\p{N}{1,3}+| ?[^\\s\\p{L}\\p{N}]++[\\r\\n]*+|\\s++$",
                Pattern.UNICODE_CHARACTER_CLASS
            );
            case "llama-3" -> Pattern.compile(
                "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}{1,3}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+",
                Pattern.UNICODE_CHARACTER_CLASS
            );
            case "deepseek" -> Pattern.compile(
                "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}{1,3}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+",
                Pattern.UNICODE_CHARACTER_CLASS
            );
            case "qwen" -> Pattern.compile(
                "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}{1,3}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+",
                Pattern.UNICODE_CHARACTER_CLASS
            );
            default -> Pattern.compile(
                "\\S+",
                Pattern.UNICODE_CHARACTER_CLASS
            );
        };
    }
}
