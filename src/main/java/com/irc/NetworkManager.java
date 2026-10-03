package com.irc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns one {@link IrcAdapter} per running network and keeps that set in step with the saved
 * list. Synchronized: the EDT (buttons, dialog) and RuneLite's client thread (config changes,
 * chatbox commands) both call in.
 */
final class NetworkManager {
    /** Opens and closes connections; the plugin supplies the real one, tests a recorder. */
    interface Connector {
        IrcAdapter open(NetworkConfig network);

        /** {@code adapter} is null when the network was not running (e.g. removed while disabled). */
        void close(IrcAdapter adapter, NetworkConfig network, boolean removed, String reason);
    }

    private final Connector connector;
    /** Every saved network, in display order, enabled or not. */
    private final Map<String, NetworkConfig> known = new LinkedHashMap<>();
    private final Map<String, IrcAdapter> running = new LinkedHashMap<>();
    /** Networks the user disconnected by hand; a config change must not bring them back. */
    private final Set<String> paused = new HashSet<>();

    NetworkManager(Connector connector) {
        this.connector = connector;
    }

    synchronized void apply(List<NetworkConfig> configs) {
        Map<String, NetworkConfig> next = new LinkedHashMap<>();
        for (NetworkConfig config : configs) next.put(config.getId(), config);

        for (String id : new ArrayList<>(known.keySet())) {
            if (!next.containsKey(id)) {
                stop(id, true, "Network removed");
                known.remove(id);
                paused.remove(id);
            }
        }

        Map<String, NetworkConfig> previous = new LinkedHashMap<>(known);
        known.clear();
        known.putAll(next);
        for (NetworkConfig config : next.values()) {
            String id = config.getId();
            NetworkConfig before = previous.get(id);
            if (!config.isEnabled()) {
                stop(id, false, "Disconnecting");
            } else if (!running.containsKey(id)) {
                if (!paused.contains(id)) start(config);
            } else if (before != null && before.connectionDiffers(config)) {
                stop(id, false, "Reloading, brb");
                start(config);
            }
        }
    }

    synchronized IrcAdapter get(String id) {
        return running.get(id);
    }

    synchronized NetworkConfig config(String id) {
        return known.get(id);
    }

    synchronized List<NetworkConfig> configs() {
        return new ArrayList<>(known.values());
    }

    synchronized List<IrcAdapter> adapters() {
        return new ArrayList<>(running.values());
    }

    synchronized boolean isKnown(String id) {
        return known.containsKey(id);
    }

    synchronized boolean isRunning(String id) {
        return running.containsKey(id);
    }

    synchronized void reconnect(String id) {
        NetworkConfig config = known.get(id);
        if (config == null || !config.isEnabled()) return;
        paused.remove(id);
        stop(id, false, "Reloading, brb");
        start(config);
    }

    /**
     * Reconnects with {@code fresh} (live settings, e.g. the SwiftIRC Connection config) in place of
     * the stored config. Ignored for a network that is not known.
     */
    synchronized void reconnect(NetworkConfig fresh) {
        String id = fresh.getId();
        if (!known.containsKey(id)) return;
        known.put(id, fresh);
        reconnect(id);
    }

    /** Replaces the stored config of a known network without touching its connection. */
    synchronized void update(NetworkConfig fresh) {
        if (known.containsKey(fresh.getId())) known.put(fresh.getId(), fresh);
    }

    /** Connects or disconnects for this session without touching the saved enabled flag. */
    synchronized void setConnected(String id, boolean connected) {
        NetworkConfig config = known.get(id);
        if (config == null) return;
        if (connected) {
            paused.remove(id);
            if (!config.isEnabled()) return;
            if (!running.containsKey(id)) {
                start(config);
            } else {
                // Still listed but dropped (lost link, bad password, /quit): connect afresh.
                IrcAdapter adapter = running.get(id);
                if (adapter == null || !adapter.isConnected()) {
                    stop(id, false, "Reloading, brb");
                    start(config);
                }
            }
        } else {
            paused.add(id);
            stop(id, false, "Disconnecting");
        }
    }

    synchronized void shutdown() {
        for (String id : new ArrayList<>(running.keySet())) stop(id, false, "Plugin shutting down");
    }

    /** Test seam: treat {@code adapter} as the running connection for {@code config}. */
    synchronized void register(NetworkConfig config, IrcAdapter adapter) {
        known.put(config.getId(), config);
        running.put(config.getId(), adapter);
    }

    private void start(NetworkConfig config) {
        running.put(config.getId(), connector.open(config));
    }

    private void stop(String id, boolean removed, String reason) {
        IrcAdapter adapter = running.remove(id);
        NetworkConfig config = known.get(id);
        if (config == null) return;
        if (adapter != null || removed) connector.close(adapter, config, removed, reason);
    }
}
