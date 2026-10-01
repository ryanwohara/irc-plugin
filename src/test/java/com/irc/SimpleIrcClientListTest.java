package com.irc;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The protocol layer for LIST (numerics 321/322/323, plus 263 when the server throttles us).
 *
 * Replies accumulate on the reader thread and are published as one immutable snapshot at 323, so
 * the EDT never observes a half-filled list. Two rules matter most: a run must not inherit rows
 * from the previous run, and a malformed row must never abort the rest of the list - servers send
 * a lot of channels and one bad line should cost one row, not the whole reply.
 */
public class SimpleIrcClientListTest {

    /** Feeds raw lines without a socket and records what came back out. */
    private static class RecordingClient extends SimpleIrcClient {
        final List<IrcEvent> events = new ArrayList<>();

        RecordingClient() {
            addEventListener(events::add);
        }

        @Override
        public synchronized void sendRawLine(String line) {
            // no socket in tests
        }

        boolean firedType(IrcEvent.Type type) {
            return events.stream().anyMatch(e -> e.getType() == type);
        }

        long countOf(IrcEvent.Type type) {
            return events.stream().filter(e -> e.getType() == type).count();
        }
    }

    private static void listReply(RecordingClient client, String channel, String count, String topic) {
        client.processLine(":server 322 me " + channel + " " + count + " :" + topic);
    }

    @Test
    public void collectsChannelsBetweenStartAndEnd() {
        RecordingClient client = new RecordingClient();
        client.processLine(":server 321 me Channel :Users  Name");
        listReply(client, "#help", "412", "Get help here");
        listReply(client, "#chat", "380", "General chat");
        client.processLine(":server 323 me :End of /LIST");

        List<ChannelListEntry> entries = client.getChannelListSnapshot();
        assertEquals(2, entries.size());
        assertEquals("#help", entries.get(0).getName());
        assertEquals(412, entries.get(0).getUserCount());
        assertEquals("Get help here", entries.get(0).getTopic());
        assertEquals("#chat", entries.get(1).getName());
        assertEquals(380, entries.get(1).getUserCount());
        assertTrue(client.firedType(SimpleIrcClient.IrcEvent.Type.CHANNEL_LIST));
    }

    /** Some servers never send 321, so 322 has to be able to start a run by itself. */
    @Test
    public void collectsWhenListStartIsNeverSent() {
        RecordingClient client = new RecordingClient();
        listReply(client, "#help", "412", "Get help here");
        client.processLine(":server 323 me :End of /LIST");

        assertEquals(1, client.getChannelListSnapshot().size());
        assertEquals("#help", client.getChannelListSnapshot().get(0).getName());
    }

    @Test
    public void keepsTopicsContainingSpacesAndColons() {
        RecordingClient client = new RecordingClient();
        listReply(client, "#help", "5", "Ask here: we answer 9-5, mostly");
        client.processLine(":server 323 me :End of /LIST");

        assertEquals("Ask here: we answer 9-5, mostly",
                client.getChannelListSnapshot().get(0).getTopic());
    }

    @Test
    public void treatsMissingTopicAsEmpty() {
        RecordingClient client = new RecordingClient();
        client.processLine(":server 322 me #notopic 7");
        client.processLine(":server 323 me :End of /LIST");

        List<ChannelListEntry> entries = client.getChannelListSnapshot();
        assertEquals(1, entries.size());
        assertEquals("", entries.get(0).getTopic());
        assertEquals(7, entries.get(0).getUserCount());
    }

    /** One unparseable count costs that row its number, not its place in the list. */
    @Test
    public void treatsUnparseableCountAsZeroAndKeepsTheRow() {
        RecordingClient client = new RecordingClient();
        listReply(client, "#weird", "many", "Odd server");
        listReply(client, "#fine", "12", "Normal");
        client.processLine(":server 323 me :End of /LIST");

        List<ChannelListEntry> entries = client.getChannelListSnapshot();
        assertEquals(2, entries.size());
        assertEquals(0, entries.get(0).getUserCount());
        assertEquals(12, entries.get(1).getUserCount());
    }

    @Test
    public void consecutiveRunsDoNotBleedTogether() {
        RecordingClient client = new RecordingClient();
        listReply(client, "#first", "1", "one");
        client.processLine(":server 323 me :End of /LIST");
        assertEquals(1, client.getChannelListSnapshot().size());

        listReply(client, "#second", "2", "two");
        client.processLine(":server 323 me :End of /LIST");

        List<ChannelListEntry> entries = client.getChannelListSnapshot();
        assertEquals(1, entries.size());
        assertEquals("#second", entries.get(0).getName());
        assertEquals(2, client.countOf(SimpleIrcClient.IrcEvent.Type.CHANNEL_LIST));
    }

    /** 321 arriving mid-flight resets, so a retried LIST never doubles up. */
    @Test
    public void listStartClearsPartialRun() {
        RecordingClient client = new RecordingClient();
        listReply(client, "#stale", "1", "stale");
        client.processLine(":server 321 me Channel :Users  Name");
        listReply(client, "#fresh", "2", "fresh");
        client.processLine(":server 323 me :End of /LIST");

        List<ChannelListEntry> entries = client.getChannelListSnapshot();
        assertEquals(1, entries.size());
        assertEquals("#fresh", entries.get(0).getName());
    }

