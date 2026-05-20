package com.chorus.engine.memory.checkpoint;

import com.chorus.engine.core.checkpoint.AgentState;
import com.chorus.engine.core.checkpoint.Checkpointer;
import com.chorus.engine.core.result.Result;
import org.jspecify.annotations.NonNull;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.resps.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Redis-based checkpointer using Jedis.
 * <p>
 * Stores each checkpoint as a Redis hash with {@code state_json} and {@code created_at} fields.
 * Maintains a sorted set per run for ordering by sequence number.
 * <p>
 * All keys have TTL for automatic cleanup.
 */
public final class RedisCheckpointer implements Checkpointer {

    private final JedisPool jedisPool;
    private final String keyPrefix;
    private final int ttlSeconds;
    private final CheckpointSerializer serializer;

    public RedisCheckpointer(@NonNull JedisPool jedisPool, @NonNull String keyPrefix, int ttlSeconds) {
        this(jedisPool, keyPrefix, ttlSeconds, new JsonCheckpointSerializer());
    }

    public RedisCheckpointer(@NonNull JedisPool jedisPool, @NonNull String keyPrefix, int ttlSeconds, @NonNull CheckpointSerializer serializer) {
        this.jedisPool = jedisPool;
        this.keyPrefix = keyPrefix;
        this.ttlSeconds = ttlSeconds;
        this.serializer = serializer;
    }

    private @NonNull String checkpointKey(@NonNull String runId, long sequenceNumber) {
        return keyPrefix + ":checkpoint:" + runId + ":" + sequenceNumber;
    }

    private @NonNull String threadKey(@NonNull String runId) {
        return keyPrefix + ":thread:" + runId;
    }

    @Override
    public @NonNull Result<Void, Checkpointer.CheckpointError> save(@NonNull String runId, long sequenceNumber, @NonNull AgentState state) {
        String ckKey = checkpointKey(runId, sequenceNumber);
        String tKey = threadKey(runId);
        String json = serializer.serialize(state);
        long now = System.currentTimeMillis();

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hset(ckKey, Map.of("state_json", json, "created_at", String.valueOf(now)));
            jedis.zadd(tKey, sequenceNumber, String.valueOf(sequenceNumber));
            jedis.expire(ckKey, ttlSeconds);
            jedis.expire(tKey, ttlSeconds);
            return new Result.Ok<>(null);
        } catch (Exception e) {
            return Result.err(Checkpointer.CheckpointError.of("SAVE_FAILED", "Failed to save checkpoint to Redis", e));
        }
    }

    @Override
    public @NonNull Result<AgentState, Checkpointer.CheckpointError> loadLatest(@NonNull String runId) {
        String tKey = threadKey(runId);

        try (Jedis jedis = jedisPool.getResource()) {
            List<String> members = jedis.zrevrange(tKey, 0, 0);
            if (members.isEmpty()) {
                return Result.err(Checkpointer.CheckpointError.of("NOT_FOUND", "No checkpoints for run: " + runId));
            }
            String seqStr = members.get(0);
            String ckKey = checkpointKey(runId, Long.parseLong(seqStr));
            Map<String, String> fields = jedis.hgetAll(ckKey);
            if (fields.isEmpty()) {
                return Result.err(Checkpointer.CheckpointError.of("NOT_FOUND", "No checkpoints for run: " + runId));
            }
            String json = fields.get("state_json");
            if (json == null) {
                return Result.err(Checkpointer.CheckpointError.of("NOT_FOUND", "No checkpoints for run: " + runId));
            }
            return Result.ok(serializer.deserialize(json));
        } catch (Exception e) {
            return Result.err(Checkpointer.CheckpointError.of("LOAD_FAILED", "Failed to load latest checkpoint from Redis", e));
        }
    }

    @Override
    public @NonNull Result<AgentState, Checkpointer.CheckpointError> load(@NonNull String runId, long sequenceNumber) {
        String ckKey = checkpointKey(runId, sequenceNumber);

        try (Jedis jedis = jedisPool.getResource()) {
            Map<String, String> fields = jedis.hgetAll(ckKey);
            if (fields.isEmpty()) {
                return Result.err(Checkpointer.CheckpointError.of("NOT_FOUND", "No checkpoint at sequence " + sequenceNumber));
            }
            String json = fields.get("state_json");
            if (json == null) {
                return Result.err(Checkpointer.CheckpointError.of("NOT_FOUND", "No checkpoint at sequence " + sequenceNumber));
            }
            return Result.ok(serializer.deserialize(json));
        } catch (Exception e) {
            return Result.err(Checkpointer.CheckpointError.of("LOAD_FAILED", "Failed to load checkpoint from Redis", e));
        }
    }

    @Override
    public @NonNull Result<List<CheckpointRef>, Checkpointer.CheckpointError> list(@NonNull String runId) {
        String tKey = threadKey(runId);

        try (Jedis jedis = jedisPool.getResource()) {
            List<Tuple> tuples = jedis.zrevrangeWithScores(tKey, 0, -1);
            List<CheckpointRef> refs = new ArrayList<>();
            for (Tuple tuple : tuples) {
                long seq = (long) tuple.getScore();
                String ckKey = checkpointKey(runId, seq);
                String createdAtStr = jedis.hget(ckKey, "created_at");
                long millis = createdAtStr != null ? Long.parseLong(createdAtStr) : System.currentTimeMillis();
                refs.add(new CheckpointRef(runId, seq, millis));
            }
            return Result.ok(refs);
        } catch (Exception e) {
            return Result.err(Checkpointer.CheckpointError.of("LIST_FAILED", "Failed to list checkpoints from Redis", e));
        }
    }

    @Override
    public @NonNull Result<Void, Checkpointer.CheckpointError> prune(@NonNull String runId, long keepAfterSequence) {
        String tKey = threadKey(runId);

        try (Jedis jedis = jedisPool.getResource()) {
            List<Tuple> tuples = jedis.zrangeWithScores(tKey, 0, -1);
            for (Tuple tuple : tuples) {
                long seq = (long) tuple.getScore();
                if (seq < keepAfterSequence) {
                    jedis.del(checkpointKey(runId, seq));
                    jedis.zrem(tKey, String.valueOf(seq));
                }
            }
            return new Result.Ok<>(null);
        } catch (Exception e) {
            return Result.err(Checkpointer.CheckpointError.of("PRUNE_FAILED", "Failed to prune checkpoints from Redis", e));
        }
    }
}
