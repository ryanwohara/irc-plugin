package com.irc;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads and writes the extra networks kept as JSON under the hidden {@code irc.networks} key. */
@Slf4j
final class NetworkStore {
    static final String CONFIG_KEY = "networks";
    private static final Type STORED_LIST = new TypeToken<List<Stored>>() {}.getType();

    private NetworkStore() {
    }

    /**
     * NetworkConfig's fields with boxed types. Gson bypasses constructors, so reading straight into
     * NetworkConfig would turn a missing "tls" into false instead of the default true.
     */
    private static final class Stored {
        String id;
        String name;
        String host;
        Integer port;
        Boolean tls;
        String nick;
        String serverPassword;
        String saslAccount;
        String saslPassword;
        String autojoin;
        Boolean enabled;
    }

    /**
     * The valid entries of {@code json}, in order. Unreadable JSON gives an empty list and is
     * logged, never thrown: a bad hand edit must not stop SwiftIRC from connecting.
     */
    static List<NetworkConfig> parse(Gson gson, String json) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        List<Stored> stored;
        try {
            stored = gson.fromJson(json, STORED_LIST);
        } catch (JsonParseException | IllegalStateException e) {
            log.warn("Ignoring unreadable IRC network list", e);
            return Collections.emptyList();
        }
        if (stored == null) return Collections.emptyList();

        List<NetworkConfig> networks = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Stored s : stored) {
            if (s == null || isBlank(s.id) || isBlank(s.name) || isBlank(s.host)) {
                log.warn("Skipping an IRC network without an id, name or host");
                continue;
            }
            if (NetworkConfig.SWIFTIRC_ID.equals(s.id) || !ids.add(s.id)) {
                log.warn("Skipping IRC network {}: its id is reserved or already used", s.name);
                continue;
            }
            networks.add(NetworkConfig.builder()
                    .id(s.id)
                    .name(s.name.trim())
                    .host(s.host.trim())
                    .port(s.port != null ? s.port : NetworkConfig.DEFAULT_PORT)
                    .tls(s.tls == null || s.tls)
                    .nick(orEmpty(s.nick))
                    .serverPassword(orEmpty(s.serverPassword))
                    .saslAccount(orEmpty(s.saslAccount))
                    .saslPassword(orEmpty(s.saslPassword))
                    .autojoin(orEmpty(s.autojoin))
                    .enabled(s.enabled == null || s.enabled)
                    .build());
        }
        return networks;
    }

    static String serialize(Gson gson, List<NetworkConfig> networks) {
        List<Stored> stored = new ArrayList<>();
        for (NetworkConfig n : networks) {
            Stored s = new Stored();
            s.id = n.getId();
            s.name = n.getName();
            s.host = n.getHost();
            s.port = n.getPort();
            s.tls = n.isTls();
            s.nick = n.getNick();
            s.serverPassword = n.getServerPassword();
            s.saslAccount = n.getSaslAccount();
            s.saslPassword = n.getSaslPassword();
            s.autojoin = n.getAutojoin();
            s.enabled = n.isEnabled();
            stored.add(s);
        }
        return gson.toJson(stored, STORED_LIST);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
