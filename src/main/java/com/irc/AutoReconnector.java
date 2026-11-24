package com.irc;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Reconnects networks whose link was lost, waiting twice as long after each failed attempt:
 * 5s, 10s, 20s ... up to 5 minutes. A connection that stays registered for a minute counts as
 * recovered and the next loss starts again from 5s; one that registers and is dropped straight
 * away (a K-line, a throttle) keeps backing off rather than hammering the server.
 *
 * Synchronized: losses arrive on each network's reader thread, firings on the scheduler's.
 */
final class AutoReconnector {
    static final long BASE_DELAY_MS = 5_000;
    static final long MAX_DELAY_MS = 300_000;
    /** How long a connection must stay up before a later loss restarts the backoff. */
    static final long STABLE_MS = 60_000;

    /** Runs {@code task} once after {@code delayMs}; the plugin supplies RuneLite's executor. */
    interface Scheduler {
        Future<?> schedule(Runnable task, long delayMs);
    }

    private final Scheduler scheduler;
    private final LongSupplier clock;
    private final BooleanSupplier enabled;
    /** Reconnects the network with this id. */
    private final Consumer<String> reconnect;
    /** Tells the user about a scheduled attempt: (network id, text). */
    private final BiConsumer<String, String> notify;

    /** Attempts made since the network was last stably connected. */
    private final Map<String, Integer> attempts = new HashMap<>();
    private final Map<String, Long> registeredAt = new HashMap<>();
    private final Map<String, Future<?>> pending = new HashMap<>();

    AutoReconnector(Scheduler scheduler, LongSupplier clock, BooleanSupplier enabled,
                    Consumer<String> reconnect, BiConsumer<String, String> notify) {
        this.scheduler = scheduler;
        this.clock = clock;
        this.enabled = enabled;
        this.reconnect = reconnect;
        this.notify = notify;
    }

    /** The wait before attempt {@code attempt} (0-based): doubling from the base, capped. */
    static long delayFor(int attempt) {
        if (attempt >= 30) return MAX_DELAY_MS;
        return Math.min(BASE_DELAY_MS << attempt, MAX_DELAY_MS);
    }

    synchronized void registered(String networkId) {
        registeredAt.put(networkId, clock.getAsLong());
    }

    /**
     * Schedules a reconnect of {@code networkId}. {@code stillCurrent} is asked again when the
     * timer fires: if the user reconnected, disconnected or removed the network meanwhile, the
     * connection that was lost is no longer the one to replace, and nothing happens.
     */
    synchronized void linkLost(String networkId, BooleanSupplier stillCurrent) {
        if (!enabled.getAsBoolean()) return;
        Long upSince = registeredAt.remove(networkId);
        if (upSince != null && clock.getAsLong() - upSince >= STABLE_MS) attempts.remove(networkId);

        int attempt = attempts.getOrDefault(networkId, 0);
        attempts.put(networkId, attempt + 1);
        long delay = delayFor(attempt);

        cancel(networkId);
        notify.accept(networkId, "Reconnecting in " + describe(delay) + " (attempt " + (attempt + 1) + ")");
        pending.put(networkId, scheduler.schedule(() -> fire(networkId, stillCurrent), delay));
    }

    private void fire(String networkId, BooleanSupplier stillCurrent) {
        synchronized (this) {
            pending.remove(networkId);
        }
        // Outside the lock: reconnecting takes NetworkManager's lock, and a reader thread
        // holding that while reporting a loss here must not deadlock against us.
        if (enabled.getAsBoolean() && stillCurrent.getAsBoolean()) reconnect.accept(networkId);
    }

    /** Drops any scheduled attempt for {@code networkId}; its backoff is kept. */
    synchronized void cancel(String networkId) {
        Future<?> scheduled = pending.remove(networkId);
        if (scheduled != null) scheduled.cancel(false);
    }

    synchronized void shutdown() {
        for (Future<?> scheduled : pending.values()) scheduled.cancel(false);
        pending.clear();
        attempts.clear();
        registeredAt.clear();
    }

    private static String describe(long delayMs) {
        long seconds = delayMs / 1000;
        if (seconds < 60) return seconds + (seconds == 1 ? " second" : " seconds");
        long minutes = seconds / 60;
        long rest = seconds % 60;
        String text = minutes + (minutes == 1 ? " minute" : " minutes");
        return rest == 0 ? text : text + " " + rest + " seconds";
    }
}
