package com.irc;

import lombok.Builder;
import lombok.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One IRC network the plugin connects to. Extra networks are stored by {@link NetworkStore};
 * the built-in SwiftIRC entry is rebuilt from the Connection settings by {@link #swiftIrc}.
 */
@Value
@Builder(toBuilder = true)
public class NetworkConfig {
    static final String SWIFTIRC_ID = "swiftirc";
    static final String SWIFTIRC_NAME = "SwiftIRC";
    static final int DEFAULT_PORT = 6697;

    String id;
    String name;
    String host;
    @Builder.Default int port = DEFAULT_PORT;
    @Builder.Default boolean tls = true;
    /** False accepts any certificate for any host: still encrypted, but not authenticated. */
    @Builder.Default boolean verifyTls = true;
    /** Blank means "use the Username setting". */
    @Builder.Default String nick = "";
    /** Sent as PASS before registering; ZNC reads "user/network:password" from it. */
    @Builder.Default String serverPassword = "";
    @Builder.Default String saslAccount = "";
    @Builder.Default String saslPassword = "";
    /** Comma-separated channels joined on connect. */
    @Builder.Default String autojoin = "";
    @Builder.Default boolean enabled = true;

    boolean isBuiltIn() {
        return SWIFTIRC_ID.equals(id);
    }

    /** True when moving from this config to {@code other} needs a fresh connection. */
    boolean connectionDiffers(NetworkConfig other) {
        return !Objects.equals(host, other.host) || port != other.port || tls != other.tls
                || verifyTls != other.verifyTls
                || !Objects.equals(nick, other.nick)
                || !Objects.equals(serverPassword, other.serverPassword)
                || !Objects.equals(saslAccount, other.saslAccount)
                || !Objects.equals(saslPassword, other.saslPassword);
    }

    /** The autojoin list as channel names, each starting with # or &. */
    List<String> autojoinChannels() {
        List<String> channels = new ArrayList<>();
        for (String part : autojoin.split(",")) {
            String channel = part.trim();
            if (channel.isEmpty()) continue;
            channels.add(channel.startsWith("#") || channel.startsWith("&") ? channel : "#" + channel);
        }
        return channels;
    }

    /** The built-in network, from today's Connection settings. Its nick is seeded by the plugin. */
    static NetworkConfig swiftIrc(IrcConfig config) {
        String password = config.password();
        return NetworkConfig.builder()
                .id(SWIFTIRC_ID)
                .name(SWIFTIRC_NAME)
                .host(config.server().getHostname())
                .port(DEFAULT_PORT)
                .tls(true)
                .verifyTls(true)
                .saslAccount(config.accountName() == null ? "" : config.accountName())
                .saslPassword(password == null ? "" : password)
                .autojoin(config.channel() == null ? "" : config.channel())
                .enabled(config.swiftIrcEnabled())
                .build();
    }
}
