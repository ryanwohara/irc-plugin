package com.irc;

import lombok.Getter;

/** One row of a server's LIST reply: a channel, its user count, and its topic. Immutable. */
@Getter
public class ChannelListEntry {
    private final String name;
    private final int userCount;
    private final String topic;

    public ChannelListEntry(String name, int userCount, String topic) {
        this.name = name;
        this.userCount = userCount;
        this.topic = topic;
    }
}
