package com.irc;

import org.junit.Test;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.text.Document;
import java.awt.Font;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * In the pop-out, a run of joins, parts, quits, kicks and nick changes is shown as one line
 * grouped by kind, so a netsplit or a channel of hoppers doesn't bury the conversation. The side
 * panel keeps one line per event.
 *
 * The lines are built exactly as IrcAdapter builds them, so a change to that wording breaks
 * these tests rather than silently ungrouping the pop-out.
 */
public class IrcPanelCompactPresenceTest {

    private static final IrcConfig NO_TIMESTAMPS = new IrcConfig() {
        @Override public String username() { return "tester"; }
        @Override public String password() { return ""; }
        @Override public boolean timestamp() { return false; }
    };

    private static final IrcConfig TIMESTAMPS = new IrcConfig() {
        @Override public String username() { return "tester"; }
        @Override public String password() { return ""; }
    };

    // --- the messages IrcAdapter produces ---

    private static IrcMessage join(String nick) {
        return new IrcMessage("#c", "*", nick + " has joined.", IrcMessage.MessageType.JOIN, Instant.now());
    }

    private static IrcMessage part(String nick, String reason) {
        return new IrcMessage("#c", nick + " parted", reason, IrcMessage.MessageType.PART, Instant.now());
    }

    private static IrcMessage quit(String nick, String reason) {
        return new IrcMessage("#c", nick + " quit", reason, IrcMessage.MessageType.QUIT, Instant.now());
    }

    private static IrcMessage nick(String oldNick, String newNick) {
        return new IrcMessage("#c", oldNick + " is now known as", newNick, IrcMessage.MessageType.NICK_CHANGE, Instant.now());
    }

    private static IrcMessage kick(String by, String nick, String reason) {
        return new IrcMessage("#c", by + " kicked " + nick, reason, IrcMessage.MessageType.KICK, Instant.now());
    }

    private static IrcMessage chat(String nick, String text) {
        return new IrcMessage("#c", nick, text, IrcMessage.MessageType.CHAT, Instant.now());
    }

