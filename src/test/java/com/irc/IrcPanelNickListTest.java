package com.irc;

import org.junit.Test;

import javax.swing.JComboBox;
import javax.swing.JTabbedPane;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The nicklist dropdown: a compact action menu left of the buffer dropdown.
 *
 * Index 0 is a "Users (N)" header rather than a selectable user, and picking a real entry opens
 * that user's PM buffer then snaps back to the header - it is a menu, not a persistent selection.
 * The real protection against reentrant repopulation is the {@code index < 1} guard in
 * {@code openQueryFromNickDropdown} - every spurious action event a model mutation can fire
 * arrives at index -1 or 0, which that guard already rejects. The listener detach/reattach in
 * {@code repopulateNickDropdown} is defensive depth kept to match {@code renameBufferDropdownItem}'s
 * precedent, not something this test suite can exercise: {@code initializeGui} is the only place
 * the tab-change listener is installed, and it never runs in a headless test.
 */
public class IrcPanelNickListTest {

    /**
     * Builds a panel through addChannel so channelPanes stays index-aligned with tabbedPane -
     * setFocusedChannel maps a channel to a tab by position, so injecting tabs directly would
     * desync the two and getCurrentChannel() would report the wrong buffer.
     */
    private static IrcPanel panelWith(String... channels) throws Exception {
        IrcPanel panel = new IrcPanel();
        set(panel, "config", stubConfig());
        set(panel, "tabbedPane", new JTabbedPane());

        for (String channel : channels) {
            panel.addChannel(channel);
        }
        panel.setFocusedChannel(channels[0]);
        return panel;
    }

