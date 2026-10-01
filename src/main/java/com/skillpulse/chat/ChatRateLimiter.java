package com.skillpulse.chat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Protects the LLM (and its API quota) from one learner or one script:
 *  - a sliding one-minute window per learner (default 12 messages per minute);
 *  - at most one reply being written per learner at a time;
 *  - a cap on replies being written across the whole server.
 *
 * State is in memory, which is right for a single backend instance. If the backend is ever run as several instances
 * behind a load balancer, move these counters to a shared store such as Redis.
 */
@Component
public class ChatRateLimiter {
    private static final long WINDOW_MS = 60000L;
    private static final long PRUNE_EVERY_MS = 120000L;

    private final int maxPerMinute;
    private final int maxConcurrent;
    private final LongSupplier clock;
    private final Map<Long, Deque<Long>> hits = new ConcurrentHashMap<Long, Deque<Long>>();
    private final Set<Long> streaming = ConcurrentHashMap.newKeySet();
    private final AtomicInteger active = new AtomicInteger();
    private volatile long lastPrune;

    @Autowired
    public ChatRateLimiter(@Value("${skillpulse.chat.max-messages-per-minute:12}") int maxPerMinute,
                           @Value("${skillpulse.chat.max-concurrent-streams:20}") int maxConcurrent) {
        this(maxPerMinute, maxConcurrent, System::currentTimeMillis);
    }

    ChatRateLimiter(int maxPerMinute, int maxConcurrent, LongSupplier clock) {
        this.maxPerMinute = Math.max(1, maxPerMinute);
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.clock = clock;
        this.lastPrune = clock.getAsLong();
    }

    /** Counts one message for this learner, or throws a 429 saying how long to wait. */
    public void checkMessage(Long userId) {
        long now = clock.getAsLong();
        prune(now);
        Deque<Long> window = hits.computeIfAbsent(userId, k -> new ArrayDeque<Long>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MS) window.pollFirst();
            if (window.size() >= maxPerMinute) {
                long waitMs = WINDOW_MS - (now - window.peekFirst());
                long seconds = Math.max(1L, (waitMs + 999L) / 1000L);
                throw new ChatLimitException(429, "You are sending messages too quickly. Please wait "
                        + seconds + " second" + (seconds == 1 ? "" : "s") + " and try again.", seconds);
            }
            window.addLast(now);
        }
    }

    /** Takes this learner's single streaming slot (and one of the server-wide slots), or throws. */
    public void acquireStream(Long userId) {
        if (!streaming.add(userId)) {
            throw new ChatLimitException(429, "Your previous reply is still being written. "
                    + "Wait for it to finish or press Stop.", 3);
        }
        if (active.incrementAndGet() > maxConcurrent) {
            active.decrementAndGet();
            streaming.remove(userId);
            throw new ChatLimitException(503, "The study coach is busy right now. Please try again in a moment.", 5);
        }
    }

    /** Must be called exactly once for every successful acquireStream. */
    public void releaseStream(Long userId) {
        if (streaming.remove(userId)) active.decrementAndGet();
    }

    int activeStreams() { return active.get(); }

    private void prune(long now) {
        if (now - lastPrune < PRUNE_EVERY_MS) return;
        lastPrune = now;
        Iterator<Map.Entry<Long, Deque<Long>>> it = hits.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Long> window = it.next().getValue();
            synchronized (window) {
                if (window.isEmpty() || now - window.peekLast() >= WINDOW_MS) it.remove();
            }
        }
    }
}
