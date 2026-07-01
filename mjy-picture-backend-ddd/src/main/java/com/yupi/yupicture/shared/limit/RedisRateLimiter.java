package com.yupi.yupicture.shared.limit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;

@Slf4j
@Service
public class RedisRateLimiter {

    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>(
            "local key = KEYS[1] " +
                    "local now = tonumber(ARGV[1]) " +
                    "local capacity = tonumber(ARGV[2]) " +
                    "local refill_rate = tonumber(ARGV[3]) " +
                    "local requested = tonumber(ARGV[4]) " +
                    "local data = redis.call('hmget', key, 'tokens', 'ts') " +
                    "local tokens = tonumber(data[1]) " +
                    "local last_refreshed = tonumber(data[2]) " +
                    "if tokens == nil then tokens = capacity end " +
                    "if last_refreshed == nil then last_refreshed = now end " +
                    "local delta = now - last_refreshed " +
                    "if delta < 0 then delta = 0 end " +
                    "local filled_tokens = math.min(capacity, tokens + (delta * refill_rate / 1000)) " +
                    "local allowed = 0 " +
                    "if filled_tokens >= requested then " +
                    "  filled_tokens = filled_tokens - requested " +
                    "  allowed = 1 " +
                    "end " +
                    "redis.call('hset', key, 'tokens', tostring(filled_tokens)) " +
                    "redis.call('hset', key, 'ts', tostring(now)) " +
                    "redis.call('expire', key, math.floor(capacity / refill_rate) + 2) " +
                    "return allowed",
            Long.class
    );

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public boolean tryAcquire(String key, int capacity, int refillRatePerSecond) {
        try {
            Long allowed = stringRedisTemplate.execute(TOKEN_BUCKET_SCRIPT, Collections.singletonList(key),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(capacity),
                    String.valueOf(refillRatePerSecond),
                    "1");
            return Long.valueOf(1L).equals(allowed);
        } catch (Exception e) {
            log.warn("rate limiter failed open, key={}", key, e);
            return true;
        }
    }
}