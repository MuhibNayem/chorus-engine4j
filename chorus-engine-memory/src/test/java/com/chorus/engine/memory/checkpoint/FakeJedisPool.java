package com.chorus.engine.memory.checkpoint;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

class FakeJedisPool extends JedisPool {

    private final FakeJedis fakeJedis = new FakeJedis();

    FakeJedisPool() {
        super("localhost", 6379);
    }

    @Override
    public Jedis getResource() {
        return fakeJedis;
    }
}
