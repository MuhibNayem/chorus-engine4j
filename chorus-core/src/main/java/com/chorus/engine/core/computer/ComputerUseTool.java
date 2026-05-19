package com.chorus.engine.core.computer;

import com.chorus.engine.core.tool.AgentTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Tool that exposes computer-use actions to the agent loop.
 * Implementations provide platform-specific automation (e.g., Playwright, PyAutoGUI bridge, etc.).
 */
public interface ComputerUseTool extends AgentTool {

    @Override
    default String name() { return "computer_use"; }

    @Override
    default String description() {
        return "Control a computer by taking screenshots, clicking, typing, scrolling, and pressing keys.";
    }

    @Override
    default Map<String, Object> schema() {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("action", Map.of("type", "string", "enum", List.of(
            "screenshot", "click", "double_click", "type", "keypress",
            "scroll", "wait", "move", "drag"
        )));
        properties.put("x", Map.of("type", "integer", "description", "X coordinate for click/move/drag/scroll"));
        properties.put("y", Map.of("type", "integer", "description", "Y coordinate for click/move/drag/scroll"));
        properties.put("button", Map.of("type", "string", "enum", List.of("left", "right", "middle")));
        properties.put("text", Map.of("type", "string", "description", "Text to type"));
        properties.put("keys", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Keys to press"));
        properties.put("scrollX", Map.of("type", "integer"));
        properties.put("scrollY", Map.of("type", "integer"));
        properties.put("toX", Map.of("type", "integer", "description", "Target X for drag"));
        properties.put("toY", Map.of("type", "integer", "description", "Target Y for drag"));
        properties.put("ms", Map.of("type", "integer", "description", "Milliseconds to wait"));

        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        return schema;
    }

    @Override
    default CompletableFuture<String> invoke(Map<String, Object> args) {
        try {
            String actionType = (String) args.get("action");
            ComputerAction action = parseAction(actionType, args);
            return execute(action);
        } catch (Exception e) {
            return CompletableFuture.completedFuture("Error: " + e.getMessage());
        }
    }

    /**
     * Execute a computer action. Implementation-specific.
     */
    CompletableFuture<String> execute(ComputerAction action);

    /**
     * Take a screenshot and return base64-encoded PNG.
     */
    CompletableFuture<String> screenshot();

    private static ComputerAction parseAction(String type, Map<String, Object> args) {
        return switch (type) {
            case "screenshot" -> new ComputerAction.ScreenshotAction();
            case "click" -> new ComputerAction.ClickAction(
                toInt(args.get("x")), toInt(args.get("y")), (String) args.get("button"));
            case "double_click" -> new ComputerAction.DoubleClickAction(
                toInt(args.get("x")), toInt(args.get("y")));
            case "type" -> new ComputerAction.TypeAction((String) args.get("text"));
            case "keypress" -> new ComputerAction.KeypressAction(
                ((List<?>) args.getOrDefault("keys", List.of())).stream()
                    .map(Object::toString).toArray(String[]::new));
            case "scroll" -> new ComputerAction.ScrollAction(
                toInt(args.get("x")), toInt(args.get("y")),
                toInt(args.get("scrollX")), toInt(args.get("scrollY")));
            case "wait" -> new ComputerAction.WaitAction(toInt(args.get("ms")));
            case "move" -> new ComputerAction.MoveAction(toInt(args.get("x")), toInt(args.get("y")));
            case "drag" -> new ComputerAction.DragAction(
                toInt(args.get("x")), toInt(args.get("y")),
                toInt(args.get("toX")), toInt(args.get("toY")));
            default -> throw new IllegalArgumentException("Unknown action: " + type);
        };
    }

    private static int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(value.toString());
    }
}
