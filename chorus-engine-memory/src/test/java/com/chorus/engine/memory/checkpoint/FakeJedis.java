package com.chorus.engine.memory.checkpoint;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.resps.Tuple;

import java.util.*;

/**
 * In-memory fake Jedis for testing RedisCheckpointer without a real Redis server.
 */
class FakeJedis extends Jedis {

    private final Map<String, Map<String, String>> hashes = new HashMap<>();
    private final Map<String, NavigableMap<Double, String>> sortedSets = new HashMap<>();

    FakeJedis() {
        super("localhost", 6379);
    }

    @Override
    public long hset(String key, Map<String, String> hash) {
        hashes.computeIfAbsent(key, k -> new HashMap<>()).putAll(hash);
        return hash.size();
    }

    @Override
    public long zadd(String key, double score, String member) {
        sortedSets.computeIfAbsent(key, k -> new TreeMap<>()).put(score, member);
        return 1;
    }

    @Override
    public long expire(String key, long seconds) {
        return 1;
    }

    @Override
    public List<String> zrevrange(String key, long start, long stop) {
        NavigableMap<Double, String> set = sortedSets.get(key);
        if (set == null) return List.of();
        List<String> result = new ArrayList<>(set.descendingMap().values());
        return slice(result, start, stop);
    }

    @Override
    public Map<String, String> hgetAll(String key) {
        Map<String, String> hash = hashes.get(key);
        return hash != null ? new HashMap<>(hash) : Map.of();
    }

    @Override
    public List<Tuple> zrevrangeWithScores(String key, long start, long stop) {
        NavigableMap<Double, String> set = sortedSets.get(key);
        if (set == null) return List.of();
        List<Tuple> result = new ArrayList<>();
        for (Map.Entry<Double, String> e : set.descendingMap().entrySet()) {
            result.add(new Tuple(e.getValue(), e.getKey()));
        }
        return slice(result, start, stop);
    }

    @Override
    public List<Tuple> zrangeWithScores(String key, long start, long stop) {
        NavigableMap<Double, String> set = sortedSets.get(key);
        if (set == null) return List.of();
        List<Tuple> result = new ArrayList<>();
        for (Map.Entry<Double, String> e : set.entrySet()) {
            result.add(new Tuple(e.getValue(), e.getKey()));
        }
        return slice(result, start, stop);
    }

    @Override
    public long del(String key) {
        long count = 0;
        if (hashes.remove(key) != null) count++;
        if (sortedSets.remove(key) != null) count++;
        return count;
    }

    @Override
    public long del(String... keys) {
        long count = 0;
        for (String k : keys) {
            if (hashes.remove(k) != null) count++;
            if (sortedSets.remove(k) != null) count++;
        }
        return count;
    }

    @Override
    public long zrem(String key, String... members) {
        NavigableMap<Double, String> set = sortedSets.get(key);
        if (set == null) return 0;
        long count = 0;
        Set<String> toRemove = new HashSet<>(Arrays.asList(members));
        Iterator<Map.Entry<Double, String>> it = set.entrySet().iterator();
        while (it.hasNext()) {
            if (toRemove.contains(it.next().getValue())) {
                it.remove();
                count++;
            }
        }
        return count;
    }

    @Override
    public String hget(String key, String field) {
        Map<String, String> hash = hashes.get(key);
        return hash != null ? hash.get(field) : null;
    }

    private static <T> List<T> slice(List<T> list, long start, long stop) {
        int s = (int) start;
        int e = stop < 0 ? list.size() : (int) stop + 1;
        if (s < 0) s = 0;
        if (e > list.size()) e = list.size();
        if (s >= e) return List.of();
        return List.copyOf(list.subList(s, e));
    }
}
