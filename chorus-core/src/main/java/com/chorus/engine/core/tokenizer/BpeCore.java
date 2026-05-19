package com.chorus.engine.core.tokenizer;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Core Byte-Pair Encoding (BPE) implementation.
 * Implements the tiktoken-style BPE algorithm with regex pre-tokenization.
 */
public class BpeCore {

    private final Map<byte[], Integer> tokenToId;
    private final Map<Integer, byte[]> idToToken;
    private final Map<BytePair, Integer> mergeRanks;
    private final Pattern splitPattern;
    private final Map<String, Integer> specialTokens;
    private final int vocabSize;

    public BpeCore(
        Map<byte[], Integer> tokenToId,
        Map<BytePair, Integer> mergeRanks,
        Pattern splitPattern,
        Map<String, Integer> specialTokens
    ) {
        this.tokenToId = tokenToId;
        this.mergeRanks = mergeRanks;
        this.splitPattern = splitPattern;
        this.specialTokens = specialTokens != null ? specialTokens : Map.of();
        this.vocabSize = tokenToId.size();

        // Build reverse mapping
        this.idToToken = new HashMap<>(vocabSize);
        for (var entry : tokenToId.entrySet()) {
            this.idToToken.put(entry.getValue(), entry.getKey());
        }
    }

    public List<Integer> encode(String text, ChorusTokenizer.SpecialTokenHandling handling) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }

        List<Integer> result = new ArrayList<>();

        // Handle special tokens first if they're present in text
        if (handling == ChorusTokenizer.SpecialTokenHandling.ALLOW && !specialTokens.isEmpty()) {
            text = processSpecialTokens(text, result);
        } else if (handling == ChorusTokenizer.SpecialTokenHandling.RAISE) {
            for (String special : specialTokens.keySet()) {
                if (text.contains(special)) {
                    throw new IllegalArgumentException("Special token found in text: " + special);
                }
            }
        }

        // Regex pre-tokenization
        var matcher = splitPattern.matcher(text);
        int lastEnd = 0;

        while (matcher.find()) {
            // Add any text between matches (shouldn't happen with good regex)
            if (matcher.start() > lastEnd) {
                String between = text.substring(lastEnd, matcher.start());
                result.addAll(encodeChunk(between));
            }
            result.addAll(encodeChunk(matcher.group()));
            lastEnd = matcher.end();
        }

        if (lastEnd < text.length()) {
            result.addAll(encodeChunk(text.substring(lastEnd)));
        }

        return result;
    }

    private String processSpecialTokens(String text, List<Integer> result) {
        // Simple greedy special token extraction
        // In production, this would use a trie for efficiency
        StringBuilder remaining = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            boolean matched = false;
            for (var entry : specialTokens.entrySet()) {
                String special = entry.getKey();
                if (text.startsWith(special, i)) {
                    if (remaining.length() > 0) {
                        result.addAll(encodeChunk(remaining.toString()));
                        remaining.setLength(0);
                    }
                    result.add(entry.getValue());
                    i += special.length();
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                remaining.append(text.charAt(i));
                i++;
            }
        }
        return remaining.toString();
    }

    private List<Integer> encodeChunk(String chunk) {
        // Convert to bytes (UTF-8)
        byte[] bytes = chunk.getBytes(StandardCharsets.UTF_8);

        // Start with individual bytes as tokens
        List<byte[]> tokens = new ArrayList<>(bytes.length);
        for (byte b : bytes) {
            tokens.add(new byte[]{b});
        }

        // BPE merge loop
        while (tokens.size() >= 2) {
            int minRank = Integer.MAX_VALUE;
            int minIndex = -1;

            for (int i = 0; i < tokens.size() - 1; i++) {
                BytePair pair = new BytePair(tokens.get(i), tokens.get(i + 1));
                Integer rank = mergeRanks.get(pair);
                if (rank != null && rank < minRank) {
                    minRank = rank;
                    minIndex = i;
                }
            }

            if (minIndex == -1) {
                break; // No more merges possible
            }

            // Merge the pair
            byte[] merged = concat(tokens.get(minIndex), tokens.get(minIndex + 1));
            tokens.set(minIndex, merged);
            tokens.remove(minIndex + 1);
        }

        // Map tokens to IDs
        List<Integer> ids = new ArrayList<>(tokens.size());
        for (byte[] token : tokens) {
            Integer id = tokenToId.get(token);
            if (id != null) {
                ids.add(id);
            } else {
                // Fallback: encode as individual bytes
                for (byte b : token) {
                    ids.add(byteToId(b));
                }
            }
        }

        return ids;
    }

    private int byteToId(byte b) {
        // In tiktoken, the first 256 IDs map directly to bytes
        // This is the standard byte-level BPE fallback
        byte[] singleByte = new byte[]{b};
        Integer id = tokenToId.get(singleByte);
        return id != null ? id : (b & 0xFF);
    }

    public String decode(List<Integer> tokens) {
        StringBuilder result = new StringBuilder();
        for (int id : tokens) {
            byte[] token = idToToken.get(id);
            if (token != null) {
                result.append(new String(token, StandardCharsets.UTF_8));
            }
        }
        return result.toString();
    }

    public int vocabSize() {
        return vocabSize;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    /**
     * Represents a pair of byte sequences for BPE merging.
     */
    public record BytePair(byte[] first, byte[] second) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BytePair other)) return false;
            return Arrays.equals(first, other.first) && Arrays.equals(second, other.second);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(first) + Arrays.hashCode(second);
        }
    }
}
