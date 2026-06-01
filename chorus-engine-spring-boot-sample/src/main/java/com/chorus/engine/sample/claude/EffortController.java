package com.chorus.engine.sample.claude;

final class EffortController {

    enum Level { FAST, NORMAL, HIGH, XHIGH }

    private Level level = Level.NORMAL;

    void setLevel(Level level) { this.level = level; }
    Level getLevel() { return level; }

    void setFast(boolean fast) { this.level = fast ? Level.FAST : Level.NORMAL; }
    boolean isFast() { return level == Level.FAST; }

    double getTemperature() {
        return switch (level) {
            case FAST -> 0.3;
            case NORMAL -> 0.7;
            case HIGH -> 0.9;
            case XHIGH -> 0.95;
        };
    }

    int getMaxTokens() {
        return switch (level) {
            case FAST -> 1024;
            case NORMAL -> 4096;
            case HIGH -> 8192;
            case XHIGH -> 16384;
        };
    }

    int getMaxRounds() {
        return switch (level) {
            case FAST -> 10;
            case NORMAL -> 25;
            case HIGH -> 50;
            case XHIGH -> 100;
        };
    }

    String getLabel() {
        return switch (level) {
            case FAST -> "fast";
            case NORMAL -> "normal";
            case HIGH -> "high";
            case XHIGH -> "xhigh";
        };
    }
}
