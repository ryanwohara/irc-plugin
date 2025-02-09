package com.irc;

import lombok.Value;
import java.time.Instant;

@Value
public class IrcMessage {
    String channel;
    String sender;
    String content;
    MessageType type;
    Instant timestamp;

    enum MessageType {
        CHAT, SYSTEM, JOIN, PART, QUIT, NICK_CHANGE, PRIVATE, NOTICE, KICK, TOPIC
    }
}