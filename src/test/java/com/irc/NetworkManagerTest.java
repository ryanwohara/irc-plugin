package com.irc;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class NetworkManagerTest {
    private final List<String> calls = new ArrayList<>();
    private final NetworkManager manager = new NetworkManager(new NetworkManager.Connector() {
        @Override public IrcAdapter open(NetworkConfig network) {
            calls.add("open " + network.getId());
            return new IrcAdapter();
        }
        @Override public void close(IrcAdapter adapter, NetworkConfig network, boolean removed, String reason) {
            calls.add("close " + network.getId() + " " + (adapter != null) + " " + removed + " " + reason);
        }
    });

    private static NetworkConfig net(String id) {
        return NetworkConfig.builder().id(id).name(id.toUpperCase()).host(id + ".example").build();
    }

    @Test
    public void startsEnabledNetworksInOrderAndSkipsDisabled() {
        manager.apply(Arrays.asList(net("a"), net("b").toBuilder().enabled(false).build(), net("c")));
        assertEquals(Arrays.asList("open a", "open c"), calls);
        assertTrue(manager.isRunning("a"));
        assertFalse(manager.isRunning("b"));
        assertTrue(manager.isKnown("b"));
        assertEquals(Arrays.asList("a", "b", "c"), ids(manager.configs()));
    }

    @Test
    public void removingANetworkClosesItAndForgetsIt() {
        manager.apply(Arrays.asList(net("a"), net("b")));
        calls.clear();
        manager.apply(Collections.singletonList(net("a")));
        assertEquals(Collections.singletonList("close b true true Network removed"), calls);
        assertFalse(manager.isKnown("b"));
        assertNull(manager.get("b"));
    }

    @Test
    public void removingADisabledNetworkStillClosesItsBuffers() {
        manager.apply(Collections.singletonList(net("a").toBuilder().enabled(false).build()));
        manager.apply(Collections.emptyList());
        assertEquals(Collections.singletonList("close a false true Network removed"), calls);
    }

    @Test
    public void disablingDisconnectsButKeepsTheNetworkKnown() {
        manager.apply(Collections.singletonList(net("a")));
        calls.clear();
        manager.apply(Collections.singletonList(net("a").toBuilder().enabled(false).build()));
        assertEquals(Collections.singletonList("close a true false Disconnecting"), calls);
        assertTrue(manager.isKnown("a"));
        assertFalse(manager.isRunning("a"));
    }

    @Test
    public void connectionChangesReconnectWithAFreshAdapter() {
        manager.apply(Collections.singletonList(net("a")));
        IrcAdapter first = manager.get("a");
        calls.clear();
        manager.apply(Collections.singletonList(net("a").toBuilder().port(6667).build()));
        assertEquals(Arrays.asList("close a true false Reloading, brb", "open a"), calls);
        assertNotSame(first, manager.get("a"));
    }

    @Test
    public void nameOrAutojoinChangesKeepTheConnection() {
        manager.apply(Collections.singletonList(net("a")));
        IrcAdapter first = manager.get("a");
        calls.clear();
        manager.apply(Collections.singletonList(net("a").toBuilder().name("Renamed").autojoin("#x").build()));
        assertTrue(calls.isEmpty());
        assertSame(first, manager.get("a"));
        assertEquals("Renamed", manager.config("a").getName());
    }

    @Test
    public void reconnectRestartsOnlyEnabledKnownNetworks() {
        manager.apply(Arrays.asList(net("a"), net("b").toBuilder().enabled(false).build()));
        calls.clear();
        manager.reconnect("a");
        manager.reconnect("b");
        manager.reconnect("missing");
        assertEquals(Arrays.asList("close a true false Reloading, brb", "open a"), calls);
    }

    @Test
    public void pausedNetworksStayDownUntilConnectedAgain() {
        manager.apply(Collections.singletonList(net("a")));
        manager.setConnected("a", false);
        calls.clear();
        manager.apply(Collections.singletonList(net("a").toBuilder().name("Renamed").build()));
        assertTrue(calls.isEmpty());
        manager.setConnected("a", true);
        assertEquals(Collections.singletonList("open a"), calls);
        assertTrue(manager.isRunning("a"));
    }

    @Test
    public void shutdownClosesEverything() {
        manager.apply(Arrays.asList(net("a"), net("b")));
        calls.clear();
        manager.shutdown();
        assertEquals(Arrays.asList("close a true false Plugin shutting down", "close b true false Plugin shutting down"), calls);
        assertTrue(manager.adapters().isEmpty());
    }

    private static List<String> ids(List<NetworkConfig> configs) {
        List<String> ids = new ArrayList<>();
        for (NetworkConfig c : configs) ids.add(c.getId());
        return ids;
    }
}