    /** Every IrcConfig method is default except username() and password(). */
    private static IrcConfig stubConfig() {
        return new IrcConfig() {
            @Override
            public String username() {
                return "tester";
            }

            @Override
            public String password() {
                return "";
            }
        };
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = IrcPanel.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<String> nickDropdown(IrcPanel panel) throws Exception {
        Field f = IrcPanel.class.getDeclaredField("nickDropdown");
        f.setAccessible(true);
        return (JComboBox<String>) f.get(panel);
    }

    private static List<ChannelUserList.Entry> roster() {
        return Arrays.asList(
                new ChannelUserList.Entry("bob", "@", 2),
                new ChannelUserList.Entry("dave", "+", 4),
                new ChannelUserList.Entry("erin", "", Integer.MAX_VALUE));
    }

    @Test
    public void headerReportsTheUserCount() throws Exception {
        IrcPanel panel = panelWith("#chan");
        panel.setChannelUsers("#chan", roster());
        assertEquals("Users (3)", nickDropdown(panel).getItemAt(0));
    }

    @Test
    public void entriesRenderWithTheirPrefix() throws Exception {
        IrcPanel panel = panelWith("#chan");
        panel.setChannelUsers("#chan", roster());

        JComboBox<String> dropdown = nickDropdown(panel);
        assertEquals(4, dropdown.getItemCount());
        assertEquals("@bob", dropdown.getItemAt(1));
        assertEquals("+dave", dropdown.getItemAt(2));
        assertEquals("erin", dropdown.getItemAt(3));
    }

    @Test
    public void headerIsSelectedAfterPopulating() throws Exception {
        IrcPanel panel = panelWith("#chan");
        panel.setChannelUsers("#chan", roster());
        assertEquals(0, nickDropdown(panel).getSelectedIndex());
    }

    @Test
    public void selectingANickOpensAndFocusesThatPmBuffer() throws Exception {
        IrcPanel panel = panelWith("#chan");
        panel.setChannelUsers("#chan", roster());

        nickDropdown(panel).setSelectedIndex(1); // "@bob"

        assertTrue("a PM buffer for bob should exist", panel.isPane("bob"));
        assertEquals("and it should be focused", "bob", panel.getCurrentChannel());
    }

    @Test
    public void selectingANickStripsThePrefixFromTheBufferName() throws Exception {
        IrcPanel panel = panelWith("#chan");
        panel.setChannelUsers("#chan", roster());

        nickDropdown(panel).setSelectedIndex(1); // "@bob"

        assertTrue(panel.isPane("bob"));
        assertFalse("the prefix is decoration, not part of the nick", panel.isPane("@bob"));
    }

    @Test
    public void selectingANickSnapsBackToTheHeader() throws Exception {
        IrcPanel panel = panelWith("#chan");
        panel.setChannelUsers("#chan", roster());

        nickDropdown(panel).setSelectedIndex(2); // "+dave"

        assertEquals("action menu, not a persistent selection", 0, nickDropdown(panel).getSelectedIndex());
    }

    @Test
    public void reSelectingTheHeaderAfterAnActionOpensNothingNew() throws Exception {
        IrcPanel panel = panelWith("#chan");
        panel.setChannelUsers("#chan", roster());

        nickDropdown(panel).setSelectedIndex(1);   // opens "bob", then snaps back to the header
        int buffersAfterPick = panel.getChannelNames().size();

        nickDropdown(panel).setSelectedIndex(0);   // the header is not an action

        assertEquals("header selection must not open a buffer", buffersAfterPick, panel.getChannelNames().size());
    }

    @Test
    public void anUpdateForAnUnfocusedChannelDoesNotRedrawTheDropdown() throws Exception {
        IrcPanel panel = panelWith("#chan", "#other");
        panel.setChannelUsers("#chan", roster());                       // #chan is tab 0, focused
        panel.setChannelUsers("#other", Collections.singletonList(
                new ChannelUserList.Entry("zoe", "", Integer.MAX_VALUE)));

        assertEquals("still showing #chan's roster", "Users (3)", nickDropdown(panel).getItemAt(0));
    }

    @Test
    public void aRosterPushedWithDifferentChannelCasingStillShows() throws Exception {
        // The tab title comes from a JOIN echo; the roster's channel comes from a 366 numeric.
        // A server that canonicalises those differently must not silently break the dropdown.
        IrcPanel panel = panelWith("#chan");
        panel.setChannelUsers("#CHAN", roster());
        assertEquals("Users (3)", nickDropdown(panel).getItemAt(0));
    }

    @Test
    public void dropdownIsDisabledOnNonChannelBuffers() throws Exception {
        IrcPanel panel = panelWith("System");
        panel.setChannelUsers("System", Collections.emptyList());
        assertFalse(nickDropdown(panel).isEnabled());
    }

    @Test
    public void dropdownEnablementFollowsTheFocusedBuffer() throws Exception {
        // A fresh JComboBox is enabled by default, so only a transition proves setEnabled runs.
        IrcPanel panel = panelWith("System", "#chan");

        panel.setFocusedChannel("System");
        panel.setChannelUsers("System", Collections.emptyList());
        assertFalse("System is not a channel", nickDropdown(panel).isEnabled());

        panel.setFocusedChannel("#chan");
        panel.setChannelUsers("#chan", roster());
        assertTrue("#chan is a channel", nickDropdown(panel).isEnabled());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void aRosterArrivingBeforeTheGuiExistsIsStoredNotDropped() throws Exception {
        // setChannelUsers can arrive from the IRC thread before initializeGui has run. It must
        // skip the redraw without throwing, and still keep the roster for when the GUI catches up.
        IrcPanel panel = new IrcPanel(); // tabbedPane is still null
        panel.setChannelUsers("#chan", roster());

        Field field = IrcPanel.class.getDeclaredField("channelUserSnapshots");
        field.setAccessible(true);
        Map<String, List<ChannelUserList.Entry>> snapshots =
                (Map<String, List<ChannelUserList.Entry>>) field.get(panel);

        assertEquals("roster kept despite no GUI to draw it on", 3, snapshots.get("#chan").size());
    }

    @Test
    public void anEmptyRosterLeavesOnlyTheHeader() throws Exception {
        IrcPanel panel = panelWith("#chan");
        panel.setChannelUsers("#chan", Collections.emptyList());

        JComboBox<String> dropdown = nickDropdown(panel);
        assertEquals(1, dropdown.getItemCount());
        assertEquals("Users (0)", dropdown.getItemAt(0));
    }
}
