package com.irc;

import org.junit.Test;

import javax.swing.JTabbedPane;
import java.lang.reflect.Field;
import java.time.Instant;

import static com.irc.IrcMessage.MessageType.*;
import static org.junit.Assert.*;

/** Which messages mark a channel that isn't being looked at as unread. */
public class IrcPanelUnreadTest {
    private static IrcPanel panel(boolean quietConnectionMessages) throws Exception {
        IrcPanel panel = new IrcPanel();
        set(panel, "config", new IrcConfig() {
            @Override public String username() { return "tester"; }
            @Override public String password() { return ""; }
            @Override public boolean quietConnectionMessages() { return quietConnectionMessages; }
        });
        set(panel, "tabbedPane", new JTabbedPane());
        panel.addChannel("System");
        panel.addChannel("#a");
        // The second buffer takes focus; look away so #a can go unread.
        panel.setFocusedChannel("System");
        return panel;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = IrcPanel.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void add(IrcPanel panel, IrcMessage.MessageType type) {
        panel.addMessage(new IrcMessage("#a", "Ash", "x", type, Instant.now()));
    }

    @Test
    public void quietByDefault() {
        assertTrue(new IrcConfig() {
            @Override public String username() { return "tester"; }
            @Override public String password() { return ""; }
        }.quietConnectionMessages());
    }

    @Test
    public void connectionMessagesLeaveAChannelReadWhenQuiet() throws Exception {
        IrcPanel panel = panel(true);
        for (IrcMessage.MessageType type : new IrcMessage.MessageType[]{JOIN, PART, QUIT, KICK, NICK_CHANGE}) {
            add(panel, type);
            assertFalse(type + " marked the channel unread", panel.isUnread("#a"));
        }
        add(panel, CHAT);
        assertTrue("conversation still marks it unread", panel.isUnread("#a"));
    }

    @Test
    public void connectionMessagesMarkAChannelUnreadWhenNotQuiet() throws Exception {
        for (IrcMessage.MessageType type : new IrcMessage.MessageType[]{JOIN, PART, QUIT, KICK, NICK_CHANGE}) {
            IrcPanel panel = panel(false);
            add(panel, type);
            assertTrue(type + " should mark the channel unread", panel.isUnread("#a"));
        }
    }
}
