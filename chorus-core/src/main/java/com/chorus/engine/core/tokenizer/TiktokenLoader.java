package com.chorus.engine.core.tokenizer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Loads tiktoken-format tokenizer files.
 *
 * Tiktoken file format:
 *   Each line: {@code base64_encoded_token_bytes  rank}
 *   The base64 string decodes to the token's byte sequence.
 *   Rank determines merge priority (lower = merge first).
 */
public class TiktokenLoader {

    /**
     * Load a tiktoken file from an InputStream.
     */
    public static BpeCore load(InputStream input, Pattern splitPattern) throws IOException {
        return load(input, splitPattern, null);
    }

    /**
     * Load a tiktoken file with special tokens.
     */
    public static BpeCore load(InputStream input, Pattern splitPattern,
                                Map<String, Integer> specialTokens) throws IOException {
        Map<byte[], Integer> tokenToId = new LinkedHashMap<>();
        Map<BpeCore.BytePair, Integer> mergeRanks = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("\\s+");
                if (parts.length != 2) {
                    throw new IOException("Invalid line " + lineNum + ": " + line);
                }

                byte[] tokenBytes = Base64.getDecoder().decode(parts[0]);
                int rank = Integer.parseInt(parts[1]);
                tokenToId.put(tokenBytes, rank);

                // Build merge ranks for pairs
                if (tokenBytes.length >= 2) {
                    for (int i = 0; i < tokenBytes.length - 1; i++) {
                        byte[] first = new byte[]{tokenBytes[i]};
                        byte[] second = new byte[]{tokenBytes[i + 1]};
                        BpeCore.BytePair pair = new BpeCore.BytePair(first, second);
                        mergeRanks.merge(pair, rank, Math::min);
                    }
                }
            }
        }

        return new BpeCore(tokenToId, mergeRanks, splitPattern, specialTokens);
    }

    /**
     * Load from a file path.
     */
    public static BpeCore load(String filePath, Pattern splitPattern,
                                Map<String, Integer> specialTokens) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath)) {
            return load(fis, splitPattern, specialTokens);
        }
    }

    /**
     * Load from classpath resource.
     */
    public static BpeCore loadFromResource(String resourcePath, Pattern splitPattern,
                                            Map<String, Integer> specialTokens) throws IOException {
        InputStream is = TiktokenLoader.class.getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            throw new FileNotFoundException("Resource not found: " + resourcePath);
        }
        try (is) {
            return load(is, splitPattern, specialTokens);
        }
    }
}
