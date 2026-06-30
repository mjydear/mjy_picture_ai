package com.yupi.yupicture.shared.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.UUID;

/**
 * L1 Caffeine + L2 Redis 多级缓存。
 */
@Slf4j
@Service
public class MultiLevelCacheService {

    private static final Duration LOCAL_CACHE_TTL = Duration.ofMinutes(5);

    private static final Duration DEFAULT_NULL_VALUE_TTL = Duration.ofSeconds(30);

    private static final Duration DEFAULT_LOCK_TTL = Duration.ofSeconds(30);

    private static final String NULL_CACHE_VALUE = "__NULL_CACHE_VALUE__";

    private static final String LOCK_KEY_PREFIX = "lock:";

        private static final int DEFAULT_RETRY_TIMES = 120;

        private static final long DEFAULT_RETRY_INTERVAL_MILLIS = 100L;

        private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
        );

    private final Cache<String, String> localCache = Caffeine.newBuilder()
            .initialCapacity(1024)
            .maximumSize(10_000L)
            .expireAfterWrite(LOCAL_CACHE_TTL)
            .build();

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public String get(String key) {
        String cachedValue = getRawValue(key);
        if (NULL_CACHE_VALUE.equals(cachedValue)) {
            return null;
        }
        return cachedValue;
    }

    private String getRawValue(String key) {
        String cachedValue = localCache.getIfPresent(key);
        if (cachedValue != null) {
            return cachedValue;
        }
        try {
            cachedValue = stringRedisTemplate.opsForValue().get(key);
            if (cachedValue != null) {
                localCache.put(key, cachedValue);
            }
            return cachedValue;
        } catch (Exception e) {
            log.warn("read redis cache failed, key={}", key, e);
            return null;
        }
    }

    public void put(String key, String value, Duration ttl) {
        if (value == null) {
            return;
        }
        localCache.put(key, value);
        try {
            stringRedisTemplate.opsForValue().set(key, value, ttlWithJitterSeconds(ttl), TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("write redis cache failed, key={}", key, e);
        }
    }

    public String getOrLoad(String key, Duration ttl, Supplier<String> loader) {
        String cachedValue = get(key);
        if (cachedValue != null) {
            return cachedValue;
        }
        String loadedValue = loader.get();
        put(key, loadedValue, ttl);
        return loadedValue;
    }

    public String getOrLoadWithMutex(String key, Duration ttl, Supplier<String> loader) {
        return getOrLoadWithMutex(key, ttl, DEFAULT_NULL_VALUE_TTL, loader);
    }

    public String getOrLoadWithMutex(String key, Duration ttl, Duration nullValueTtl, Supplier<String> loader) {
        String cachedValue = getRawValue(key);
        if (cachedValue != null) {
            return NULL_CACHE_VALUE.equals(cachedValue) ? null : cachedValue;
        }

        String lockKey = LOCK_KEY_PREFIX + key;
        String lockToken = UUID.randomUUID().toString();
        if (tryLock(lockKey, lockToken)) {
            try {
                cachedValue = getRawValue(key);
                if (cachedValue != null) {
                    return NULL_CACHE_VALUE.equals(cachedValue) ? null : cachedValue;
                }
                return loadAndPut(key, ttl, nullValueTtl, loader);
            } finally {
                unlock(lockKey, lockToken);
            }
        }

        for (int i = 0; i < DEFAULT_RETRY_TIMES; i++) {
            sleepQuietly();
            cachedValue = getRawValue(key);
            if (cachedValue != null) {
                return NULL_CACHE_VALUE.equals(cachedValue) ? null : cachedValue;
            }
        }
        return loadAndPut(key, ttl, nullValueTtl, loader);
    }

    private String loadAndPut(String key, Duration ttl, Duration nullValueTtl, Supplier<String> loader) {
        String loadedValue = loader.get();
        if (loadedValue == null) {
            put(key, NULL_CACHE_VALUE, nullValueTtl);
            return null;
        }
        put(key, loadedValue, ttl);
        return loadedValue;
    }

    private boolean tryLock(String lockKey, String lockToken) {
        try {
            Boolean success = stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, lockToken, DEFAULT_LOCK_TTL.getSeconds(), TimeUnit.SECONDS);
            return Boolean.TRUE.equals(success);
        } catch (Exception e) {
            log.warn("try redis cache lock failed, key={}", lockKey, e);
            return true;
        }
    }

    private void unlock(String lockKey, String lockToken) {
        try {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), lockToken);
        } catch (Exception e) {
            log.warn("release redis cache lock failed, key={}", lockKey, e);
        }
    }

    private void sleepQuietly() {
        try {
            TimeUnit.MILLISECONDS.sleep(DEFAULT_RETRY_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long ttlWithJitterSeconds(Duration ttl) {
        long baseSeconds = Math.max(1, ttl.getSeconds());
        long jitterSeconds = Math.max(1, baseSeconds / 5);
        return baseSeconds + ThreadLocalRandom.current().nextLong(jitterSeconds + 1);
    }
}