package com.chorus.engine.graph.channel;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Channel reducer for typed state fields in a StateGraph.
 */
public interface Channel<T> {

    T merge(T existing, T update);

    T defaultValue();

    static <T> Channel<T> lastValue(T defaultValue) {
        return new Channel<>() {
            @Override public T merge(T existing, T update) { return update; }
            @Override public T defaultValue() { return defaultValue; }
        };
    }

    static <T> Channel<List<T>> append() {
        return new Channel<>() {
            @Override public List<T> merge(List<T> existing, List<T> update) {
                List<T> result = new java.util.ArrayList<>(existing);
                result.addAll(update);
                return result;
            }
            @Override public List<T> defaultValue() { return List.of(); }
        };
    }

    static <T> Channel<List<T>> prepend() {
        return new Channel<>() {
            @Override public List<T> merge(List<T> existing, List<T> update) {
                List<T> result = new java.util.ArrayList<>(update);
                result.addAll(existing);
                return result;
            }
            @Override public List<T> defaultValue() { return List.of(); }
        };
    }

    static Channel<Integer> sum() {
        return new Channel<>() {
            @Override public Integer merge(Integer existing, Integer update) { return existing + update; }
            @Override public Integer defaultValue() { return 0; }
        };
    }

    static <T> Channel<Set<T>> setUnion() {
        return new Channel<>() {
            @Override public Set<T> merge(Set<T> existing, Set<T> update) {
                Set<T> result = new java.util.HashSet<>(existing);
                result.addAll(update);
                return result;
            }
            @Override public Set<T> defaultValue() { return Set.of(); }
        };
    }

    static <K, V> Channel<Map<K, V>> mapMerge() {
        return new Channel<>() {
            @Override public Map<K, V> merge(Map<K, V> existing, Map<K, V> update) {
                Map<K, V> result = new java.util.HashMap<>(existing);
                result.putAll(update);
                return result;
            }
            @Override public Map<K, V> defaultValue() { return Map.of(); }
        };
    }

    static <T> Channel<T> binaryOperator(T defaultValue, java.util.function.BinaryOperator<T> operator) {
        return new Channel<>() {
            @Override public T merge(T existing, T update) { return operator.apply(existing, update); }
            @Override public T defaultValue() { return defaultValue; }
        };
    }

    static <T> Channel<T> withDefault(T defaultValue) {
        return lastValue(defaultValue);
    }
}
