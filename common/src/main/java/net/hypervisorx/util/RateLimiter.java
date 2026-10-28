package net.hypervisorx.util;

/** token bucket for one player's packets. thread safe because NeoForge hands us input on network threads. */
public final class RateLimiter {
    private final double perSecond;
    private final double burst;
    private double tokens;
    private long last = Long.MIN_VALUE;

    public RateLimiter(double perSecond, double burst) {
        this.perSecond = perSecond;
        this.burst = burst;
        this.tokens = burst;
    }

    public synchronized boolean tryAcquire() {
        return tryAcquire(System.nanoTime());
    }

    /** same thing but you pass the clock in, for tests. */
    public synchronized boolean tryAcquire(long nowNanos) {
        if (last != Long.MIN_VALUE) tokens = Math.min(burst, tokens + Math.max(0, nowNanos - last) / 1e9 * perSecond);
        last = nowNanos;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }
}
