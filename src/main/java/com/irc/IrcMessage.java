package com.irc;

import lombok.AllArgsConstructor;
import lombok.Value;
import java.time.Instant;

@Value
@AllArgsConstructor
public class IrcMessage {
    String channel;
    String sender;
    String content;
    MessageType type;
    Instant timestamp;
    /** The sender's channel prefix ("@", "+", ...) when the message was received; "" when none. */
    String prefix;

    IrcMessage(String channel, String sender, String content, MessageType type, Instant timestamp) {
        this(channel, sender, content, type, timestamp, "");
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