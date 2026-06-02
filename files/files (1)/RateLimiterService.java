package com.chatapp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token-bucket rate limiter — one bucket per user session.
 *
 * Config: 10 tokens, refill 2/sec.
 * Each message costs 1 token.
 * Typing events cost 0 (checked separately, low cost).
 *
 * Thread-safety: all fields are atomic / volatile.
 * No locks — CAS operations only.
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    private static final int BUCKET_CAPACITY  = 10;
    private static final int REFILL_PER_SEC   = 2;
    private static final int CLEANUP_INTERVAL = 60_000; // 1 minute

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * Try to consume one token for the given session.
     * Returns true if allowed, false if rate-limited.
     */
    public boolean tryConsume(String sessionId) {
        TokenBucket bucket = buckets.computeIfAbsent(sessionId, k -> new TokenBucket());
        return bucket.tryConsume();
    }

    /**
     * Remove bucket when user disconnects.
     */
    public void removeBucket(String sessionId) {
        buckets.remove(sessionId);
    }

    /**
     * Periodic cleanup of stale buckets (users who disconnected without explicit removal).
     */
    @Scheduled(fixedDelay = CLEANUP_INTERVAL)
    public void cleanupStaleBuckets() {
        long now = System.currentTimeMillis();
        int before = buckets.size();
        buckets.entrySet().removeIf(e -> now - e.getValue().lastAccess.get() > 120_000);
        int removed = before - buckets.size();
        if (removed > 0) {
            log.debug("RateLimiter cleanup: removed {} stale buckets", removed);
        }
    }

    public int activeBuckets() { return buckets.size(); }

    // ── Inner: Token Bucket ────────────────────────────────────────────────────

    private static class TokenBucket {
        private final AtomicInteger tokens    = new AtomicInteger(BUCKET_CAPACITY);
        private final AtomicLong    lastRefill = new AtomicLong(System.currentTimeMillis());
        final AtomicLong            lastAccess = new AtomicLong(System.currentTimeMillis());

        boolean tryConsume() {
            refill();
            lastAccess.set(System.currentTimeMillis());
            // CAS loop — no lock needed
            while (true) {
                int current = tokens.get();
                if (current <= 0) return false;
                if (tokens.compareAndSet(current, current - 1)) return true;
            }
        }

        private void refill() {
            long now  = System.currentTimeMillis();
            long last = lastRefill.get();
            long elapsed = now - last;
            if (elapsed < 500) return; // refill at most every 500ms

            int toAdd = (int) (elapsed / 1000L * REFILL_PER_SEC);
            if (toAdd <= 0) return;

            if (lastRefill.compareAndSet(last, now)) {
                tokens.updateAndGet(t -> Math.min(BUCKET_CAPACITY, t + toAdd));
            }
        }
    }
}
