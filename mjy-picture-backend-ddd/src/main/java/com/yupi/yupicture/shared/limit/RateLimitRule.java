package com.yupi.yupicture.shared.limit;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RateLimitRule {

    private final int capacity;

    private final int refillRatePerSecond;
}