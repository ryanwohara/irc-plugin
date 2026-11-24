package com.irc;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** When the client announces a lost link - the signal auto reconnect hangs off. */
public class SimpleIrcClientLinkLostTest {
    private static final SimpleIrcClient.IrcEvent.Type LINK_LOST = SimpleIrcClient.IrcEvent.Type.LINK_LOST;
    private static final SimpleIrcClient.IrcEvent.Type DISCONNECT = SimpleIrcClient.IrcEvent.Type.DISCONNECT;

    private static SimpleIrcClient client(int port, List<SimpleIrcClient.IrcEvent.Type> events, CountDownLatch done) {
        SimpleIrcClient client = new SimpleIrcClient()
                .server("127.0.0.1", port, false)
                .credentials("me", "runelite", "me");
        client.addEventListener(event -> {
            events.add(event.getType());
            if (event.getType() == LINK_LOST || event.getType() == DISCONNECT) done.countDown();
        });
        return client;
    }

    @Test
    public void serverClosingTheSocketIsALostLink() throws Exception {
        List<SimpleIrcClient.IrcEvent.Type> events = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);
        try (ServerSocket server = new ServerSocket(0)) {
            SimpleIrcClient client = client(server.getLocalPort(), events, done);
            client.connect();
            try (Socket accepted = server.accept()) {
                new BufferedReader(new InputStreamReader(accepted.getInputStream(), StandardCharsets.UTF_8)).readLine();
            }
            assertTrue("expected DISCONNECT and LINK_LOST, got " + events, done.await(5, TimeUnit.SECONDS));
        }
        assertTrue(events.indexOf(DISCONNECT) < events.indexOf(LINK_LOST));
    }

    @Test
    public void refusedConnectionIsALostLink() throws Exception {
        int port;
        try (ServerSocket server = new ServerSocket(0)) {
            port = server.getLocalPort();
        }
        List<SimpleIrcClient.IrcEvent.Type> events = new CopyOnWriteArrayList<>();
        CountDownLatch lost = new CountDownLatch(1);
        SimpleIrcClient client = client(port, events, new CountDownLatch(0));
        client.addEventListener(event -> {
            if (event.getType() == LINK_LOST) lost.countDown();
        });
        client.connect();
        assertTrue("expected LINK_LOST, got " + events, lost.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void quittingIsNotALostLink() throws Exception {
        List<SimpleIrcClient.IrcEvent.Type> events = new CopyOnWriteArrayList<>();
        CountDownLatch disconnected = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0)) {
            SimpleIrcClient client = new SimpleIrcClient()
                    .server("127.0.0.1", server.getLocalPort(), false)
                    .credentials("me", "runelite", "me");
            CountDownLatch connected = new CountDownLatch(1);
            client.addEventListener(event -> {
                events.add(event.getType());
                if (event.getType() == SimpleIrcClient.IrcEvent.Type.CONNECT) connected.countDown();
                if (event.getType() == DISCONNECT) disconnected.countDown();
            });
            client.connect();
            try (Socket accepted = server.accept()) {
                assertTrue(connected.await(5, TimeUnit.SECONDS));
                client.quit("bye");
                assertTrue(disconnected.await(5, TimeUnit.SECONDS));
                // Give the reader thread time to wind down and (wrongly) report a loss.
                Thread.sleep(300);
            }
        }
        assertFalse("a deliberate quit must not trigger a reconnect: " + events, events.contains(LINK_LOST));
    }
}
