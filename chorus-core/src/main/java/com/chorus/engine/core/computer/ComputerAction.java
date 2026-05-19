package com.chorus.engine.core.computer;

/**
 * Sealed hierarchy of computer-use actions for GUI automation.
 * Inspired by Anthropic's computer use and OpenAI's CUA.
 */
public sealed interface ComputerAction {

    String type();

    record ScreenshotAction() implements ComputerAction {
        @Override public String type() { return "screenshot"; }
    }

    record ClickAction(int x, int y, String button) implements ComputerAction {
        public ClickAction { if (button == null) button = "left"; }
        @Override public String type() { return "click"; }
    }

    record DoubleClickAction(int x, int y) implements ComputerAction {
        @Override public String type() { return "double_click"; }
    }

    record TypeAction(String text) implements ComputerAction {
        @Override public String type() { return "type"; }
    }

    record KeypressAction(String[] keys) implements ComputerAction {
        @Override public String type() { return "keypress"; }
    }

    record ScrollAction(int x, int y, int scrollX, int scrollY) implements ComputerAction {
        @Override public String type() { return "scroll"; }
    }

    record WaitAction(int ms) implements ComputerAction {
        public WaitAction { if (ms <= 0) ms = 1000; }
        @Override public String type() { return "wait"; }
    }

    record MoveAction(int x, int y) implements ComputerAction {
        @Override public String type() { return "move"; }
    }

    record DragAction(int x, int y, int toX, int toY) implements ComputerAction {
        @Override public String type() { return "drag"; }
    }
}
