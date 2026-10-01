package com.irc;

import org.junit.Test;

import javax.swing.JTextPane;
import javax.swing.text.AttributeSet;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.ElementIterator;
import javax.swing.text.html.HTMLDocument;
import java.util.Enumeration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * IRC color codes are {@code <fg>,<bg>}; the panel now renders the background too.
 * colorSpan builds the HTML run: foreground is always applied (via the 0-98 palette, falling
 * back to black like a normal color), while a background is applied ONLY for a real palette
 * code 0-98 - code 99 ("default background") and anything out of range render with no
 * background so the theme shows through.
 */
public class IrcBackgroundColorTest {

    @Test
    public void foregroundOnlyWhenNoBackground() {
        assertEquals("<span style=\"color:#FF0000\">hi</span>",
                IrcPanel.ChannelPane.colorSpan("4", null, "hi"));
    }

    @Test
    public void foregroundAndBackground() {
        assertEquals("<span style=\"color:#FF0000;background-color:#FFFF00\">hi</span>",
                IrcPanel.ChannelPane.colorSpan("4", "8", "hi"));
    }

    @Test
    public void extendedPaletteBackground() {
        // Foreground 0 is the classic palette's named "white"; background 16 is extended-palette hex.
        assertEquals("<span style=\"color:white;background-color:#470000\">x</span>",
                IrcPanel.ChannelPane.colorSpan("0", "16", "x"));
    }

    @Test
    public void defaultBackground99RendersNoBackground() {
        assertEquals("<span style=\"color:#FF0000\">hi</span>",
                IrcPanel.ChannelPane.colorSpan("4", "99", "hi"));
    }

    @Test
    public void invalidBackgroundRendersNoBackground() {
        assertEquals("<span style=\"color:#FF0000\">hi</span>",
                IrcPanel.ChannelPane.colorSpan("4", "notacode", "hi"));
    }

    @Test
    public void backgroundRunUsesNonBreakingSpacesSoTheyDoNotCollapse() {
        // Swing collapses runs of regular whitespace, so a colored space would lose its block.
        assertEquals("<span style=\"color:#FF0000;background-color:#FFFF00\">a&nbsp;&nbsp;b</span>",
                IrcPanel.ChannelPane.colorSpan("4", "8", "a  b"));
    }

    @Test
    public void foregroundOnlyRunKeepsRegularSpaces() {
        // No background means no colored block to preserve; keep normal spaces so text still wraps.
        assertEquals("<span style=\"color:#FF0000\">a  b</span>",
                IrcPanel.ChannelPane.colorSpan("4", null, "a  b"));
    }

    /**
     * Root-cause evidence: Swing's HTML view collapses consecutive regular spaces but keeps
     * non-breaking spaces - which is exactly why a background-colored space needs {@code &nbsp;}.
     */
    @Test
    public void swingCollapsesRegularSpacesButPreservesNonBreakingSpaces() throws Exception {
        int collapsed = bodyText("<span>a   b</span>").trim().length();
        int preserved = bodyText("<span>a&nbsp;&nbsp;&nbsp;b</span>").trim().length();
        assertTrue("regular spaces should collapse (" + collapsed + " chars)", collapsed < preserved);
        assertEquals("nbsp run keeps a + 3 spaces + b", 5, preserved);
    }

    private static String bodyText(String spanHtml) throws Exception {
        JTextPane pane = new JTextPane();
        pane.setContentType("text/html");
        pane.setText("<html><body>" + spanHtml + "</body></html>");
        Document doc = pane.getDocument();
        return doc.getText(0, doc.getLength());
    }

    /**
     * Proves Swing's HTML renderer actually parses an inline background-color onto the text run
     * (not just that we emit the markup). If this fails, Approach A doesn't render and we'd need
     * to set StyledDocument attributes directly instead.
     */
    @Test
    public void swingHtmlRendererParsesInlineBackground() throws Exception {
        JTextPane pane = new JTextPane();
        pane.setContentType("text/html");
        pane.setText("<html><body>" + IrcPanel.ChannelPane.colorSpan("4", "8", "hi") + "</body></html>");

        Object background = backgroundAttributeFor((HTMLDocument) pane.getDocument(), "hi");
        assertNotNull("Swing must parse background-color onto the run", background);
        assertTrue("background should be the yellow we asked for, was: " + background,
                background.toString().toLowerCase().contains("ffff00"));
    }

    private static Object backgroundAttributeFor(HTMLDocument doc, String text) throws Exception {
        ElementIterator it = new ElementIterator(doc);
        for (Element el = it.next(); el != null; el = it.next()) {
            if (!el.isLeaf()) {
                continue;
            }
            String content = doc.getText(el.getStartOffset(), el.getEndOffset() - el.getStartOffset());
            if (!content.contains(text)) {
                continue;
            }
            AttributeSet attrs = el.getAttributes();
            for (Enumeration<?> names = attrs.getAttributeNames(); names.hasMoreElements(); ) {
                Object key = names.nextElement();
                if ("background-color".equals(key.toString())) {
                    return attrs.getAttribute(key);
                }
            }
        }
        return null;
    }
}
