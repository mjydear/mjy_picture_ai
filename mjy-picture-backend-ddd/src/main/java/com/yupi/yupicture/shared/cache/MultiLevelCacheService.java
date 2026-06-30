package com.yupi.yupicture.shared.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * L1 Caffeine + L2 Redis 多级缓存。
 */
@Slf4j
@Service
public class MultiLevelCacheService {

    private static final Duration LOCAL_CACHE_TTL = Duration.ofMinutes(5);

    private final Cache<String, String> localCache = Caffeine.newBuilder()
            .initialCapacity(1024)
            .maximumSize(10_000L)
            .expireAfterWrite(LOCAL_CACHE_TTL)
            .build();

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public String get(String key) {
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

    private long ttlWithJitterSeconds(Duration ttl) {
        long baseSeconds = Math.max(1, ttl.getSeconds());
        long jitterSeconds = Math.max(1, baseSeconds / 5);
        return baseSeconds + ThreadLocalRandom.current().nextLong(jitterSeconds + 1);
    }
}