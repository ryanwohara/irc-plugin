package com.irc;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AutoReconnectorTest {
    private static final class Scheduled {
        final Runnable task;
        final long delayMs;
        final CompletableFuture<Void> future = new CompletableFuture<>();

        Scheduled(Runnable task, long delayMs) {
            this.task = task;
            this.delayMs = delayMs;
        }
    }

    private final List<Scheduled> scheduled = new ArrayList<>();
    private final List<String> reconnected = new ArrayList<>();
    private final List<String> notices = new ArrayList<>();
    private long now;
    private boolean enabled;
    private AutoReconnector reconnector;

    @Before
    public void setUp() {
        now = 1_000_000;
        enabled = true;
        reconnector = new AutoReconnector(
                (task, delayMs) -> {
                    Scheduled s = new Scheduled(task, delayMs);
                    scheduled.add(s);
                    return (Future<?>) s.future;
                },
                () -> now,
                () -> enabled,
                reconnected::add,
                (id, text) -> notices.add(id + ": " + text));
    }

    private Scheduled last() {
        return scheduled.get(scheduled.size() - 1);
    }

    @Test
    public void delayDoublesFromFiveSecondsUpToFiveMinutes() {
        assertEquals(5_000, AutoReconnector.delayFor(0));
        assertEquals(10_000, AutoReconnector.delayFor(1));
        assertEquals(20_000, AutoReconnector.delayFor(2));
        assertEquals(160_000, AutoReconnector.delayFor(5));
        assertEquals(300_000, AutoReconnector.delayFor(6));
        assertEquals(300_000, AutoReconnector.delayFor(100));
    }

    @Test
    public void eachFailedAttemptWaitsLonger() {
        reconnector.linkLost("net", () -> true);
        reconnector.linkLost("net", () -> true);
        reconnector.linkLost("net", () -> true);

        assertEquals(5_000, scheduled.get(0).delayMs);
        assertEquals(10_000, scheduled.get(1).delayMs);
        assertEquals(20_000, scheduled.get(2).delayMs);
        assertEquals("net: Reconnecting in 20 seconds (attempt 3)", notices.get(2));
    }

    @Test
    public void firingReconnectsTheNetwork() {
        reconnector.linkLost("net", () -> true);
        last().task.run();
        assertEquals(1, reconnected.size());
        assertEquals("net", reconnected.get(0));
    }

    @Test
    public void aStableConnectionRestartsTheBackoff() {
        reconnector.linkLost("net", () -> true);
        reconnector.linkLost("net", () -> true);
        reconnector.registered("net");
        now += AutoReconnector.STABLE_MS;

        reconnector.linkLost("net", () -> true);

        assertEquals(5_000, last().delayMs);
    }

    @Test
    public void aConnectionDroppedStraightAfterRegisteringKeepsBackingOff() {
        reconnector.linkLost("net", () -> true);
        reconnector.registered("net");
        now += 2_000;

        reconnector.linkLost("net", () -> true);

        assertEquals(10_000, last().delayMs);
    }

    @Test
    public void networksBackOffIndependently() {
        reconnector.linkLost("a", () -> true);
        reconnector.linkLost("a", () -> true);
        reconnector.linkLost("b", () -> true);

        assertEquals(5_000, last().delayMs);
    }

    @Test
    public void nothingIsScheduledWhenTurnedOff() {
        enabled = false;
        reconnector.linkLost("net", () -> true);
        assertTrue(scheduled.isEmpty());
        assertTrue(notices.isEmpty());
    }

    @Test
    public void turningItOffBeforeTheTimerFiresStopsTheReconnect() {
        reconnector.linkLost("net", () -> true);
        enabled = false;
        last().task.run();
        assertTrue(reconnected.isEmpty());
    }

    @Test
    public void aConnectionReplacedMeanwhileIsNotReconnected() {
        reconnector.linkLost("net", () -> false);
        last().task.run();
        assertTrue(reconnected.isEmpty());
    }

    @Test
    public void cancelDropsTheScheduledAttempt() {
        reconnector.linkLost("net", () -> true);
        reconnector.cancel("net");
        assertTrue(last().future.isCancelled());
    }

    @Test
    public void shutdownCancelsEverything() {
        reconnector.linkLost("a", () -> true);
        reconnector.linkLost("b", () -> true);
        reconnector.shutdown();
        assertTrue(scheduled.get(0).future.isCancelled());
        assertTrue(scheduled.get(1).future.isCancelled());
    }

    @Test
    public void longDelaysAreDescribedInMinutes() {
        for (int i = 0; i < 7; i++) reconnector.linkLost("net", () -> true);
        assertEquals("net: Reconnecting in 2 minutes 40 seconds (attempt 6)", notices.get(5));
        assertEquals("net: Reconnecting in 5 minutes (attempt 7)", notices.get(6));
    }
}
