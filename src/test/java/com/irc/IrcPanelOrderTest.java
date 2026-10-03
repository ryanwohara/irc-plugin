package com.irc;

import org.junit.Test;

import javax.swing.JComboBox;
import javax.swing.JTabbedPane;
import java.awt.Color;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Buffers and networks can be reordered; the tab, pane, unread flag and dropdown move together. */
public class IrcPanelOrderTest {
    private static final String RIZON = "rizon-id";
    private static final String LIBERA = "libera-id";

    private final List<String> calls = new ArrayList<>();

    private IrcPanel panel() throws Exception {
        IrcPanel panel = new IrcPanel();
        set(panel, "config", new IrcConfig() {
            @Override public String username() { return "tester"; }
            @Override public String password() { return ""; }
        });
        set(panel, "tabbedPane", new JTabbedPane());
        panel.addChannel("System");
        panel.setFocusedChannel("System");
        panel.setNetworkName(RIZON, "Rizon");
        panel.setOrderListener(new IrcPanel.OrderListener() {
            @Override public void networkOrderChanged(List<String> ids) { calls.add("networks " + ids); }
            @Override public void channelOrderChanged(String id, List<String> names) { calls.add("channels " + id + " " + names); }
        });
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

    private static List<String> tabTitles(IrcPanel panel) throws Exception {
        JTabbedPane tabs = get(panel, "tabbedPane");
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) titles.add(tabs.getTitleAt(i));
        return titles;
    }

    private static List<String> dropdownItems(IrcPanel panel) throws Exception {
        JComboBox<String> dropdown = get(panel, "bufferDropdown");
        List<String> items = new ArrayList<>();
        for (int i = 0; i < dropdown.getItemCount(); i++) items.add(dropdown.getItemAt(i));
        return items;
    }

    private static List<BufferKey> keys(String... names) {
        List<BufferKey> keys = new ArrayList<>();
        for (String name : names) {
            int split = name.indexOf('/');
            keys.add(split < 0 ? BufferKey.swiftIrc(name) : BufferKey.of(name.substring(0, split), name.substring(split + 1)));
        }
        return keys;
    }

    @Test
    public void moveBufferKeepsTabsPanesUnreadAndDropdownInStep() throws Exception {
        IrcPanel panel = panel();
        panel.addChannel("#a");
        panel.addChannel("#b");
        panel.addChannel(BufferKey.of(RIZON, "#c"));
        panel.setFocusedChannel("#b");
        panel.unreadMessages.put(BufferKey.of(RIZON, "#c"), true);
        JTabbedPane tabs = get(panel, "tabbedPane");
        tabs.setForegroundAt(3, Color.CYAN);
        IrcPanel.ChannelPane paneC = panel.getChannelPanes().get(BufferKey.of(RIZON, "#c"));

        panel.moveBuffer(BufferKey.of(RIZON, "#c"), 1);

        assertEquals(keys("System", RIZON + "/#c", "#a", "#b"), panel.getBuffers());
        assertEquals(Arrays.asList("System", "#c (Rizon)", "#a", "#b"), tabTitles(panel));
        assertEquals(tabTitles(panel), dropdownItems(panel));
        assertEquals(new ArrayList<>(panel.getChannelPanes().keySet()), new ArrayList<>(panel.unreadMessages.keySet()));
        assertSame(paneC, ((javax.swing.JScrollPane) tabs.getComponentAt(1)).getViewport().getView());
        assertEquals(Color.CYAN, tabs.getForegroundAt(1));
        assertTrue(panel.unreadMessages.get(BufferKey.of(RIZON, "#c")));
        assertEquals(BufferKey.swiftIrc("#b"), panel.getCurrentBuffer());
        JComboBox<String> dropdown = get(panel, "bufferDropdown");
        assertEquals("#b", dropdown.getSelectedItem());
        assertTrue("a programmatic move does not report itself", calls.isEmpty());
    }

    @Test
    public void networkOrderPutsListedIdsFirstAndKeepsTheRest() throws Exception {
        IrcPanel panel = panel();
        panel.setNetworkName(LIBERA, "Libera");
        panel.setNetworkOrder(Arrays.asList(LIBERA, "unknown", NetworkConfig.SWIFTIRC_ID));
        assertEquals(Arrays.asList(LIBERA, NetworkConfig.SWIFTIRC_ID, RIZON), panel.networkOrder());
        assertTrue(calls.isEmpty());
    }

    @Test
    public void newChannelsTakeTheirSavedPlace() throws Exception {
        IrcPanel panel = panel();
        panel.setChannelOrder(RIZON, Arrays.asList("#c", "#a", "#b"));
        panel.addChannel(BufferKey.of(RIZON, "Luna"));
        panel.addChannel(BufferKey.of(RIZON, "#b"));
        // Before #b, which comes later in the saved order.
        panel.addChannel(BufferKey.of(RIZON, "#A"));
        // Nothing later is open: straight after the network's last channel.
        panel.addChannel("#swift");
        panel.addChannel(BufferKey.of(RIZON, "#zzz"));
        panel.addChannel(BufferKey.of(RIZON, "#c"));
        assertEquals(keys("System", RIZON + "/Luna", RIZON + "/#c", RIZON + "/#A", RIZON + "/#b", "#swift", RIZON + "/#zzz"),
                panel.getBuffers());
        assertEquals(tabTitles(panel), dropdownItems(panel));
        assertEquals(new ArrayList<>(panel.getChannelPanes().keySet()), new ArrayList<>(panel.unreadMessages.keySet()));
        assertTrue(calls.isEmpty());
    }

