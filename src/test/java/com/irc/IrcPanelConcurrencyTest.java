package com.irc;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNull;

/**
 * Regression test for the data race that produced:
 *
 *   java.lang.ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 1
 *       at java.util.LinkedHashMap.keysToArray
 *       at com.irc.IrcPanel.getChannelNames(IrcPanel.java:67)
 *
 * getChannelNames() snapshots channelPanes.keySet() on the IRC reader thread while
 * the EDT concurrently adds/removes channels. With a non-thread-safe map the snapshot
 * (new ArrayList<>(keySet())) reads size(), allocates an array of that size, then
 * overflows it when another thread inserts mid-copy.
 */
public class IrcPanelConcurrencyTest {

    @Test
    public void getChannelNamesIsSafeUnderConcurrentMutation() throws Exception {
        IrcPanel panel = new IrcPanel();

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean stop = new AtomicBoolean(false);

        // Writer: churns the map the way addChannel()/removeChannel() do.
        Thread writer = new Thread(() -> {
            try {
                for (int round = 0; round < 100_000 && !stop.get(); round++) {
                    for (int i = 0; i < 16; i++) {
                        panel.getChannelPanes().put("#chan" + i, null);
                    }
                    for (int i = 0; i < 16; i++) {
                        panel.getChannelPanes().remove("#chan" + i);
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "writer");

        // Reader: snapshots channel names the way processMessage() does on the IRC thread.
        Thread reader = new Thread(() -> {
            try {
                for (int round = 0; round < 100_000 && !stop.get(); round++) {
                    panel.getChannelNames();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "reader");

        writer.start();
        reader.start();
        writer.join(30_000);
        reader.join(30_000);
        stop.set(true);
        writer.join();
        reader.join();

        assertNull("getChannelNames() must not throw under concurrent mutation, but got: "
                + failure.get(), failure.get());
    }
}
