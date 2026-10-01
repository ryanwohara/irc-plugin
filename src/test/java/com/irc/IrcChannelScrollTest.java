package com.irc;

import org.junit.Test;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;

/** A channel that fills up while its tab is hidden should open scrolled to the newest message. */
public class IrcChannelScrollTest {

    @Test
    public void backgroundChannelOpensAtTheBottom() throws Exception {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        IrcConfig config = new IrcConfig() {
            @Override public String username() { return "tester"; }
            @Override public String password() { return ""; }
        };
        AtomicReference<JFrame> frame = new AtomicReference<>();
        AtomicReference<JTabbedPane> tabs = new AtomicReference<>();
        AtomicReference<JScrollPane> scroll = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                IrcPanel.ChannelPane system = new IrcPanel.ChannelPane(new Font("SansSerif", Font.PLAIN, 12), config, null);
                IrcPanel.ChannelPane pane = new IrcPanel.ChannelPane(new Font("SansSerif", Font.PLAIN, 12), config, null);
                JTabbedPane tabbedPane = new JTabbedPane();
                tabbedPane.addTab("System", new JScrollPane(system));
                JScrollPane scrollPane = new JScrollPane(pane);
                tabbedPane.addTab("#busy", scrollPane);
                JFrame f = new JFrame();
                f.add(tabbedPane);
                f.setSize(400, 300);
                for (int i = 0; i < 80; i++) {
                    pane.appendMessage(new IrcMessage("#busy", "bob", "message " + i,
                            IrcMessage.MessageType.CHAT, Instant.now()), config);
                }
                frame.set(f);
                tabs.set(tabbedPane);
                scroll.set(scrollPane);
            });
            flush();
            // The chat may not be on screen at all while messages arrive (e.g. another sidebar panel is open).
            SwingUtilities.invokeAndWait(() -> frame.get().setVisible(true));
            flush();
            SwingUtilities.invokeAndWait(() -> tabs.get().setSelectedIndex(1));
            flush();
            SwingUtilities.invokeAndWait(() -> {
                JScrollBar bar = scroll.get().getVerticalScrollBar();
                assertEquals(bar.getMaximum(), bar.getValue() + bar.getVisibleAmount());
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (frame.get() != null) frame.get().dispose(); });
        }
    }

    // Rendering and scrolling are queued with invokeLater, sometimes more than one deep.
    private static void flush() throws Exception {
        for (int i = 0; i < 5; i++) SwingUtilities.invokeAndWait(() -> {});
    }
}
