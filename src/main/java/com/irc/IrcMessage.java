package com.irc;

import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.With;
import java.time.Instant;

@Value
@AllArgsConstructor
public class IrcMessage {
    /** Sender of a channel's topic text, both on join (RPL_TOPIC) and when someone changes it. */
    static final String TOPIC_SENDER = "* Topic";

    String channel;
    String sender;
    String content;
    MessageType type;
    Instant timestamp;
    /** The sender's channel prefix ("@", "+", ...) when the message was received; "" when none. */
    String prefix;
    /** The network the message belongs to; see {@link NetworkConfig#SWIFTIRC_ID}. */
    @With
    String networkId;

    IrcMessage(String channel, String sender, String content, MessageType type, Instant timestamp) {
        this(channel, sender, content, type, timestamp, "");
    }

    IrcMessage(String channel, String sender, String content, MessageType type, Instant timestamp, String prefix) {
        this(channel, sender, content, type, timestamp, prefix, NetworkConfig.SWIFTIRC_ID);
    }

    BufferKey getBuffer() {
        return BufferKey.of(networkId, channel);
    }

    /** The sender with its prefix, kept after the "* " that marks an action: "@bob", "* @bob". */
    String getDisplaySender() {
        if (prefix.isEmpty()) return sender;
        if (sender.startsWith("* ")) return "* " + prefix + sender.substring(2);
        return prefix + sender;
    }

    enum MessageType {
        CHAT, SYSTEM, JOIN, PART, QUIT, NICK_CHANGE, PRIVATE, NOTICE, KICK, TOPIC, MODE,
        HISTORY, HISTORY_SEPARATOR
    }
}