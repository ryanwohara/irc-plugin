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
                panel.init((channel, text) -> sent.set(text), (channel, password) -> {},
                        channel -> {}, reconnect -> {}, query -> {}, () -> {});
                panel.initializeGui();
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
                assertEquals(5, users.getModel().getSize());
                assertEquals("#runelite", panel.getCurrentChannel());
                assertTrue(panel.unreadMessages.get("#rshelp"));
                render(panel.getChatContent());

                tree.setSelectionPath(path(tree, "#rshelp"));
                assertEquals("#rshelp", panel.getCurrentChannel());
                assertFalse(panel.unreadMessages.get("#rshelp"));
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
            IrcDesktopLayout layout = new IrcDesktopLayout("irc.example", name -> false, name -> {},
                    nick -> {}, nick -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {},
                    new JComboBox<>(), nick -> null);
            layout.attachChat(new JTabbedPane(), input);
            JTree tree = (JTree) find(layout, "ircChannels");
            layout.updateChannels(Arrays.asList("System", "Luna", "#runelite", "#rshelp"), "System");
            assertEquals(Arrays.asList("System", "#runelite", "#rshelp", "Luna"), layout.channelOrder());
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
            IrcDesktopLayout layout = new IrcDesktopLayout("irc.example", name -> false, name -> {},
                    nick -> {}, nick -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {},
                    new JComboBox<>(), nick -> null);
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

    private static String rowText(JTree tree, String name) {
        TreePath path = path(tree, name);
        JLabel label = (JLabel) tree.getCellRenderer().getTreeCellRendererComponent(
                tree, path.getLastPathComponent(), false, false, true, tree.getRowForPath(path), false);
        return label.getText();
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
            if (name.equals(node.getUserObject())) return new TreePath(node.getPath());
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