    @Test
    public void tryAgainReportsFailureWithoutFiringChannelList() {
        RecordingClient client = new RecordingClient();
        client.processLine(":server 263 me LIST :Server load is too heavy, please try again later");

        assertTrue(client.firedType(SimpleIrcClient.IrcEvent.Type.CHANNEL_LIST_FAILED));
        assertFalse(client.firedType(SimpleIrcClient.IrcEvent.Type.CHANNEL_LIST));
    }

    /** 263 must not fire ERROR - IrcAdapter's ERROR case disconnects the client. */
    @Test
    public void tryAgainDoesNotFireError() {
        RecordingClient client = new RecordingClient();
        client.processLine(":server 263 me LIST :Try again later");

        assertFalse(client.firedType(SimpleIrcClient.IrcEvent.Type.ERROR));
    }

    @Test
    public void tryAgainDiscardsAPartialRun() {
        RecordingClient client = new RecordingClient();
        listReply(client, "#partial", "3", "partial");
        client.processLine(":server 263 me LIST :Try again later");
        client.processLine(":server 323 me :End of /LIST");

        assertTrue(client.getChannelListSnapshot().isEmpty());
    }

    @Test
    public void rowWithNoChannelNameIsSkipped() {
        RecordingClient client = new RecordingClient();
        client.processLine(":server 322 me");
        listReply(client, "#good", "4", "good");
        client.processLine(":server 323 me :End of /LIST");

        List<ChannelListEntry> entries = client.getChannelListSnapshot();
        assertEquals(1, entries.size());
        assertEquals("#good", entries.get(0).getName());
    }

    @Test
    public void stopsAccumulatingAtTheCapAndReportsTruncation() {
        RecordingClient client = new RecordingClient();
        for (int i = 0; i < 20005; i++) {
            listReply(client, "#chan" + i, "1", "topic");
        }
        client.processLine(":server 323 me :End of /LIST");

        assertEquals(20000, client.getChannelListSnapshot().size());
        assertTrue(client.isChannelListTruncated());
    }

    /**
     * The second run here deliberately omits 321, since not every server sends it. The flag must
     * still reset on the first 322 of a fresh run - it must not depend on 321 arriving to clear it.
     */
    @Test
    public void truncationFlagIsClearedByAFreshRun() {
        RecordingClient client = new RecordingClient();
        for (int i = 0; i < 20005; i++) {
            listReply(client, "#chan" + i, "1", "topic");
        }
        client.processLine(":server 323 me :End of /LIST");
        assertTrue(client.isChannelListTruncated());

        listReply(client, "#small", "1", "topic");
        client.processLine(":server 323 me :End of /LIST");

        assertFalse(client.isChannelListTruncated());
        assertEquals(1, client.getChannelListSnapshot().size());
    }

    /** A 323 with no preceding 321/322 must not wipe out the last good snapshot or re-fire CHANNEL_LIST. */
    @Test
    public void unpairedListEndDoesNotClobberThePreviousSnapshot() {
        RecordingClient client = new RecordingClient();
        listReply(client, "#good", "4", "good");
        client.processLine(":server 323 me :End of /LIST");
        assertEquals(1, client.countOf(SimpleIrcClient.IrcEvent.Type.CHANNEL_LIST));

        client.processLine(":server 323 me :End of /LIST");

        List<ChannelListEntry> entries = client.getChannelListSnapshot();
        assertEquals(1, entries.size());
        assertEquals("#good", entries.get(0).getName());
        assertEquals(1, client.countOf(SimpleIrcClient.IrcEvent.Type.CHANNEL_LIST));
    }

    /** disconnect() must clear a partial run so it can't bleed into the connection's next LIST. */
    @Test
    public void disconnectClearsAPartialRun() {
        RecordingClient client = new RecordingClient();
        client.processLine(":server 001 me :Welcome");
        listReply(client, "#partial", "3", "partial");

        client.disconnect();

        listReply(client, "#fresh", "1", "fresh");
        client.processLine(":server 323 me :End of /LIST");

        List<ChannelListEntry> entries = client.getChannelListSnapshot();
        assertEquals(1, entries.size());
        assertEquals("#fresh", entries.get(0).getName());
    }

    /** 263 only concerns LIST; a throttled WHO must not touch LIST state or fire CHANNEL_LIST_FAILED. */
    @Test
    public void tryAgainForADifferentCommandLeavesListStateAlone() {
        RecordingClient client = new RecordingClient();
        listReply(client, "#inflight", "1", "still going");

        client.processLine(":server 263 me WHO :Server load is too heavy, please try again later");

        assertFalse(client.firedType(SimpleIrcClient.IrcEvent.Type.CHANNEL_LIST_FAILED));

        client.processLine(":server 323 me :End of /LIST");

        List<ChannelListEntry> entries = client.getChannelListSnapshot();
        assertEquals(1, entries.size());
        assertEquals("#inflight", entries.get(0).getName());
    }

    @Test
    public void snapshotIsImmutable() {
        RecordingClient client = new RecordingClient();
        listReply(client, "#help", "1", "topic");
        client.processLine(":server 323 me :End of /LIST");

        try {
            client.getChannelListSnapshot().add(new ChannelListEntry("#injected", 0, ""));
            org.junit.Assert.fail("snapshot handed to the EDT must not be mutable");
        } catch (UnsupportedOperationException expected) {
            // the EDT holds this list; nothing may mutate it after publication
        }
    }
}