    @Test
    public void savedChannelGoesAfterTheNetworksLastChannelWhenNothingLaterIsOpen() throws Exception {
        IrcPanel panel = panel();
        panel.setChannelOrder(RIZON, Arrays.asList("#a", "#b"));
        panel.addChannel(BufferKey.of(RIZON, "#a"));
        panel.addChannel("#swift");
        panel.addChannel(BufferKey.of(RIZON, "#b"));
        assertEquals(keys("System", RIZON + "/#a", RIZON + "/#b", "#swift"), panel.getBuffers());
    }

    @Test
    public void withoutASavedOrderNewBuffersAreAppended() throws Exception {
        IrcPanel panel = panel();
        panel.addChannel("#b");
        panel.addChannel("#a");
        panel.addChannel(BufferKey.of(RIZON, "#c"));
        assertEquals(keys("System", "#b", "#a", RIZON + "/#c"), panel.getBuffers());
    }

    @Test
    public void sortChannelsReordersOpenChannelsQuietly() throws Exception {
        IrcPanel panel = panel();
        panel.addChannel("#a");
        panel.addChannel("Luna");
        panel.addChannel("#b");
        panel.addChannel("#other");
        panel.addChannel("#c");
        panel.setFocusedChannel("#b");
        panel.setChannelOrder(NetworkConfig.SWIFTIRC_ID, Arrays.asList("#c", "#b", "#a"));
        panel.sortChannels(NetworkConfig.SWIFTIRC_ID);
        // Listed channels swap among their own slots; unlisted buffers stay put.
        assertEquals(keys("System", "#c", "Luna", "#b", "#other", "#a"), panel.getBuffers());
        assertEquals(tabTitles(panel), dropdownItems(panel));
        assertEquals(BufferKey.swiftIrc("#b"), panel.getCurrentBuffer());
        assertTrue(calls.isEmpty());
    }

    @Test
    public void userMovesReorderAndReportTheNewOrder() throws Exception {
        IrcPanel panel = panel();
        panel.addChannel("#a");
        panel.addChannel("Luna");
        panel.addChannel("#b");
        panel.addChannel("Ash");
        panel.addChannel(BufferKey.of(RIZON, "#c"));

        panel.userMovedBuffer(BufferKey.swiftIrc("#b"), 0);
        assertEquals(keys("System", "#b", "#a", "Luna", "Ash", RIZON + "/#c"), panel.getBuffers());
        panel.userMovedBuffer(BufferKey.swiftIrc("Luna"), 1);
        assertEquals(keys("System", "#b", "#a", "Ash", "Luna", RIZON + "/#c"), panel.getBuffers());
        panel.userMovedNetwork(RIZON, 0);
        assertEquals(Arrays.asList(RIZON, NetworkConfig.SWIFTIRC_ID), panel.networkOrder());

        // Private chats are never saved, so moving one reports nothing.
        assertEquals(Arrays.asList("channels swiftirc [#b, #a]", "networks [rizon-id, swiftirc]"), calls);
    }

    @Test
    public void userMovedChannelIsUsedForLaterJoins() throws Exception {
        IrcPanel panel = panel();
        panel.addChannel("#a");
        panel.addChannel("#b");
        panel.userMovedBuffer(BufferKey.swiftIrc("#b"), 0);
        panel.removeChannel("#a");
        panel.addChannel("#a");
        assertEquals(keys("System", "#b", "#a"), panel.getBuffers());
        panel.removeChannel("#b");
        panel.addChannel("#b");
        assertEquals(keys("System", "#b", "#a"), panel.getBuffers());
    }

    @Test
    public void reorderingToTheSameOrderChangesNothing() throws Exception {
        IrcPanel panel = panel();
        panel.addChannel("#a");
        panel.addChannel("#b");
        panel.setNetworkOrder(Arrays.asList(NetworkConfig.SWIFTIRC_ID, RIZON));
        panel.setChannelOrder(NetworkConfig.SWIFTIRC_ID, Arrays.asList("#a", "#b"));
        panel.sortChannels(NetworkConfig.SWIFTIRC_ID);
        panel.moveBuffer(BufferKey.swiftIrc("#a"), 1);
        assertEquals(keys("System", "#a", "#b"), panel.getBuffers());
        assertEquals(Arrays.asList(NetworkConfig.SWIFTIRC_ID, RIZON), panel.networkOrder());
        assertEquals(Collections.emptyList(), calls);
    }
}
