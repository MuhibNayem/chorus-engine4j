package com.chorus.engine.checkpoint.redis;

import com.chorus.engine.core.checkpoint.Checkpoint;
import com.chorus.engine.core.checkpoint.CheckpointState;
import com.chorus.engine.core.checkpoint.Checkpointer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.ScanParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Production-grade Redis checkpointer using Jedis client.
 * Stores checkpoints as hash fields (round → JSON) per thread.
 * Supports TTL, pipeline batching, and Redis Cluster via JedisPool abstraction.
 */
public class RedisCheckpointer implements Checkpointer {

    private static final Logger log = LoggerFactory.getLogger(RedisCheckpointer.class);
    private static final int DEFAULT_TTL_SECONDS = 7 * 24 * 60 * 60; // 7 days

    private final JedisPool jedisPool;
    private final ObjectMapper mapper;
    private final int ttlSeconds;

    public RedisCheckpointer(JedisPool jedisPool) {
        this(jedisPool, DEFAULT_TTL_SECONDS);
    }

    public RedisCheckpointer(JedisPool jedisPool, int ttlSeconds) {
        this.jedisPool = jedisPool;
        this.mapper = new ObjectMapper().registerModule(new Jdk8Module());
        this.ttlSeconds = ttlSeconds;
        verifyConnection();
    }

    private void verifyConnection() {
        try (Jedis jedis = jedisPool.getResource()) {
            String pong = jedis.ping();
            if (!"PONG".equals(pong)) {
                throw new RuntimeException("Redis ping failed: " + pong);
            }
            log.info("Redis checkpointer connected");
        }
    }

    private String key(String threadId) {
        return "chorus:ckpt:" + threadId;
    }

    @Override
    public CompletableFuture<Void> save(String threadId, CheckpointState state) {
        return CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String field = String.valueOf(state.round());
                Checkpoint cp = new Checkpoint(null, state.round(), state.messages(),
                    System.currentTimeMillis(),
                    state.waitingForHitl().map(p ->
                        new CheckpointState.HitlPause(p.resumeKey(), p.requests(), p.toolCalls(), p.assistant())));
                String json = mapper.writeValueAsString(cp);
                String redisKey = key(threadId);
                Pipeline pipe = jedis.pipelined();
                pipe.hset(redisKey, field, json);
                pipe.expire(redisKey, ttlSeconds);
                pipe.sync();
            } catch (Exception e) {
                throw new RuntimeException("Redis checkpoint save failed for thread " + threadId, e);
            }
        });
    }

    @Override
    public CompletableFuture<Checkpoint> load(String threadId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String redisKey = key(threadId);
                var fields = jedis.hkeys(redisKey);

                if (fields.isEmpty()) return null;

                int maxRound = fields.stream()
                    .mapToInt(Integer::parseInt)
                    .max().orElse(0);

                String data = jedis.hget(redisKey, String.valueOf(maxRound));
                if (data == null) return null;

                return mapper.readValue(data, Checkpoint.class);
            } catch (Exception e) {
                throw new RuntimeException("Redis checkpoint load failed for thread " + threadId, e);
            }
        });
    }

    @Override
    public CompletableFuture<Checkpoint> loadAt(String threadId, int round) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String data = jedis.hget(key(threadId), String.valueOf(round));
                if (data == null) return null;
                return mapper.readValue(data, Checkpoint.class);
            } catch (Exception e) {
                throw new RuntimeException("Redis checkpoint loadAt failed for thread " + threadId, e);
            }
        });
    }

    @Override
    public CompletableFuture<List<Checkpoint>> list(String threadId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String redisKey = key(threadId);
                List<Checkpoint> results = new ArrayList<>();
                ScanParams scanParams = new ScanParams().count(100);
                String cursor = ScanParams.SCAN_POINTER_START;

                do {
                    var scanResult = jedis.hscan(redisKey, cursor, scanParams);
                    for (var entry : scanResult.getResult()) {
                        String data = jedis.hget(redisKey, entry.getKey());
                        if (data != null) {
                            results.add(mapper.readValue(data, Checkpoint.class));
                        }
                    }
                    cursor = scanResult.getCursor();
                } while (!cursor.equals(ScanParams.SCAN_POINTER_START));

                results.sort((a, b) -> Integer.compare(a.round(), b.round()));
                return results;
            } catch (Exception e) {
                throw new RuntimeException("Redis checkpoint list failed for thread " + threadId, e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> fork(String threadId, int round, String newThreadId) {
        return loadAt(threadId, round).thenCompose(cp -> {
            if (cp == null) return CompletableFuture.completedFuture(null);
            CheckpointState state = new CheckpointState(cp.messages(), cp.round(), cp.waitingForHitl().map(p ->
                new CheckpointState.HitlPause(p.resumeKey(), p.requests(), p.toolCalls(), p.assistant())));
            return save(newThreadId, state);
        });
    }

    @Override
    public CompletableFuture<Void> delete(String threadId) {
        return CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.del(key(threadId));
            } catch (Exception e) {
                throw new RuntimeException("Redis checkpoint delete failed for thread " + threadId, e);
            }
        });
    }

    /**
     * Health check returning true if Redis is reachable.
     */
    public boolean isHealthy() {
        try (Jedis jedis = jedisPool.getResource()) {
            return "PONG".equals(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }
}
