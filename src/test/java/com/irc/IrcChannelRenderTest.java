package com.irc;

import org.junit.Test;

import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Font;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;

/**
 * A burst of lines - a ZNC replaying its backlog on attach - must not queue one full HTML
 * re-render per line. Each render re-parses the whole scrollback on the EDT, so hundreds of them
 * froze the window for minutes.
 */
public class IrcChannelRenderTest {

    @Test
    public void aBurstOfLinesIsRenderedOnce() throws Exception {
        IrcConfig config = new IrcConfig() {
            @Override public String username() { return "tester"; }
            @Override public String password() { return ""; }
        };
        AtomicReference<IrcPanel.ChannelPane> paneRef = new AtomicReference<>();
        AtomicInteger renders = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            IrcPanel.ChannelPane pane = new IrcPanel.ChannelPane(new Font("SansSerif", Font.PLAIN, 12), config, null);
            // Every render replaces the whole text, which removes the old content once.
            pane.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void removeUpdate(DocumentEvent e) { renders.incrementAndGet(); }
                @Override public void insertUpdate(DocumentEvent e) { }
                @Override public void changedUpdate(DocumentEvent e) { }
            });
            for (int i = 0; i < 300; i++) {
                pane.appendMessage(new IrcMessage("#busy", "bob", "replayed line " + i,
                        IrcMessage.MessageType.HISTORY, Instant.now()), config);
            }
            paneRef.set(pane);
        });
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });

        String text = paneRef.get().getDocument().getText(0, paneRef.get().getDocument().getLength());
        assertTrue("the newest line is shown", text.contains("replayed line 299"));
        assertTrue("300 lines must not mean 300 renders, got " + renders.get(), renders.get() <= 2);
    }
}
