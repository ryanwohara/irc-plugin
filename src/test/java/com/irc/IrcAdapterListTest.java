package com.irc;

import org.junit.Test;

import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The adapter's LIST plumbing: turning a query into a wire command, and a completed reply into a
 * panel call.
 *
 * A bare /list must send "LIST" with no trailing space - some servers treat "LIST " as a filter
 * argument and return nothing.
 */
public class IrcAdapterListTest {

    /** Captures what the adapter puts on the wire. */
    private static class RecordingClient extends SimpleIrcClient {
        final List<String> sentLines = new ArrayList<>();

        @Override
        public synchronized void sendRawLine(String line) {
            sentLines.add(line);
        }
    }

    private static IrcAdapter adapterWith(RecordingClient client) throws Exception {
        IrcAdapter adapter = new IrcAdapter();
        java.lang.reflect.Field field = IrcAdapter.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(adapter, client);
        return adapter;
    }

    @Test
    public void bareListSendsNoTrailingSpace() throws Exception {
        RecordingClient client = new RecordingClient();
        IrcAdapter adapter = adapterWith(client);

        adapter.requestChannelList("");

        assertEquals(1, client.sentLines.size());
        assertEquals("LIST", client.sentLines.get(0));
    }

    @Test
    public void nullQueryIsTreatedAsBare() throws Exception {
        RecordingClient client = new RecordingClient();
        IrcAdapter adapter = adapterWith(client);

        adapter.requestChannelList(null);

        assertEquals("LIST", client.sentLines.get(0));
    }

    @Test
    public void argumentsArePassedThroughVerbatim() throws Exception {
        RecordingClient client = new RecordingClient();
        IrcAdapter adapter = adapterWith(client);

        adapter.requestChannelList(">50 *quest*");

        assertEquals("LIST >50 *quest*", client.sentLines.get(0));
    }

    @Test
    public void surroundingWhitespaceIsTrimmed() throws Exception {
        RecordingClient client = new RecordingClient();
        IrcAdapter adapter = adapterWith(client);

        adapter.requestChannelList("   >50   ");

        assertEquals("LIST >50", client.sentLines.get(0));
    }

    @Test
    public void reportsConnectionState() throws Exception {
        RecordingClient client = new RecordingClient();
        IrcAdapter adapter = adapterWith(client);

        assertTrue("a fresh client is not connected", !adapter.isConnected());
    }

    /**
     * Confirmed Task 2 review finding: a LIST run abandoned with no 323, no 263 and no
     * disconnect (the server truncates its reply but the connection survives) used to leave
     * channelListRunActive true, so the next LIST's rows appended onto the stale ones instead of
     * starting fresh. requestChannelList must reset the run before sending.
     *
     * Also covers a Task 6 review finding: the reset must NOT clear channelListSnapshot - the
     * dialog's currently displayed list has to survive until a new run actually completes, or
     * Refresh would blank it mid-flight.
     */
    @Test
    public void secondRequestDoesNotAppendToAnAbandonedRun() throws Exception {
        RecordingClient client = new RecordingClient();
        IrcAdapter adapter = adapterWith(client);

        // A prior LIST completed normally and published a snapshot the dialog is showing now.
        client.processLine(":server 322 me #old 3 :old topic");
        client.processLine(":server 323 me :End of /LIST");
        assertEquals("#old", client.getChannelListSnapshot().get(0).getName());

        // Second run starts but is abandoned - no 323 ever arrives.
        client.processLine(":server 322 me #stale 1 :stale topic");

        adapter.requestChannelList("");

        // Refresh must not blank the dialog mid-flight: the previous snapshot survives the reset,
        // even though the abandoned run's accumulator has just been cleared.
        List<ChannelListEntry> midFlight = client.getChannelListSnapshot();
        assertEquals(1, midFlight.size());
        assertEquals("#old", midFlight.get(0).getName());

        // Third run completes normally and replaces the snapshot; #stale never appears.
        client.processLine(":server 322 me #fresh 2 :fresh topic");
        client.processLine(":server 323 me :End of /LIST");

        List<ChannelListEntry> entries = client.getChannelListSnapshot();
        assertEquals(1, entries.size());
        assertEquals("#fresh", entries.get(0).getName());
    }

    private static IrcConfig stubConfig() {
        // IrcConfig only has two abstract methods; everything else is a default.
        return new IrcConfig() {
            @Override
            public String username() {
                return "mynick";
            }

            @Override
            public String password() {
                return "";
            }
        };
    }

    /** Same headless construction approach as IrcPanelChannelListTest. */
    private static IrcPanel headlessPanel() throws Exception {
        IrcPanel panel = new IrcPanel();
        Field configField = IrcPanel.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(panel, stubConfig());
        Field tabbedPaneField = IrcPanel.class.getDeclaredField("tabbedPane");
        tabbedPaneField.setAccessible(true);
        tabbedPaneField.set(panel, new JTabbedPane());
        panel.addChannel("System");
        panel.setFocusedChannel("System");
        return panel;
    }

