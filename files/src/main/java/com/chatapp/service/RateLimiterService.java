package com.chatapp.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RateLimiterService {

    private final int aiLimitPerMinute;
    private final int chatLimitPerMinute;
    private final ConcurrentHashMap<String, SlidingWindow> aiWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SlidingWindow> chatWindows = new ConcurrentHashMap<>();

    public RateLimiterService(
        @Value("${nexus.ai.rate-limit-per-minute:20}") int aiLimitPerMinute,
        @Value("${nexus.chat.rate-limit-per-minute:60}") int chatLimitPerMinute
    ) {
        this.aiLimitPerMinute = aiLimitPerMinute;
        this.chatLimitPerMinute = chatLimitPerMinute;
    }

    public boolean tryAi(String key) {
        return aiWindows.computeIfAbsent(key, k -> new SlidingWindow()).tryAcquire(aiLimitPerMinute);
    }

    public boolean tryChat(String key) {
        return chatWindows.computeIfAbsent(key, k -> new SlidingWindow()).tryAcquire(chatLimitPerMinute);
    }

    public void remove(String key) {
        aiWindows.remove(key);
        chatWindows.remove(key);
    }

    private static final class SlidingWindow {
        private final AtomicInteger count = new AtomicInteger(0);
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

        boolean tryAcquire(int limit) {
            long now = System.currentTimeMillis();
            long start = windowStart.get();
            if (now - start > 60_000) {
                if (windowStart.compareAndSet(start, now)) {
                    count.set(0);
                }
            }
            return count.incrementAndGet() <= limit;
        }
    }
}
