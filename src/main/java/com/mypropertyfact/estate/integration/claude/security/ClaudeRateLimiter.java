package com.mypropertyfact.estate.integration.claude.security;

import com.mypropertyfact.estate.integration.claude.config.ClaudeIntegrationProperties;
import com.mypropertyfact.estate.integration.claude.exception.ClaudeIntegrationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sliding one-minute window per account for the upload/validate endpoints (per instance). */
@Component
public class ClaudeRateLimiter {

    private final ClaudeIntegrationProperties props;
    private final Map<String, Deque<Long>> calls = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ClaudeRateLimiter(ClaudeIntegrationProperties props) {
        this(props, System::currentTimeMillis);
    }

    ClaudeRateLimiter(ClaudeIntegrationProperties props, java.util.function.LongSupplier clock) {
        this.props = props;
        this.clock = clock;
    }

    public void check(String accountKey) {
        int limit = props.getRateLimitPerMinute();
        if (limit <= 0) {
            return;
        }
        long now = clock.getAsLong();
        Deque<Long> q = calls.computeIfAbsent(accountKey, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= 60_000) {
                q.pollFirst();
            }
            if (q.size() >= limit) {
                throw new ClaudeIntegrationException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "Too many requests; the limit is " + limit + " per minute");
            }
            q.addLast(now);
        }
    }
}
