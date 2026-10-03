package com.irc;

import org.junit.Test;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * Regression tests for renameChannel keeping every ordered UI structure consistent.
 *
 * Navigation maps a channel to a tab strictly by POSITION: setFocusedChannel computes
 * an index by iterating channelPanes, then calls tabbedPane.setSelectedIndex(index),
 * while getCurrentChannel()/getChannelNames() read the tab titles. That only works if
 * the channelPanes iteration order stays identical to the tabbedPane tab order.
 *
 * renameChannel() renamed the tab in place but did remove()+put() on the LinkedHashMap,
 * which moved the renamed channel to the END of the iteration order - diverging the two
 * orderings and sending navigation to the wrong tab. The bufferDropdown was also left with
 * the stale old label. A NICK change (e.g. a renamed query window) triggers this normally.
 */
public class IrcPanelRenameTest {

    private static JTabbedPane installTabbedPane(IrcPanel panel) throws Exception {
        JTabbedPane tabs = new JTabbedPane();
        Field tabbedPaneField = IrcPanel.class.getDeclaredField("tabbedPane");
        tabbedPaneField.setAccessible(true);
        tabbedPaneField.set(panel, tabs);
        return tabs;
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<String> bufferDropdown(IrcPanel panel) throws Exception {
        Field field = IrcPanel.class.getDeclaredField("bufferDropdown");
        field.setAccessible(true);
        return (JComboBox<String>) field.get(panel);
    }

    private static void addChannel(IrcPanel panel, JTabbedPane tabs, JComboBox<String> dropdown, String name) {
        panel.getChannelPanes().put(BufferKey.swiftIrc(name), null);
        panel.unreadMessages.put(BufferKey.swiftIrc(name), false);
        tabs.addTab(name, new JPanel());
        if (dropdown != null) {
            dropdown.addItem(name);
        }
    }

    @Test
    public void renameKeepsChannelOrderAlignedWithTabs() throws Exception {
        IrcPanel panel = new IrcPanel();
        JTabbedPane tabs = installTabbedPane(panel);

        addChannel(panel, tabs, null, "System");
        addChannel(panel, tabs, null, "#alpha");
        addChannel(panel, tabs, null, "bob");
        addChannel(panel, tabs, null, "#gamma");

        // A NICK change renames a query window in place: bob -> bob2 (originally at index 2).
        panel.renameChannel("bob", "bob2");

        List<String> channelOrder = panel.getChannelNames();
        assertEquals("tab count and channel count must match", tabs.getTabCount(), channelOrder.size());
        for (int i = 0; i < tabs.getTabCount(); i++) {
            assertEquals("channelPanes order must match tab order at index " + i,
                    tabs.getTitleAt(i), channelOrder.get(i));
        }
        // The renamed channel must keep its position, not jump to the end.
        assertEquals("bob2", channelOrder.get(2));
    }

    @Test
    public void renameUpdatesBufferDropdownLabelInPlace() throws Exception {
        IrcPanel panel = new IrcPanel();
        JTabbedPane tabs = installTabbedPane(panel);
        JComboBox<String> dropdown = bufferDropdown(panel);

        addChannel(panel, tabs, dropdown, "System");
        addChannel(panel, tabs, dropdown, "#alpha");
        addChannel(panel, tabs, dropdown, "bob");
        addChannel(panel, tabs, dropdown, "#gamma");
        dropdown.setSelectedIndex(2); // "bob" is the active buffer

        panel.renameChannel("bob", "bob2");

        // The label is replaced in place: new label at the same index, old label gone.
        assertEquals("bob2", dropdown.getItemAt(2));
        for (int i = 0; i < dropdown.getItemCount(); i++) {
            assertNotEquals("stale old label must be gone", "bob", dropdown.getItemAt(i));
        }
        // Order preserved (matches tabs) and the active selection stays on the renamed buffer.
        for (int i = 0; i < tabs.getTabCount(); i++) {
            assertEquals(tabs.getTitleAt(i), dropdown.getItemAt(i));
        }
        assertEquals(2, dropdown.getSelectedIndex());
    }
}
