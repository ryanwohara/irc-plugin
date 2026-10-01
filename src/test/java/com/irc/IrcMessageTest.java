package com.irc;

import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;

/** The sender as drawn in chat: the channel prefix goes on the nick, after an action's "* ". */
public class IrcMessageTest {

    private static IrcMessage message(String sender, String prefix) {
        return new IrcMessage("#chan", sender, "hi", IrcMessage.MessageType.CHAT, Instant.now(), prefix);
    }

    @Test
    public void displaySenderIsTheBareNickWithoutAPrefix() {
        assertEquals("bob", message("bob", "").getDisplaySender());
        assertEquals("bob", new IrcMessage("#chan", "bob", "hi", IrcMessage.MessageType.CHAT, Instant.now())
                .getDisplaySender());
    }

    @Test
    public void displaySenderPrefixesTheNick() {
        assertEquals("@bob", message("bob", "@").getDisplaySender());
    }

    @Test
    public void displaySenderKeepsTheActionMarkerFirst() {
        assertEquals("* +bob", message("* bob", "+").getDisplaySender());
    }
}
