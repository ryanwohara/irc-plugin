package com.irc;

import net.runelite.client.ui.laf.RuneLiteLAF;
import org.junit.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class IrcDesktopLayoutTest {
    @Test
    public void navigationRosterAndDockingShareLiveChatState() throws Exception {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<IrcPanel> ref = new AtomicReference<>();
        AtomicReference<String> sent = new AtomicReference<>();
        LookAndFeel previous = UIManager.getLookAndFeel();
        try {
            SwingUtilities.invokeAndWait(() -> {
                RuneLiteLAF.setup();
                IrcPanel panel = new IrcPanel();
                ref.set(panel);
                try {
                    Field config = IrcPanel.class.getDeclaredField("config");
                    config.setAccessible(true);
                    config.set(panel, new IrcConfig() {
                        @Override public String username() { return "Mikey"; }
                        @Override public String password() { return ""; }
                    });
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                panel.init((channel, text) -> sent.set(text), (network, channel, password) -> {},
                        channel -> {}, reconnect -> {}, (network, query) -> {}, () -> {});
                panel.initializeGui();
                panel.setNetworkConnected(NetworkConfig.SWIFTIRC_ID, true);
            });
            SwingUtilities.invokeAndWait(() -> {
                IrcPanel panel = ref.get();
                panel.addChannel("#runelite");
                panel.addChannel("#rshelp");
                panel.addChannel("#off-topic");
                panel.addChannel("Luna");
                panel.setFocusedChannel("#runelite");
                panel.setChannelUsers("#runelite", Arrays.asList(
                        new ChannelUserList.Entry("Ash", "@", 0),
                        new ChannelUserList.Entry("Mikey", "+", 1),
                        new ChannelUserList.Entry("Luna", "", 2),
                        new ChannelUserList.Entry("Maple", "", 2),
                        new ChannelUserList.Entry("River", "", 2)));
                String[] nicks = {"Ash", "Mikey", "Luna", "Maple", "River", "Mikey"};
                String[] messages = {
                        "Welcome to #runelite. What is everyone working on?",
                        "Trying out the new IRC pop-out layout.",
                        "The channel tree makes switching conversations much easier.",
                        "And the user list stays visible while you chat.",
                        "You can drag either divider to make more room.",
                        "Yep, this feels like a little IRC client now."};
                for (int i = 0; i < nicks.length; i++) {
                    panel.addMessage(new IrcMessage("#runelite", nicks[i], messages[i],
                            IrcMessage.MessageType.CHAT, Instant.parse("2026-09-28T18:32:00Z").plusSeconds(i * 18)));
                }
                panel.addMessage(new IrcMessage("#runelite", IrcMessage.TOPIC_SENDER,
                        "\u0002Welcome\u0002 to #runelite | Be nice | <html><b>not bold</b>",
                        IrcMessage.MessageType.TOPIC, Instant.parse("2026-09-28T18:31:00Z")));
                panel.addMessage(new IrcMessage("#runelite", "* Topic set by", "Ash",
                        IrcMessage.MessageType.TOPIC, Instant.parse("2026-09-28T18:31:00Z")));
                panel.addMessage(new IrcMessage("#rshelp", "Ash", "Anyone need a hand?",
                        IrcMessage.MessageType.CHAT, Instant.now()));
                panel.addMessage(new IrcMessage("Luna", "Luna", "See you in chat!",
                        IrcMessage.MessageType.PRIVATE, Instant.now()));
                panel.inputField.setText("This is the same input field, just with more room.");
                panel.setDetached(true, false);
            });
            // Allow the HTML message documents and native window layout to finish updating.
            SwingUtilities.invokeAndWait(() -> {
                IrcPanel panel = ref.get();
                JFrame frame = (JFrame) SwingUtilities.getWindowAncestor(panel.getChatContent());
                frame.validate();
                JTree tree = (JTree) find(panel.getChatContent(), "ircChannels");
                JList<?> users = (JList<?>) find(panel.getChatContent(), "ircUsers");
                assertNotNull(tree);
                JComboBox<?> fontSize = (JComboBox<?>) find(panel.getChatContent(), "ircFontSize");
                assertEquals(12, fontSize.getSelectedItem());
                assertEquals(8, fontSize.getItemAt(0));
                assertEquals(32, fontSize.getItemAt(fontSize.getItemCount() - 1));
                assertEquals(5, users.getModel().getSize());
                assertEquals("#runelite", panel.getCurrentChannel());
                assertTrue(panel.isUnread("#rshelp"));
                JLabel topic = (JLabel) find(panel.getChatContent(), "ircTopic");
                String runeliteTopic = "Welcome to #runelite | Be nice | <html><b>not bold</b>";
                assertEquals("—  " + runeliteTopic, topic.getText());
                assertEquals(runeliteTopic, topic.getToolTipText());
                render(panel.getChatContent());

                tree.setSelectionPath(path(tree, "#rshelp"));
                assertEquals("", topic.getText());
                assertNull(topic.getToolTipText());
                panel.addMessage(new IrcMessage("#rshelp", IrcMessage.TOPIC_SENDER, "Questions welcome",
                        IrcMessage.MessageType.TOPIC, Instant.now()));
                assertEquals("—  Questions welcome", topic.getText());
                assertEquals("#rshelp", panel.getCurrentChannel());
                assertFalse(panel.isUnread("#rshelp"));
                assertEquals(0, users.getModel().getSize());
                tree.setSelectionPath(path(tree, "#runelite"));
                users.setSelectedIndex(0);
                users.getActionMap().get("query").actionPerformed(new ActionEvent(users, 0, "query"));
                assertEquals("Ash", panel.getCurrentChannel());
                assertNotNull(path(tree, "Ash"));
                panel.renameChannel("Ash", "Ash_away");
                assertNotNull(path(tree, "Ash_away"));
                panel.removeChannel("Ash_away");
                assertNull(path(tree, "Ash_away"));

                tree.setSelectionPath(path(tree, "#runelite"));
                panel.setChannelUsers("#runelite", Collections.singletonList(
                        new ChannelUserList.Entry("Luna", "+", 1)));
                assertEquals(1, users.getModel().getSize());
                for (int i = 0; i < 3; i++) {
                    panel.setDetached(false, false);
                    assertNull(find(panel.getChatContent(), "ircChannels"));
                    assertSame(panel.getChatContent(), panel.inputField.getParent());
                    panel.setDetached(true, false);
                    assertEquals("#runelite", panel.getCurrentChannel());
                    assertSame(tree, find(panel.getChatContent(), "ircChannels"));
                }
                assertEquals("This is the same input field, just with more room.", panel.inputField.getText());
                panel.inputField.postActionEvent();
                assertEquals("This is the same input field, just with more room.", sent.get());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (ref.get() != null) ref.get().shutdown();
                try { UIManager.setLookAndFeel(previous); }
                catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
            });
        }
    }

    @Test
    public void clickingChannelFocusesInputButKeyboardSelectionDoesNot() throws Exception {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            java.util.concurrent.atomic.AtomicInteger focusRequests = new java.util.concurrent.atomic.AtomicInteger();
            JTextField input = new JTextField() {
                @Override public boolean requestFocusInWindow() {
                    focusRequests.incrementAndGet();
                    return super.requestFocusInWindow();
                }
            };
            IrcDesktopLayout layout = new IrcDesktopLayout(name -> false, name -> {}, nick -> {}, nick -> {}, () -> {}, () -> {}, () -> {}, IrcDesktopLayout.NetworkActions.NONE, () -> {}, new JComboBox<>(), new JComboBox<>(), nick -> null);
            layout.attachChat(new JTabbedPane(), input);
            JTree tree = (JTree) find(layout, "ircChannels");
            layout.updateChannels(Collections.singletonList(swift(true, "System", "Luna", "#runelite", "#rshelp")),
                    BufferKey.swiftIrc("System"));
            assertEquals(Arrays.asList(BufferKey.swiftIrc("System"), BufferKey.swiftIrc("#runelite"),
                    BufferKey.swiftIrc("#rshelp"), BufferKey.swiftIrc("Luna")), layout.channelOrder());
            assertEquals("4. Luna", rowText(tree, "Luna"));
            tree.setSize(200, 400);

            tree.setSelectionPath(path(tree, "#rshelp"));
            assertEquals(0, focusRequests.get());

            Rectangle channelRow = tree.getPathBounds(path(tree, "#runelite"));
            release(tree, channelRow.x + 2, channelRow.y + channelRow.height / 2);
            assertEquals(1, focusRequests.get());

            Rectangle groupRow = tree.getPathBounds(path(tree, "Channels"));
            release(tree, groupRow.x + 2, groupRow.y + groupRow.height / 2);
            assertEquals(1, focusRequests.get());
        });
    }

    @Test
    public void toggleButtonsHideAndRestoreChannelAndUserLists() throws Exception {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            IrcDesktopLayout layout = new IrcDesktopLayout(name -> false, name -> {}, nick -> {}, nick -> {}, () -> {}, () -> {}, () -> {}, IrcDesktopLayout.NetworkActions.NONE, () -> {}, new JComboBox<>(), new JComboBox<>(), nick -> null);
            layout.attachChat(new JTabbedPane(), new JTextField());
            layout.setSize(960, 600);
            layout.validate();
            JToggleButton channelsToggle = (JToggleButton) find(layout, "ircToggleChannels");
            JToggleButton usersToggle = (JToggleButton) find(layout, "ircToggleUsers");
            JSplitPane all = (JSplitPane) SwingUtilities.getAncestorOfClass(JSplitPane.class,
                    SwingUtilities.getAncestorOfClass(JSplitPane.class, find(layout, "ircUsers")));
            Component channelSide = all.getLeftComponent();
            Component userSide = ((JSplitPane) all.getRightComponent()).getRightComponent();
            assertTrue(channelsToggle.isSelected());
            assertTrue(usersToggle.isSelected());
            assertTrue(channelSide.isVisible());
            assertTrue(userSide.isVisible());

            all.setDividerLocation(220);
            channelsToggle.doClick();
            usersToggle.doClick();
            layout.validate();
            assertFalse(channelSide.isVisible());
            assertFalse(userSide.isVisible());

            channelsToggle.doClick();
            usersToggle.doClick();
            layout.validate();
            assertTrue(channelSide.isVisible());
            assertTrue(userSide.isVisible());
            assertEquals(220, all.getDividerLocation());
        });
    }

    @Test
    public void eachNetworkIsARootAndNumberingRunsAcrossThem() throws Exception {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            java.util.List<String> calls = new java.util.ArrayList<>();
            IrcDesktopLayout layout = layout(calls);
            layout.attachChat(new JTabbedPane(), new JTextField());
            JTree tree = (JTree) find(layout, "ircChannels");
            layout.updateChannels(Arrays.asList(swift(true, "System", "#rshelp", "Luna"), rizon(false, "System", "#rshelp")),
                    BufferKey.swiftIrc("#rshelp"));
            assertFalse(tree.isRootVisible());
            assertEquals(2, ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildCount());
            assertEquals(Arrays.asList(BufferKey.swiftIrc("System"), BufferKey.swiftIrc("#rshelp"),
                    BufferKey.swiftIrc("Luna"), BufferKey.of("rizon-id", "System"), BufferKey.of("rizon-id", "#rshelp")),
                    layout.channelOrder());
            assertEquals("5. #rshelp", rowText(tree, path(tree, (Object) BufferKey.of("rizon-id", "#rshelp"))));
            assertEquals("\u25cf SwiftIRC", rowText(tree, path(tree, (Object) swift(true, "System", "#rshelp", "Luna"))));
            assertEquals("\u25cb Rizon  (disconnected)", rowText(tree, path(tree, (Object) rizon(false, "System", "#rshelp"))));

            tree.setSelectionPath(path(tree, (Object) BufferKey.of("rizon-id", "#rshelp")));
            assertEquals("select rizon-id #rshelp", calls.get(calls.size() - 1));
        });
    }

    @Test
    public void headerNamesTheNetworkOnlyWhenTwoAreConnected() throws Exception {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            IrcDesktopLayout layout = layout(new java.util.ArrayList<>());
            layout.attachChat(new JTabbedPane(), new JTextField());
            JLabel heading = (JLabel) find(layout, "ircChannelHeading");
            BufferKey foo = BufferKey.of("rizon-id", "#foo");
            layout.updateChannels(Arrays.asList(swift(true, "System"), rizon(false, "#foo")), foo);
            assertEquals("#foo", heading.getText());
            layout.updateChannels(Arrays.asList(swift(true, "System"), rizon(true, "#foo")), foo);
            assertEquals("#foo \u00b7 Rizon", heading.getText());
        });
    }

    @Test
    public void reconnectAndNetworksButtonsTargetTheSelectedNetwork() throws Exception {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            java.util.List<String> calls = new java.util.ArrayList<>();
            IrcDesktopLayout layout = layout(calls);
            layout.attachChat(new JTabbedPane(), new JTextField());
            layout.updateChannels(Arrays.asList(swift(true, "System"), rizon(true, "#foo")), BufferKey.of("rizon-id", "#foo"));
            ((JButton) find(layout, "ircReconnect")).doClick();
            ((JButton) find(layout, "ircNetworks")).doClick();
            assertEquals(Arrays.asList("reconnect rizon-id", "edit null"), calls.subList(calls.size() - 2, calls.size()));
        });
    }

    @Test
    public void networkMenuReconnectsTogglesAndEdits() throws Exception {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            java.util.List<String> calls = new java.util.ArrayList<>();
            IrcDesktopLayout layout = layout(calls);
            JPopupMenu menu = layout.networkMenu(rizon(true, "#foo"));
            assertEquals(3, menu.getComponentCount());
            assertEquals("Reconnect", ((JMenuItem) menu.getComponent(0)).getText());
            assertEquals("Disconnect", ((JMenuItem) menu.getComponent(1)).getText());
            assertEquals("Edit\u2026", ((JMenuItem) menu.getComponent(2)).getText());
            for (int i = 0; i < 3; i++) ((JMenuItem) menu.getComponent(i)).doClick();
            assertEquals(Arrays.asList("reconnect rizon-id", "connected rizon-id false", "edit rizon-id"), calls);
            assertEquals("Connect", ((JMenuItem) layout.networkMenu(rizon(false)).getComponent(1)).getText());
        });
    }

    private static String rowText(JTree tree, TreePath path) {
        JLabel label = (JLabel) tree.getCellRenderer().getTreeCellRendererComponent(
                tree, path.getLastPathComponent(), false, false, true, tree.getRowForPath(path), false);
        return label.getText();
    }

    private static String rowText(JTree tree, String name) {
        return rowText(tree, path(tree, name));
    }

    private static IrcDesktopLayout layout(java.util.List<String> calls) {
        return new IrcDesktopLayout(key -> false, key -> calls.add("select " + key.getNetworkId() + " " + key),
                nick -> {}, nick -> {}, () -> {}, () -> {}, () -> {},
                new IrcDesktopLayout.NetworkActions() {
                    @Override public void reconnect(String id) { calls.add("reconnect " + id); }
                    @Override public void setConnected(String id, boolean c) { calls.add("connected " + id + " " + c); }
                    @Override public void edit(String id) { calls.add("edit " + id); }
                },
                () -> {}, new JComboBox<>(), new JComboBox<>(), nick -> null);
    }

    private static IrcDesktopLayout.NetworkNode swift(boolean connected, String... buffers) {
        return new IrcDesktopLayout.NetworkNode("swiftirc", "SwiftIRC", connected, Arrays.asList(buffers));
    }

    private static IrcDesktopLayout.NetworkNode rizon(boolean connected, String... buffers) {
        return new IrcDesktopLayout.NetworkNode("rizon-id", "Rizon", connected, Arrays.asList(buffers));
    }

    private static void release(JTree tree, int x, int y) {
        tree.dispatchEvent(new java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(), java.awt.event.InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false,
                java.awt.event.MouseEvent.BUTTON1));
    }

    private static Component find(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container) {
                Component found = find((Container) child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TreePath path(JTree tree, String name) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        java.util.Enumeration<?> nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
            if (name.equals(String.valueOf(node.getUserObject()))) return new TreePath(node.getPath());
        }
        return null;
    }

    private static TreePath path(JTree tree, Object userObject) {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        java.util.Enumeration<?> nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
            if (userObject.equals(node.getUserObject())) return new TreePath(node.getPath());
        }
        return null;
    }

    private static void render(JPanel content) {
        BufferedImage image = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        content.printAll(graphics);
        graphics.dispose();
        try {
            File target = new File("build/previews/irc-desktop.png");
            target.getParentFile().mkdirs();
            ImageIO.write(image, "png", target);
        } catch (java.io.IOException e) { throw new AssertionError(e); }
    }
}
