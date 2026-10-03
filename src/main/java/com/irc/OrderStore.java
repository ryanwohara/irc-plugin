package com.irc;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes the user's chosen order of networks ({@code irc.networkOrder}, comma-separated
 * ids) and of channels within each network ({@code irc.channelOrder}, JSON of id to names).
 */
@Slf4j
final class OrderStore {
    static final String NETWORK_ORDER_KEY = "networkOrder";
    static final String CHANNEL_ORDER_KEY = "channelOrder";
    private static final Type CHANNEL_ORDER = new TypeToken<Map<String, List<String>>>() {}.getType();

    private OrderStore() {
    }

    /** The ids in {@code value}, in order, without blanks or repeats. */
    static List<String> parseNetworkOrder(String value) {
        if (value == null) return Collections.emptyList();
        Set<String> ids = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) ids.add(id);
        }
        return new ArrayList<>(ids);
    }

    static String serializeNetworkOrder(List<String> ids) {
        return String.join(",", ids);
    }

    /**
     * {@code networks} with the listed ids first, in listed order, then the rest in their given
     * order. Ids that match no network are ignored: a removed network may linger in the setting.
     */
    static List<NetworkConfig> applyNetworkOrder(List<NetworkConfig> networks, List<String> ids) {
        Map<String, NetworkConfig> remaining = new LinkedHashMap<>();
        for (NetworkConfig network : networks) remaining.put(network.getId(), network);
        List<NetworkConfig> ordered = new ArrayList<>();
        for (String id : ids) {
            NetworkConfig network = remaining.remove(id);
            if (network != null) ordered.add(network);
        }
        ordered.addAll(remaining.values());
        return ordered;
    }

    /**
     * Channel names by network id. Unreadable JSON gives an empty map and is logged, never thrown,
     * like {@link NetworkStore#parse}: a bad hand edit only loses the order.
     */
    static Map<String, List<String>> parseChannelOrder(Gson gson, String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyMap();
        Map<String, List<String>> stored;
        try {
            stored = gson.fromJson(json, CHANNEL_ORDER);
        } catch (JsonParseException | IllegalStateException e) {
            log.warn("Ignoring unreadable IRC channel order", e);
            return Collections.emptyMap();
        }
        return channelsOnly(stored);
    }

    static String serializeChannelOrder(Gson gson, Map<String, List<String>> order) {
        return gson.toJson(channelsOnly(order), CHANNEL_ORDER);
    }

    /**
     * {@code names} with those in {@code order} first, in that order, then the rest as given.
     * Names match ignoring case: a server may echo a channel with different casing.
     */
    static List<String> sortByOrder(List<String> names, List<String> order) {
        List<String> remaining = new ArrayList<>(names);
        List<String> sorted = new ArrayList<>();
        for (String wanted : order) {
            for (int i = 0; i < remaining.size(); i++) {
                if (remaining.get(i).equalsIgnoreCase(wanted)) {
                    sorted.add(remaining.remove(i));
                    break;
                }
            }
        }
        sorted.addAll(remaining);
        return sorted;
    }

    /** The position of {@code name} in {@code order} ignoring case, or -1. */
    static int indexIn(List<String> order, String name) {
        String folded = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).toLowerCase(Locale.ROOT).equals(folded)) return i;
        }
        return -1;
    }

    /** Private chats and System are never ordered by the setting, only channels. */
    private static Map<String, List<String>> channelsOnly(Map<String, List<String>> order) {
        if (order == null) return Collections.emptyMap();
        Map<String, List<String>> kept = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : order.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            List<String> channels = new ArrayList<>();
            for (String name : entry.getValue()) {
                if (name != null && (name.startsWith("#") || name.startsWith("&"))) channels.add(name);
            }
            kept.put(entry.getKey(), channels);
        }
        return kept;
    }
}
