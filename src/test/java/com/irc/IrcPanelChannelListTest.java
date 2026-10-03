package com.irc;

import org.junit.Test;

import javax.swing.JTabbedPane;
import javax.swing.Timer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The panel's side of the channel browser: firing the request and policing the response window.
 *
 * A LIST that never terminates would otherwise leave the user staring at nothing, so the request
 * arms a timer that must be cancelled by any outcome - success or an explicit failure. A timer
 * left running fires a "no response" message on top of a list that did arrive.
 *
 * The dialog itself is not built here: constructing a Window throws HeadlessException on a
 * headless machine, so showChannelList's dialog handling is covered by the manual smoke test.
 */
public class IrcPanelChannelListTest {

    private static IrcPanel headlessPanel() throws Exception {
        IrcPanel panel = new IrcPanel();
        set(panel, "config", stubConfig());
        set(panel, "tabbedPane", new JTabbedPane());
        panel.addChannel("System");
        panel.setFocusedChannel("System");
        return panel;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = IrcPanel.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object get(Object target, String field) throws Exception {
        Field f = IrcPanel.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }

    /**
     * IrcConfig is an interface whose methods are nearly all defaults, so only the two abstract
     * ones need overriding. Same shape as the stub in IrcPanelNickListTest.
     */
    private static IrcConfig stubConfig() {
        return new IrcConfig() {
            @Override
            public String username() {
                return "tester";
            }

            @Override
            public String password() {
                return "";
            }
        };
    }

    @Test
    public void requestChannelListForwardsTheQuery() throws Exception {
        IrcPanel panel = headlessPanel();
        List<String> requested = new ArrayList<>();
        panel.init(null, null, null, null, (network, query) -> requested.add(query), null);

        panel.requestChannelList(">50");

        assertEquals(1, requested.size());
        assertEquals(">50", requested.get(0));
    }

    @Test
    public void requestChannelListIsSafeWithoutACallback() throws Exception {
        IrcPanel panel = headlessPanel();
        panel.requestChannelList("");  // must not throw
    }

    @Test
    public void requestChannelListCoalescesNullToEmptyString() throws Exception {
        IrcPanel panel = headlessPanel();
        List<String> requested = new ArrayList<>();
        panel.init(null, null, null, null, (network, query) -> requested.add(query), null);

        panel.requestChannelList(null);

        assertEquals(1, requested.size());
        assertEquals("", requested.get(0));
    }

    @Test
    public void armingStartsATimer() throws Exception {
        IrcPanel panel = headlessPanel();
        panel.armChannelListTimeout();

        Timer timer = (Timer) get(panel, "channelListTimeout");
        assertNotNull(timer);
        assertTrue(timer.isRunning());
        assertFalse("a one-shot timeout must not repeat", timer.isRepeats());

        panel.cancelChannelListTimeout();
    }

    @Test
    public void cancellingStopsTheTimer() throws Exception {
        IrcPanel panel = headlessPanel();
        panel.armChannelListTimeout();
        panel.cancelChannelListTimeout();

        Timer timer = (Timer) get(panel, "channelListTimeout");
        assertFalse(timer.isRunning());
    }

    /** Re-arming must not leave the previous timer running behind the new one. */
    @Test
    public void rearmingReplacesTheRunningTimer() throws Exception {
        IrcPanel panel = headlessPanel();
        panel.armChannelListTimeout();
        Timer first = (Timer) get(panel, "channelListTimeout");

        panel.armChannelListTimeout();
        Timer second = (Timer) get(panel, "channelListTimeout");

        assertFalse("the superseded timer must be stopped", first.isRunning());
        assertTrue(second.isRunning());
        panel.cancelChannelListTimeout();
    }

    @Test
    public void cancellingWithoutArmingIsSafe() throws Exception {
        IrcPanel panel = headlessPanel();
        panel.cancelChannelListTimeout();  // must not throw
    }

    /** Fires the armed timer's action now rather than waiting 30 real seconds. */
    private static void fireTimeout(IrcPanel panel) throws Exception {
        Timer timer = (Timer) get(panel, "channelListTimeout");
        for (java.awt.event.ActionListener listener : timer.getActionListeners()) {
            listener.actionPerformed(null);
        }
        panel.cancelChannelListTimeout();
        // Twice: addMessage queues the document edit on a second invokeLater hop, so one drain
        // would leave the "must NOT contain" assertion below passing vacuously.
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
    }

    /**
     * Whole-branch review finding: the timeout was the one channel-list message that never reached
     * the game chatbox. "Requesting channel list..." and a 263 refusal both go through the
     * plugin's processMessage, which feeds the chatbox and the panel; the timeout called
     * addMessage directly, so a user with the sidebar collapsed saw the request announced in game
     * chat and never learned it had failed.
     *
     * The fix is that the panel reports the expiry to the plugin instead of writing it itself, so
     * what is pinned here is: the callback fires, and nothing lands in the System pane locally.
     * The second assertion is the one that fails if anyone reinstates the direct write.
     */
    @Test
    public void timeoutReportsThroughTheCallbackRatherThanWritingToThePanel() throws Exception {
        IrcPanel panel = headlessPanel();
        List<String> reported = new ArrayList<>();
        panel.init(null, null, null, null, null, () -> reported.add("timeout"));

        panel.armChannelListTimeout();
        fireTimeout(panel);

        assertEquals("the expiry must be reported exactly once", 1, reported.size());

        String text = panel.getPane("System").getText();
        assertFalse("the panel must not also write the notice itself - the plugin owns emitting it,"
                        + " so a direct write would double up in the panel and still skip the chatbox: "
                        + text,
                text.toLowerCase().contains("no channel list response"));
    }

    /** A panel that was never wired to a plugin must not blow up when its timeout expires. */
    @Test
    public void timeoutWithoutACallbackIsSafe() throws Exception {
        IrcPanel panel = headlessPanel();
        panel.armChannelListTimeout();
        fireTimeout(panel);  // must not throw
    }

    /**
     * Whole-branch review finding: the browser survived plugin shutdown as a zombie window.
     * shutDown() removed the nav button, nulled the panel and disconnected, but the JDialog is a
     * top-level Window that nothing disposed - it stayed on screen with Refresh and Join both
     * returning silently, and re-enabling the plugin leaked it (IrcPanel is not a singleton, so
     * the injector hands back a fresh panel and a fresh dialog) along with its sorter and up to
     * 20,000 entries.
     *
     * The dialog half needs a real Window, so it is asserted only where one can be created; the
     * timer half and the field-nulling are asserted unconditionally.
     */
    @Test
    public void shutdownStopsTheTimeoutAndReleasesTheDialog() throws Exception {
        IrcPanel panel = headlessPanel();
        panel.armChannelListTimeout();
        Timer armed = (Timer) get(panel, "channelListTimeout");

        ChannelListDialog dialog = null;
        try {
            dialog = new ChannelListDialog(null, null, null);
            set(panel, "channelListDialog", dialog);
        } catch (java.awt.HeadlessException noDisplayAvailable) {
            // Window construction needs a display; the rest of the teardown still has to hold.
        }

        panel.shutdown();

        assertFalse("shutdown must stop a pending timeout", armed.isRunning());
        assertNull("the timer field must be released", get(panel, "channelListTimeout"));
        assertNull("the dialog field must be nulled so a later showChannelList rebuilds cleanly",
                get(panel, "channelListDialog"));
        if (dialog != null) {
            assertFalse("the dialog's native window must be disposed, not merely dereferenced",
                    dialog.isDisplayable());
        }
    }

    /** Teardown runs on plugin shutdown regardless of whether /list was ever used. */
    @Test
    public void shutdownIsSafeWithNothingArmedOrShown() throws Exception {
        IrcPanel panel = headlessPanel();
        panel.shutdown();
        panel.shutdown();  // idempotent - must not throw
        assertNull(get(panel, "channelListDialog"));
    }

    /**
     * A LIST reply that does arrive must disarm the timeout, or the "no response" notice fires on
     * top of a list the user is already looking at. {@code cancelChannelListTimeout()} is the
     * first statement in {@code showChannelList}, before any {@link java.awt.Window} is
     * constructed, so this holds even though the dialog itself can't be built headless: the
     * {@link java.awt.HeadlessException} it throws is caught here and the timer state is asserted
     * regardless of whether that exception was thrown.
     */
    @Test
    public void showingTheChannelListCancelsThePendingTimeout() throws Exception {
        IrcPanel panel = headlessPanel();
        panel.armChannelListTimeout();

        try {
            panel.showChannelList(new ArrayList<>(), "", false);
        } catch (java.awt.HeadlessException expectedOnAHeadlessMachine) {
            // Dialog construction needs a real Window; the cancel already ran before this point.
        }

        Timer timer = (Timer) get(panel, "channelListTimeout");
        assertFalse("showChannelList must cancel the pending timeout", timer.isRunning());
    }
}
