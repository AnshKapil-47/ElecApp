package com.elecsarthi.security.service;

import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class RateLimiterService {

    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    private record WindowCounter(long windowMinute, AtomicInteger count) {}

    public void checkRateLimit(String action, String identifier, int maxRequestsPerMinute) {
        if (identifier == null || identifier.isBlank()) {
            return;
        }

        long currentMinute = System.currentTimeMillis() / 60000;
        String bucketKey = action + ":" + identifier.trim().toLowerCase() + ":" + currentMinute;

        WindowCounter counter = counters.compute(bucketKey, (k, existing) -> {
            if (existing == null) {
                return new WindowCounter(currentMinute, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });

        if (counter.count.get() > maxRequestsPerMinute) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Too many requests. Rate limit exceeded for " + action + ". Please try again later.", HttpStatus.TOO_MANY_REQUESTS);
        }

        // Clean up stale buckets periodically
        if (counters.size() > 5000) {
            counters.entrySet().removeIf(e -> e.getValue().windowMinute < currentMinute - 2);
        }
    }
}
