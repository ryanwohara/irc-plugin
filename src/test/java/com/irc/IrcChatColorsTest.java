package com.irc;

import org.junit.Test;

import javax.swing.SwingUtilities;
import javax.swing.text.View;
import javax.swing.text.html.HTMLDocument;
import java.awt.Color;
import java.awt.Font;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * The chat background and default text colour are user settings. Plain chat lines carry no
 * colour of their own and inherit the body's, so changing the setting recolours scrollback too.
 */
public class IrcChatColorsTest {

    private static IrcConfig config(Color background, Color text) {
        return new IrcConfig() {
            @Override public String username() { return "tester"; }
            @Override public String password() { return ""; }
            @Override public Color chatBackgroundColor() { return background; }
            @Override public Color chatTextColor() { return text; }
            @Override public boolean colorizedNicks() { return false; }
        };
    }

    private static IrcPanel.ChannelPane renderedPane(IrcConfig config) throws Exception {
        AtomicReference<IrcPanel.ChannelPane> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IrcPanel.ChannelPane pane = new IrcPanel.ChannelPane(new Font("SansSerif", Font.PLAIN, 12), config, null);
            pane.appendMessage(new IrcMessage("#test", "bob", "hello",
                    IrcMessage.MessageType.CHAT, Instant.now()), config);
            ref.set(pane);
        });
        // appendMessage renders via invokeLater; wait for it.
        SwingUtilities.invokeAndWait(() -> {});
        return ref.get();
    }

    @Test
    public void defaultsMatchTheExistingTheme() {
        IrcConfig defaults = new IrcConfig() {
            @Override public String username() { return "tester"; }
            @Override public String password() { return ""; }
        };
        assertEquals(new Color(30, 30, 30), defaults.chatBackgroundColor());
        assertEquals(new Color(165, 165, 165), defaults.chatTextColor());
    }

    @Test
    public void paneUsesConfiguredBackground() throws Exception {
        IrcPanel.ChannelPane pane = renderedPane(config(new Color(0x10, 0x20, 0x30), Color.WHITE));
        assertEquals(new Color(0x10, 0x20, 0x30), pane.getBackground());
    }

    @Test
    public void bodyUsesConfiguredTextColor() throws Exception {
        IrcPanel.ChannelPane pane = renderedPane(config(Color.BLACK, new Color(0xAB, 0xCD, 0xEF)));
        assertEquals(new Color(0xAB, 0xCD, 0xEF), renderedForeground(pane, "hello"));
    }

    @Test
    public void plainChatLinesInheritTheBodyColor() throws Exception {
        IrcPanel.ChannelPane pane = renderedPane(config(Color.BLACK, new Color(0xAB, 0xCD, 0xEF)));
        // The old hard-coded light gray must not be baked into the line.
        assertFalse(pane.getText().toLowerCase().contains("#a5a5a5"));
    }

    @Test
    public void changingTheSettingRecoloursScrollback() throws Exception {
        AtomicReference<Color> text = new AtomicReference<>(Color.RED);
        AtomicReference<Color> background = new AtomicReference<>(Color.BLACK);
        IrcConfig live = new IrcConfig() {
            @Override public String username() { return "tester"; }
            @Override public String password() { return ""; }
            @Override public Color chatBackgroundColor() { return background.get(); }
            @Override public Color chatTextColor() { return text.get(); }
        };
        IrcPanel.ChannelPane pane = renderedPane(live);
        text.set(new Color(0x12, 0x34, 0x56));
        background.set(new Color(0x65, 0x43, 0x21));
        SwingUtilities.invokeAndWait(pane::applyColors);
        assertEquals(new Color(0x12, 0x34, 0x56), renderedForeground(pane, "hello"));
        assertEquals(new Color(0x65, 0x43, 0x21), pane.getBackground());
    }

    /** The colour Swing will actually paint {@code text} in, with CSS inheritance resolved by the view tree. */
    private static Color renderedForeground(IrcPanel.ChannelPane pane, String text) throws Exception {
        AtomicReference<Color> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            pane.setSize(400, 400);
            HTMLDocument doc = (HTMLDocument) pane.getDocument();
            View leaf = findLeaf(pane.getUI().getRootView(pane), doc, text);
            if (leaf != null) {
                result.set(doc.getStyleSheet().getForeground(leaf.getAttributes()));
            }
        });
        return result.get();
    }

    private static View findLeaf(View view, HTMLDocument doc, String text) {
        if (view.getViewCount() == 0) {
            try {
                int start = view.getStartOffset();
                return doc.getText(start, view.getEndOffset() - start).contains(text) ? view : null;
            } catch (Exception e) {
                return null;
            }
        }
        for (int i = 0; i < view.getViewCount(); i++) {
            View found = findLeaf(view.getView(i), doc, text);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
