package com.irc;

import org.junit.Test;

import javax.swing.JComboBox;
import javax.swing.JTabbedPane;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/** Buffers are identified by network and name; the same name on two networks is two buffers. */
public class IrcPanelNetworkTest {
    private static final String RIZON = "rizon-id";
    private static final IrcMessage.MessageType CHAT = IrcMessage.MessageType.CHAT;

    private static IrcPanel panel() throws Exception {
        IrcPanel panel = new IrcPanel();
        set(panel, "config", new IrcConfig() {
            @Override public String username() { return "tester"; }
            @Override public String password() { return ""; }
        });
        set(panel, "tabbedPane", new JTabbedPane());
        panel.addChannel("System");
        panel.setFocusedChannel("System");
        panel.setNetworkName(RIZON, "Rizon");
        return panel;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = IrcPanel.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T get(Object target, String field) throws Exception {
        Field f = IrcPanel.class.getDeclaredField(field);
        f.setAccessible(true);
        return (T) f.get(target);
    }

    @Test
    public void sameChannelNameOnTwoNetworksMakesTwoBuffers() throws Exception {
        IrcPanel panel = panel();
        panel.addMessage(new IrcMessage("#rshelp", "Ash", "on swift", CHAT, Instant.now()));
        panel.addMessage(new IrcMessage("#rshelp", "Ash", "on rizon", CHAT, Instant.now()).withNetworkId(RIZON));
        assertEquals(Arrays.asList(BufferKey.swiftIrc("System"), BufferKey.swiftIrc("#rshelp"),
                BufferKey.of(RIZON, "#rshelp")), panel.getBuffers());
        assertNotSame(panel.getChannelPanes().get(BufferKey.swiftIrc("#rshelp")),
                panel.getChannelPanes().get(BufferKey.of(RIZON, "#rshelp")));
    }

    @Test
    public void lateMessageForARemovedNetworkDoesNotRecreateItsBuffer() throws Exception {
        IrcPanel panel = panel();
        panel.addMessage(new IrcMessage("#foo", "Ash", "hi", CHAT, Instant.now()).withNetworkId(RIZON));
        panel.removeNetworkBuffers(RIZON);
        panel.forgetNetwork(RIZON);
        panel.addMessage(new IrcMessage("#foo", "Ash", "late", CHAT, Instant.now()).withNetworkId(RIZON));
        assertEquals(Collections.singletonList(BufferKey.swiftIrc("System")), panel.getBuffers());
    }

    @Test
    public void samePmNickOnTwoNetworksMakesTwoBuffers() throws Exception {
        IrcPanel panel = panel();
        panel.addMessage(new IrcMessage("Luna", "Luna", "hi", CHAT, Instant.now()));
        panel.addMessage(new IrcMessage("Luna", "Luna", "hi", CHAT, Instant.now()).withNetworkId(RIZON));
        assertTrue(panel.isPane(BufferKey.swiftIrc("Luna")));
        assertTrue(panel.isPane(BufferKey.of(RIZON, "Luna")));
        assertEquals(3, panel.getBuffers().size());
    }

    @Test
    public void extraNetworkBuffersAreTitledWithTheirNetwork() throws Exception {
        IrcPanel panel = panel();
        panel.addChannel(BufferKey.of(RIZON, "#foo"));
        panel.addChannel(BufferKey.swiftIrc("#foo"));
        JTabbedPane tabs = get(panel, "tabbedPane");
        JComboBox<String> dropdown = get(panel, "bufferDropdown");
        assertEquals("#foo (Rizon)", tabs.getTitleAt(1));
        assertEquals("#foo", tabs.getTitleAt(2));
        assertEquals("#foo (Rizon)", dropdown.getItemAt(1));

        panel.setNetworkName(RIZON, "Rizon2");
        assertEquals("#foo (Rizon2)", tabs.getTitleAt(1));
        assertEquals("#foo (Rizon2)", dropdown.getItemAt(1));
    }

    @Test
    public void renameTouchesOnlyThatNetwork() throws Exception {
        IrcPanel panel = panel();
        panel.addChannel(BufferKey.swiftIrc("Luna"));
        panel.addChannel(BufferKey.of(RIZON, "Luna"));
        panel.renameChannel(BufferKey.of(RIZON, "Luna"), "Luna2");
        assertTrue(panel.isPane(BufferKey.swiftIrc("Luna")));
        assertTrue(panel.isPane(BufferKey.of(RIZON, "Luna2")));
        assertFalse(panel.isPane(BufferKey.of(RIZON, "Luna")));
    }

    @Test
    public void removingANetworkDropsAllItsBuffersAndRefocuses() throws Exception {
        IrcPanel panel = panel();
        panel.addChannel(BufferKey.of(RIZON, "System"));
        panel.addChannel(BufferKey.of(RIZON, "#foo"));
        panel.setFocusedChannel(BufferKey.of(RIZON, "#foo"));
        panel.removeNetworkBuffers(RIZON);
        assertEquals(Collections.singletonList(BufferKey.swiftIrc("System")), panel.getBuffers());
        assertEquals(BufferKey.swiftIrc("System"), panel.getCurrentBuffer());
    }

    @Test
    public void historyDoesNotMarkABufferUnread() throws Exception {
        IrcPanel panel = panel();
        panel.addChannel("#a"); // second buffer: gets focus
        panel.addMessage(new IrcMessage("#old", "Ash", "replayed", IrcMessage.MessageType.HISTORY, Instant.now()));
        panel.addMessage(new IrcMessage("#new", "Ash", "live", CHAT, Instant.now()));
        assertFalse(panel.isUnread("#old"));
        assertTrue(panel.isUnread("#new"));
    }

    @Test
    public void currentChannelOnAnotherNetworkIsItsSystemBuffer() throws Exception {
        IrcPanel panel = panel();
        panel.addChannel("#a");
        panel.setFocusedChannel("#a");
        assertEquals("#a", panel.getCurrentChannelOn(NetworkConfig.SWIFTIRC_ID));
        assertEquals("System", panel.getCurrentChannelOn(RIZON));
    }

    @Test
    public void rostersAreKeptPerNetwork() throws Exception {
        IrcPanel panel = panel();
        panel.addChannel(BufferKey.of(RIZON, "#foo"));
        panel.addChannel(BufferKey.swiftIrc("#foo"));
        panel.setChannelUsers(BufferKey.of(RIZON, "#FOO"),
                Collections.singletonList(new ChannelUserList.Entry("Ash", "@", 0)));
        // Headless panels have no tab change listener, so drive the focus hook by hand.
        panel.setFocusedChannel(BufferKey.swiftIrc("#foo"));
        panel.onFocusedBufferChanged();
        JComboBox<String> nicks = get(panel, "nickDropdown");
        assertEquals("Users (0)", nicks.getItemAt(0));
        panel.setFocusedChannel(BufferKey.of(RIZON, "#foo"));
        panel.onFocusedBufferChanged();
        assertEquals("Users (1)", nicks.getItemAt(0));
    }
}
