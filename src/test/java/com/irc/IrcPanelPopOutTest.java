package com.irc;

import org.junit.Test;
import org.junit.Before;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class IrcPanelPopOutTest {
    @Before
    public void requiresDesktop() {
        org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
    }

    @Test
    public void actualChatSendsInBothHostsAndBrowserFollowsItsOwner() throws Exception {
        AtomicReference<IrcPanel> panelRef = new AtomicReference<>();
        AtomicReference<JFrame> mainRef = new AtomicReference<>();
        AtomicReference<String> sent = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IrcPanel panel = new IrcPanel();
            panelRef.set(panel);
            setField(panel, "config", new IrcConfig() {
                @Override public String username() { return "tester"; }
                @Override public String password() { return ""; }
            });
            panel.init((channel, text) -> sent.set(channel + ":" + text),
                    (channel, password) -> {}, channel -> {}, reconnect -> {}, query -> {}, () -> {});
            panel.initializeGui();
            JFrame main = new JFrame();
            mainRef.set(main);
            main.add(panel.getWrappedPanel());
            main.setSize(260, 600);
            main.setVisible(true);
            panel.addChannel("#test");
            panel.setFocusedChannel("#test");
            panel.addMessage(new IrcMessage("#test", "tester", "test scrollback",
                    IrcMessage.MessageType.CHAT, Instant.now()));
        });
        try {
            SwingUtilities.invokeAndWait(() -> {
                IrcPanel panel = panelRef.get();
                // initializeGui queues the System tab; select after that startup work completes.
                panel.setFocusedChannel("#test");
                IrcPanel.ChannelPane original = panel.getChannelPanes().get("#test");
                assertTrue(original.getText().contains("test scrollback"));
                panel.inputField.setText("my draft");
                panel.showChannelList(Collections.emptyList(), "", false);
                ChannelListDialog dockedBrowser = getBrowser(panel);
                assertSame(mainRef.get(), dockedBrowser.getOwner());

                panel.setDetached(true, false);
                JFrame popOut = (JFrame) SwingUtilities.getWindowAncestor(panel.getChatContent());
                assertNotSame(mainRef.get(), popOut);
                assertTrue(popOut.isVisible());
                assertFalse(dockedBrowser.isDisplayable());
                assertEquals("my draft", panel.inputField.getText());
                assertEquals("#test", panel.getCurrentChannel());
                assertSame(original, panel.getChannelPanes().get("#test"));
                panel.inputField.postActionEvent();
                assertEquals("#test:my draft", sent.get());
                assertEquals("", panel.inputField.getText());

                panel.showChannelList(Collections.emptyList(), "", false);
                ChannelListDialog floatingBrowser = getBrowser(panel);
                assertSame(popOut, floatingBrowser.getOwner());
                panel.setDetached(false, false);
                assertFalse(floatingBrowser.isDisplayable());
                assertFalse(popOut.isDisplayable());
                assertSame(mainRef.get(), SwingUtilities.getWindowAncestor(panel.getChatContent()));
                panel.inputField.setText("docked message");
                panel.inputField.postActionEvent();
                assertEquals("#test:docked message", sent.get());
                assertTrue(original.getText().contains("test scrollback"));
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                panelRef.get().shutdown();
                mainRef.get().dispose();
            });
        }
    }

    private static void setField(IrcPanel panel, String name, Object value) {
        try {
            Field field = IrcPanel.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(panel, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static ChannelListDialog getBrowser(IrcPanel panel) {
        try {
            Field field = IrcPanel.class.getDeclaredField("channelListDialog");
            field.setAccessible(true);
            return (ChannelListDialog) field.get(panel);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
