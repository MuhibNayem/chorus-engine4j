package com.chorus.engine.core.tokenizer;

import java.util.List;

/**
 * Exact BPE tokenizer implementation.
 * Wraps a {@link BpeCore} to provide the {@link ChorusTokenizer} interface.
 */
public class BpeTokenizer implements ChorusTokenizer {

    private final BpeCore core;
    private final String encodingName;

    public BpeTokenizer(BpeCore core, String encodingName) {
        this.core = core;
        this.encodingName = encodingName;
    }

    @Override
    public List<Integer> encode(String text) {
        return encode(text, SpecialTokenHandling.RAISE);
    }

    @Override
    public List<Integer> encode(String text, SpecialTokenHandling handling) {
        return core.encode(text, handling);
    }

    @Override
    public String decode(List<Integer> tokens) {
        return core.decode(tokens);
    }

    @Override
    public int countTokens(String text) {
        return countTokens(text, SpecialTokenHandling.RAISE);
    }

    @Override
    public int countTokens(String text, SpecialTokenHandling handling) {
        return encode(text, handling).size();
    }

    @Override
    public int vocabSize() {
        return core.vocabSize();
    }

    @Override
    public String encodingName() {
        return encodingName;
    }

    @Override
    public boolean isExact() {
        return true;
    }
}