    /** The pane's visible text once every queued render has run, one line per paragraph. */
    private static String render(IrcConfig config, boolean compact, IrcMessage... messages) throws Exception {
        AtomicReference<IrcPanel.ChannelPane> paneRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IrcPanel.ChannelPane pane = new IrcPanel.ChannelPane(new Font("SansSerif", Font.PLAIN, 12), config, null);
            pane.setCompactPresence(compact);
            for (IrcMessage message : messages) pane.appendMessage(message, config);
            paneRef.set(pane);
        });
        return textOf(paneRef.get());
    }

    private static String textOf(IrcPanel.ChannelPane pane) throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        AtomicReference<String> text = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                Document doc = pane.getDocument();
                // Swing's HTML document keeps a non-breaking space after "sender:"; read it as a space.
                text.set(doc.getText(0, doc.getLength()).replace(' ', ' ').trim());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return text.get();
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    @Test
    public void groupsARunIntoOneLineByKind() throws Exception {
        String text = render(NO_TIMESTAMPS, true,
                join("alice"), quit("bob", "Ping timeout"), part("carol", "bye"),
                nick("dave", "dave_"), kick("eve", "frank", "spam"), join("gina"));
        assertEquals("* Joins: alice, gina · Parts: carol · Quits: bob · "
                + "Nicks: dave → dave_ · Kicks: frank (by eve)", text);
    }

    @Test
    public void leavesOutEmptyGroups() throws Exception {
        String text = render(NO_TIMESTAMPS, true, quit("bob", "x"), quit("carol", "y"));
        assertEquals("* Quits: bob, carol", text);
    }

    @Test
    public void aHopperShowsOncePerGroup() throws Exception {
        String text = render(NO_TIMESTAMPS, true,
                join("alice"), part("alice", " "), join("alice"), part("alice", " "), join("ALICE"));
        assertEquals("* Joins: alice · Parts: alice", text);
    }

    @Test
    public void everyNickChangeIsListed() throws Exception {
        String text = render(NO_TIMESTAMPS, true, nick("dave", "dave_"), nick("dave_", "dave"));
        assertEquals("* Nicks: dave → dave_, dave_ → dave", text);
    }

    @Test
    public void aNickKickedTwiceShowsTheLatestKicker() throws Exception {
        String text = render(NO_TIMESTAMPS, true,
                kick("eve", "frank", "a"), join("frank"), kick("zed", "frank", "b"));
        assertEquals("* Joins: frank · Kicks: frank (by zed)", text);
    }

    @Test
    public void aSingleEventKeepsItsNormalLine() throws Exception {
        String text = render(NO_TIMESTAMPS, true, chat("bob", "hi"), quit("carol", "Ping timeout"), chat("bob", "bye"));
        assertEquals("bob: hi\ncarol quit: Ping timeout\nbob: bye", text);
    }

    @Test
    public void otherLinesEndTheRun() throws Exception {
        String text = render(NO_TIMESTAMPS, true,
                join("a"), join("b"), chat("bob", "hi"), join("c"), join("d"));
        assertEquals("* Joins: a, b\nbob: hi\n* Joins: c, d", text);
    }

    @Test
    public void theLineIsStampedWhenTheRunStarted() throws Exception {
        Instant start = Instant.parse("2026-10-06T12:01:03Z");
        IrcMessage first = new IrcMessage("#c", "*", "a has joined.", IrcMessage.MessageType.JOIN, start);
        IrcMessage second = new IrcMessage("#c", "*", "b has joined.", IrcMessage.MessageType.JOIN, start.plusSeconds(90));
        String stamp = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(start);
        assertEquals("[" + stamp + "] * Joins: a, b", render(TIMESTAMPS, true, first, second));
    }

    @Test
    public void theSidePanelKeepsOneLinePerEvent() throws Exception {
        String text = render(NO_TIMESTAMPS, false, join("alice"), quit("bob", "Ping timeout"));
        assertEquals("*: alice has joined.\nbob quit: Ping timeout", text);
    }

    @Test
    public void switchingModesRerendersTheScrollback() throws Exception {
        AtomicReference<IrcPanel.ChannelPane> paneRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IrcPanel.ChannelPane pane = new IrcPanel.ChannelPane(new Font("SansSerif", Font.PLAIN, 12), NO_TIMESTAMPS, null);
            pane.appendMessage(join("alice"), NO_TIMESTAMPS);
            pane.appendMessage(join("bob"), NO_TIMESTAMPS);
            paneRef.set(pane);
        });
        IrcPanel.ChannelPane pane = paneRef.get();
        assertEquals("*: alice has joined.\n*: bob has joined.", textOf(pane));

        SwingUtilities.invokeAndWait(() -> pane.setCompactPresence(true));
        assertEquals("* Joins: alice, bob", textOf(pane));

        SwingUtilities.invokeAndWait(() -> pane.setCompactPresence(false));
        assertEquals("*: alice has joined.\n*: bob has joined.", textOf(pane));
    }

    @Test
    public void scrollbackStillCountsEachEvent() throws Exception {
        IrcConfig small = new IrcConfig() {
            @Override public String username() { return "tester"; }
            @Override public String password() { return ""; }
            @Override public boolean timestamp() { return false; }
            @Override public int getMaxScrollback() { return 3; }
        };
        String text = render(small, true, join("a"), join("b"), join("c"), join("d"), join("e"));
        assertEquals("* Joins: c, d, e", text);
    }

    /** Popping out compacts every open pane, and panes opened while popped out; docking undoes it. */
    @Test
    public void thePopOutTurnsCompactingOnForItsPanes() throws Exception {
        org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        AtomicReference<IrcPanel> panelRef = new AtomicReference<>();
        AtomicReference<JFrame> mainRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IrcPanel panel = new IrcPanel();
            panelRef.set(panel);
            setField(panel, "config", NO_TIMESTAMPS);
            panel.init((channel, text) -> {}, (network, channel, password) -> {}, channel -> {},
                    reconnect -> {}, (network, query) -> {}, () -> {});
            panel.initializeGui();
            JFrame main = new JFrame();
            mainRef.set(main);
            main.add(panel.getWrappedPanel());
            main.setSize(260, 600);
            main.setVisible(true);
            panel.addChannel("#c");
        });
        try {
            IrcPanel panel = panelRef.get();
            SwingUtilities.invokeAndWait(() -> {
                panel.addMessage(join("alice"));
                panel.addMessage(join("bob"));
            });
            assertEquals(2, count(textOf(panel.getPane("#c")), "has joined."));

            SwingUtilities.invokeAndWait(() -> {
                panel.setDetached(true, false);
                panel.addChannel("#later");
                panel.addMessage(new IrcMessage("#later", "*", "x has joined.", IrcMessage.MessageType.JOIN, Instant.now()));
                panel.addMessage(new IrcMessage("#later", "*", "y has joined.", IrcMessage.MessageType.JOIN, Instant.now()));
            });
            assertTrue(textOf(panel.getPane("#c")).contains("* Joins: alice, bob"));
            assertTrue(textOf(panel.getPane("#later")).contains("* Joins: x, y"));

            SwingUtilities.invokeAndWait(() -> panel.setDetached(false, false));
            assertFalse(textOf(panel.getPane("#c")).contains("Joins:"));
            assertFalse(textOf(panel.getPane("#later")).contains("Joins:"));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                panelRef.get().setDetached(false, false);
                mainRef.get().dispose();
            });
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = IrcPanel.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
