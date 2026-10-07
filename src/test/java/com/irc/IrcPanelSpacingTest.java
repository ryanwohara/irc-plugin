package com.irc;

import org.junit.Test;

import javax.swing.SwingUtilities;
import javax.swing.text.Document;
import java.awt.Font;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * HTML collapses a run of spaces to one, which flattened ASCII art and aligned text. Every space
 * in a run but the last becomes a no-break space, so the spacing survives and the trailing plain
 * space still lets a long line wrap.
 */
public class IrcPanelSpacingTest {

    private static final IrcConfig CONFIG = new IrcConfig() {
        @Override public String username() { return "tester"; }
        @Override public String password() { return ""; }
        @Override public boolean timestamp() { return false; }
        @Override public boolean colorizedNicks() { return false; }
    };

    /** The pane's visible text for one chat line, once its render has run. */
    private static String rendered(String content) throws Exception {
        AtomicReference<IrcPanel.ChannelPane> paneRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IrcPanel.ChannelPane pane = new IrcPanel.ChannelPane(new Font("SansSerif", Font.PLAIN, 12), CONFIG, null);
            pane.appendMessage(new IrcMessage("#c", "bob", content, IrcMessage.MessageType.CHAT, Instant.now()), CONFIG);
            paneRef.set(pane);
        });
        SwingUtilities.invokeAndWait(() -> { });
        AtomicReference<String> text = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                Document doc = paneRef.get().getDocument();
                text.set(doc.getText(0, doc.getLength()));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return text.get();
    }

    @Test
    public void aRunOfSpacesKeepsItsWidth() throws Exception {
        assertTrue(rendered("a   b").contains("a   b"));
    }

    @Test
    public void singleSpacesAreUntouched() {
        assertEquals("one two three", IrcPanel.ChannelPane.keepSpaceRuns("one two three"));
    }

    /** Every run ends in a plain space, so there is still somewhere to wrap. */
    @Test
    public void everyRunStillEndsInABreakableSpace() {
        assertEquals("a&nbsp;&nbsp; b&nbsp; c", IrcPanel.ChannelPane.keepSpaceRuns("a   b  c"));
    }

    @Test
    public void tagsAreLeftAlone() {
        String html = "<font style=\"color:red\">x  y</font>  <a href=\"https://e.com\">https://e.com</a>";
        assertEquals("<font style=\"color:red\">x&nbsp; y</font>&nbsp; <a href=\"https://e.com\">https://e.com</a>",
                IrcPanel.ChannelPane.keepSpaceRuns(html));
    }

    @Test
    public void aLinkBeforeARunStopsAtTheUrl() throws Exception {
        AtomicReference<IrcPanel.ChannelPane> paneRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IrcPanel.ChannelPane pane = new IrcPanel.ChannelPane(new Font("SansSerif", Font.PLAIN, 12), CONFIG, null);
            pane.appendMessage(new IrcMessage("#c", "bob", "see https://example.com/x   next",
                    IrcMessage.MessageType.CHAT, Instant.now()), CONFIG);
            paneRef.set(pane);
        });
        SwingUtilities.invokeAndWait(() -> { });
        AtomicReference<String> html = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> html.set(paneRef.get().getText()));
        assertTrue(html.get(), html.get().contains("href=\"https://example.com/x\""));
        assertTrue(rendered("see https://example.com/x   next").contains("https://example.com/x   next"));
    }

    @Test
    public void runsNextToColorCodesKeepTheirWidth() throws Exception {
        String text = rendered("\u000304red\u000f   plain");
        assertTrue(text, text.contains("red   plain"));
    }
}
