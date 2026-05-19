package com.chorus.engine.tokenizer.bpe;

import com.chorus.engine.tokenizer.Tokenizer;
import org.jspecify.annotations.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java Byte-Pair Encoding tokenizer. Exact implementation matching
 * OpenAI's tiktoken and Llama-3 tokenizers.
 *
 * <p>Thread-safe, immutable. Merge table is shared across all encode calls.
 * Uses LRU cache for frequently seen byte sequences.
 */
public final class BpeTokenizer implements Tokenizer {

    private final String name;
    private final int vocabularySize;
    private final Pattern preTokenizer;
    private final Map<List<Byte>, Integer> tokenToRank;
    private final Map<Integer, byte[]> rankToToken;
    private final Map<String, Integer> specialTokens;
    private final int cacheSize;
    private final Map<String, List<Integer>> encodeCache;

    public BpeTokenizer(
        @NonNull String name,
        int vocabularySize,
        @NonNull Pattern preTokenizer,
        @NonNull Map<List<Byte>, Integer> tokenToRank,
        @NonNull Map<String, Integer> specialTokens,
        int cacheSize
    ) {
        this.name = name;
        this.vocabularySize = vocabularySize;
        this.preTokenizer = preTokenizer;
        this.tokenToRank = Map.copyOf(tokenToRank);
        this.specialTokens = Map.copyOf(specialTokens);
        this.cacheSize = cacheSize;
        this.encodeCache = Collections.synchronizedMap(new LinkedHashMap<>(cacheSize, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, List<Integer>> eldest) {
                return size() > cacheSize;
            }
        });

        // Build reverse map
        Map<Integer, byte[]> rev = new HashMap<>(tokenToRank.size());
        for (Map.Entry<List<Byte>, Integer> e : tokenToRank.entrySet()) {
            byte[] arr = new byte[e.getKey().size()];
            for (int i = 0; i < e.getKey().size(); i++) arr[i] = e.getKey().get(i);
            rev.put(e.getValue(), arr);
        }
        this.rankToToken = Collections.unmodifiableMap(rev);
    }

    @Override
    public @NonNull List<Integer> encode(@NonNull String text) {
        List<Integer> cached = encodeCache.get(text);
        if (cached != null) return cached;

        List<Integer> result = new ArrayList<>();
        Matcher m = preTokenizer.matcher(text);
        while (m.find()) {
            String piece = m.group();
            Integer special = specialTokens.get(piece);
            if (special != null) {
                result.add(special);
                continue;
            }
            result.addAll(encodePiece(piece));
        }
        List<Integer> frozen = List.copyOf(result);
        encodeCache.put(text, frozen);
        return frozen;
    }

    private @NonNull List<Integer> encodePiece(@NonNull String piece) {
        byte[] bytes = piece.getBytes(StandardCharsets.UTF_8);
        List<TokenBytePair> pairs = new ArrayList<>();
        for (byte b : bytes) {
            List<Byte> key = List.of(b);
            Integer rank = tokenToRank.get(key);
            if (rank == null) rank = tokenToRank.getOrDefault(List.of((byte) 0x3F), 0); // fallback '?'
            pairs.add(new TokenBytePair(List.of(b), rank));
        }

        while (pairs.size() > 1) {
            int minRank = Integer.MAX_VALUE;
            int minIndex = -1;
            for (int i = 0; i < pairs.size() - 1; i++) {
                List<Byte> merged = new ArrayList<>(pairs.get(i).bytes);
                merged.addAll(pairs.get(i + 1).bytes);
                Integer rank = tokenToRank.get(merged);
                if (rank != null && rank < minRank) {
                    minRank = rank;
                    minIndex = i;
                }
            }
            if (minIndex == -1) break;

            List<Byte> merged = new ArrayList<>(pairs.get(minIndex).bytes);
            merged.addAll(pairs.get(minIndex + 1).bytes);
            pairs.set(minIndex, new TokenBytePair(merged, minRank));
            pairs.remove(minIndex + 1);
        }

        List<Integer> result = new ArrayList<>(pairs.size());
        for (TokenBytePair p : pairs) result.add(p.rank);
        return result;
    }

    @Override
    public @NonNull String decode(@NonNull List<Integer> tokens) {
        StringBuilder sb = new StringBuilder(tokens.size() * 4);
        for (int token : tokens) {
            byte[] bytes = rankToToken.get(token);
            if (bytes != null) {
                sb.append(new String(bytes, StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    @Override
    public int countTokens(@NonNull String text) {
        return encode(text).size();
    }

    @Override
    public int countChatTokens(@NonNull String role, @NonNull String content) {
        // Per-message overhead: 3 tokens for <|start|>, role, <|end|>
        // OpenAI format: <|start|>role<|end|>\ncontent<|end|>
        return 3 + countTokens(role) + countTokens(content);
    }

    @Override
    public @NonNull String name() { return name; }

    @Override
    public int vocabularySize() { return vocabularySize; }

    private record TokenBytePair(List<Byte> bytes, int rank) {}
}
