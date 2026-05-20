package com.chorus.engine.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a handler for Chorus events.
 *
 * <p>The method is automatically subscribed to {@code EventBus} at startup.
 * If {@code value()} is empty, the method receives all events.
 * Otherwise, it receives only events matching the specified type(s).
 *
 * <p>Method signature requirements:
 * <ul>
 *   <li>Single parameter of type {@code ChorusEvent} or a subtype</li>
 *   <li>Return type is ignored (should be {@code void})</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * @EventHandler("llm.call")
 * public void onLlmCall(LlmCallEvent event) { ... }
 *
 * @EventHandler // receives all events
 * public void onAnyEvent(ChorusEvent event) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventHandler {
    /** Event type(s) to subscribe to. Empty = all events. */
    String[] value() default {};
}
