package com.yupi.yupicture.shared.limit;

import com.yupi.yupicture.infrastructure.exception.BusinessException;
import com.yupi.yupicture.infrastructure.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String RATE_LIMIT_KEY_PREFIX = "yupicture:rate_limit:";

    private static final Map<String, RateLimitRule> RULE_MAP = new HashMap<>();

    static {
        RULE_MAP.put("/picture/rank/hot", new RateLimitRule(50, 50));
        RULE_MAP.put("/picture/list/page/vo", new RateLimitRule(3000, 3000));
        RULE_MAP.put("/picture/get/vo", new RateLimitRule(500, 500));
    }

    @Resource
    private RedisRateLimiter redisRateLimiter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = resolvePath(request);
        RateLimitRule rule = RULE_MAP.get(path);
        if (rule == null) {
            return true;
        }
        String key = RATE_LIMIT_KEY_PREFIX + path + ':' + getClientIp(request);
        boolean allowed = redisRateLimiter.tryAcquire(key, rule.getCapacity(), rule.getRefillRatePerSecond());
        if (!allowed) {
            throw new BusinessException(ErrorCode.RATE_LIMIT_ERROR, "请求过于频繁，请稍后再试");
        }
        return true;
    }

    private String getClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && forwardedFor.length() > 0) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String resolvePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        int paramIdx = uri.indexOf('?');
        if (paramIdx >= 0) {
            uri = uri.substring(0, paramIdx);
        }
        return uri;
    }
}