    /**
     * Confirmed Task 5 review finding: the design invariant is that ANY outcome cancels the
     * pending 30s timeout, but previously only the success path (showChannelList) and the
     * explicit failure path (CHANNEL_LIST_FAILED) did. A disconnect while a timeout was armed
     * still fired a spurious "No channel list response from the server." message 30 seconds
     * later. The DISCONNECT case must cancel it too.
     */
    @Test
    public void disconnectCancelsThePendingChannelListTimeout() throws Exception {
        IrcPanel panel = headlessPanel();

        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), message -> { }, panel, "mynick");
        SimpleIrcClient client = adapter.getClient();
        // Register so disconnect() (which requires `connected`) actually runs.
        client.processLine(":server 001 mynick :Welcome to IRC mynick!runelite@host");

        panel.armChannelListTimeout();

        adapter.disconnect();
        // Drain the EDT so the invokeLater posted by the DISCONNECT case has run before we assert.
        SwingUtilities.invokeAndWait(() -> { });

        Field timeoutField = IrcPanel.class.getDeclaredField("channelListTimeout");
        timeoutField.setAccessible(true);
        Timer timer = (Timer) timeoutField.get(panel);
        assertFalse("disconnect must cancel a pending channel list timeout", timer.isRunning());
    }

    /**
     * Records what the adapter delivers to the panel for a completed or failed LIST, without
     * needing a live Window - showChannelList's real implementation constructs a ChannelListDialog,
     * which throws HeadlessException on a headless machine. Same technique as RecordingClient
     * above: override the one method under test rather than exercise the real implementation.
     */
    private static class RecordingPanel extends IrcPanel {
        volatile List<ChannelListEntry> deliveredEntries;
        volatile String deliveredQuery;
        volatile Boolean deliveredTruncated;
        volatile boolean cancelCalled;

        @Override
        public void showChannelList(List<ChannelListEntry> entries, String query, boolean truncated) {
            deliveredEntries = entries;
            deliveredQuery = query;
            deliveredTruncated = truncated;
        }

        @Override
        public void cancelChannelListTimeout() {
            cancelCalled = true;
        }
    }

    /**
     * Task 6 review finding: the task's headline deliverable - handing a completed LIST reply to
     * the panel - had no coverage. A mutation that disabled the CHANNEL_LIST branch entirely left
     * the full suite green. This pins the whole handoff: the right entries, the query that was in
     * flight, and the truncation flag all have to reach showChannelList.
     */
    @Test
    public void channelListDeliversTheSnapshotToThePanel() throws Exception {
        RecordingPanel panel = new RecordingPanel();
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), message -> { }, panel, "mynick");
        SimpleIrcClient client = adapter.getClient();

        adapter.requestChannelList(">50");
        client.processLine(":server 322 me #help 4 :Get help here");
        client.processLine(":server 323 me :End of /LIST");
        // CHANNEL_LIST hands off via invokeLater; drain the EDT before asserting.
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(1, panel.deliveredEntries.size());
        assertEquals("#help", panel.deliveredEntries.get(0).getName());
        assertEquals(">50", panel.deliveredQuery);
        assertEquals(Boolean.FALSE, panel.deliveredTruncated);
    }

    /**
     * Task 6 review finding, same gap for the failure path: CHANNEL_LIST_FAILED must also cancel
     * the panel's pending timeout, and nothing exercised that call either.
     */
    @Test
    public void channelListFailedCancelsThePendingTimeout() throws Exception {
        RecordingPanel panel = new RecordingPanel();
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), message -> { }, panel, "mynick");
        SimpleIrcClient client = adapter.getClient();

        client.processLine(":server 263 me LIST :Server load is too heavy, please try again later");
        SwingUtilities.invokeAndWait(() -> { });

        assertTrue("a failed channel list must cancel the pending timeout", panel.cancelCalled);
    }

    /**
     * Whole-branch review finding: the adapter held the panel forever, so a 323 that arrived while
     * the plugin was shutting down still drove showChannelList and popped the browser for a plugin
     * that no longer exists. shutDown() clears the reference before tearing the panel down.
     */
    @Test
    public void clearPanelStopsALateChannelListFromReachingTheUi() throws Exception {
        RecordingPanel panel = new RecordingPanel();
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), message -> { }, panel, "mynick");
        SimpleIrcClient client = adapter.getClient();

        // Sanity: without clearing, this delivery path is live.
        adapter.requestChannelList("");
        client.processLine(":server 322 me #before 4 :still wired up");
        client.processLine(":server 323 me :End of /LIST");
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, panel.deliveredEntries.size());

        adapter.clearPanel();

        adapter.requestChannelList("");
        client.processLine(":server 322 me #after 9 :arrived during shutdown");
        client.processLine(":server 323 me :End of /LIST");
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals("a reply arriving after teardown must not reach the panel",
                "#before", panel.deliveredEntries.get(0).getName());
        assertEquals(1, panel.deliveredEntries.size());
    }

    /** The failure path holds the same reference and must respect the same teardown. */
    @Test
    public void clearPanelStopsALateFailureFromReachingTheUi() throws Exception {
        RecordingPanel panel = new RecordingPanel();
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), message -> { }, panel, "mynick");
        SimpleIrcClient client = adapter.getClient();

        adapter.clearPanel();
        client.processLine(":server 263 me LIST :Server load is too heavy, please try again later");
        SwingUtilities.invokeAndWait(() -> { });

        assertFalse("a 263 arriving after teardown must not touch the panel", panel.cancelCalled);
    }
}
