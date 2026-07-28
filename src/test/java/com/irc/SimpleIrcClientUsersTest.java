package com.irc;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The protocol layer feeding ChannelUserList and announcing changes.
 *
 * Every membership event must both update the roster and fire USERS_CHANGED for each channel it
 * touched, so the panel redraws exactly the affected buffers. Display events (NAMES, CHANNEL_MODE)
 * must keep firing unchanged so /names and mode messages still print to chat.
 */
public class SimpleIrcClientUsersTest {

    /** Feeds raw lines without a socket and records what came back out. */
    private static class RecordingClient extends SimpleIrcClient {
        final List<String> sentLines = new ArrayList<>();
        final List<IrcEvent> events = new ArrayList<>();

        RecordingClient() {
            addEventListener(events::add);
        }

        @Override
        public synchronized void sendRawLine(String line) {
            sentLines.add(line);
        }

        List<String> usersChangedChannels() {
            List<String> channels = new ArrayList<>();
            for (IrcEvent event : events) {
                if (event.getType() == IrcEvent.Type.USERS_CHANGED) {
                    channels.add(event.getTarget());
                }
            }
            return channels;
        }

        List<String> nicksIn(String channel) {
            List<String> nicks = new ArrayList<>();
            for (ChannelUserList.Entry entry : getChannelUsers(channel)) {
                nicks.add(entry.getPrefix() + entry.getNick());
            }
            return nicks;
        }

        boolean firedType(IrcEvent.Type type) {
            return events.stream().anyMatch(e -> e.getType() == type);
        }
    }

    /** A 353 followed by its 366 terminator, as the server sends them. */
    private static void loadNames(RecordingClient client, String channel, String names) {
        client.processLine(":server 353 me = " + channel + " :" + names);
        client.processLine(":server 366 me " + channel + " :End of /NAMES list");
    }

    @Test
    public void namesReplyPopulatesTheRosterWithPrefixesIntact() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#chan", "@bob +dave erin");
        assertEquals(java.util.Arrays.asList("@bob", "+dave", "erin"), client.nicksIn("#chan"));
    }

    @Test
    public void namesReplyStillFiresTheDisplayEventSoSlashNamesKeepsPrinting() {
        RecordingClient client = new RecordingClient();
        client.processLine(":server 353 me = #chan :@bob +dave");
        assertTrue(client.firedType(SimpleIrcClient.IrcEvent.Type.NAMES));
    }

    @Test
    public void endOfNamesAnnouncesTheChannel() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#chan", "@bob");
        assertEquals(java.util.Collections.singletonList("#chan"), client.usersChangedChannels());
    }

    @Test
    public void isupportRetunesThePrefixTable() {
        RecordingClient client = new RecordingClient();
        client.processLine(":server 005 me PREFIX=(ov)@+ CHANMODES=b,k,l,imnpst :are supported");
        loadNames(client, "#chan", "@bob %carol");
        // With PREFIX=(ov)@+ the server has no halfop, so "%carol" is a nick starting with '%'.
        assertEquals(java.util.Arrays.asList("@bob", "%carol"), client.nicksIn("#chan"));
    }

    @Test
    public void joinAddsTheUserAndAnnounces() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#chan", "@bob");
        client.events.clear();

        client.processLine(":erin!user@host JOIN #chan");

        assertEquals(java.util.Arrays.asList("@bob", "erin"), client.nicksIn("#chan"));
        assertEquals(java.util.Collections.singletonList("#chan"), client.usersChangedChannels());
    }

    @Test
    public void partRemovesTheUserAndAnnounces() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#chan", "@bob erin");
        client.events.clear();

        client.processLine(":erin!user@host PART #chan :bye");

        assertEquals(java.util.Collections.singletonList("@bob"), client.nicksIn("#chan"));
        assertEquals(java.util.Collections.singletonList("#chan"), client.usersChangedChannels());
    }

    @Test
    public void quitAnnouncesEveryChannelTheUserWasIn() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#one", "erin bob");
        loadNames(client, "#two", "erin");
        loadNames(client, "#three", "bob");
        client.events.clear();

        client.processLine(":erin!user@host QUIT :gone");

        List<String> announced = client.usersChangedChannels();
        assertEquals(2, announced.size());
        assertTrue(announced.contains("#one"));
        assertTrue(announced.contains("#two"));
        assertFalse("#three had no erin", announced.contains("#three"));
    }

    @Test
    public void quitStillCarriesTheAffectedChannelsOnTheQuitEvent() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#one", "erin");
        client.events.clear();

        client.processLine(":erin!user@host QUIT :gone");

        SimpleIrcClient.IrcEvent quit = client.events.stream()
                .filter(e -> e.getType() == SimpleIrcClient.IrcEvent.Type.QUIT)
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("#one", quit.getAdditionalData());
    }

    @Test
    public void nickChangeRenamesInPlaceAndKeepsModes() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#chan", "@bob");
        client.events.clear();

        client.processLine(":bob!user@host NICK robert");

        assertEquals(java.util.Collections.singletonList("@robert"), client.nicksIn("#chan"));
        assertEquals(java.util.Collections.singletonList("#chan"), client.usersChangedChannels());
    }

    @Test
    public void kickRemovesTheUserAndAnnounces() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#chan", "@bob erin");
        client.events.clear();

        client.processLine(":bob!user@host KICK #chan erin :out");

        assertEquals(java.util.Collections.singletonList("@bob"), client.nicksIn("#chan"));
        assertTrue(client.usersChangedChannels().contains("#chan"));
    }

    @Test
    public void channelModeIsAppliedToTheRoster() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#chan", "bob");
        client.events.clear();

        client.processLine(":op!user@host MODE #chan +o bob");

        assertEquals(java.util.Collections.singletonList("@bob"), client.nicksIn("#chan"));
        assertTrue(client.usersChangedChannels().contains("#chan"));
    }

    @Test
    public void compoundChannelModeAlignsItsParameters() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#chan", "bob");
        client.events.clear();

        client.processLine(":op!user@host MODE #chan +mo bob");

        assertEquals(java.util.Collections.singletonList("@bob"), client.nicksIn("#chan"));
    }

    @Test
    public void channelModeStillFiresTheDisplayEvent() {
        RecordingClient client = new RecordingClient();
        loadNames(client, "#chan", "bob");
        client.events.clear();

        client.processLine(":op!user@host MODE #chan +o bob");

        assertTrue(client.firedType(SimpleIrcClient.IrcEvent.Type.CHANNEL_MODE));
    }

    @Test
    public void userModeOnANickIsNotTreatedAsAChannel() {
        RecordingClient client = new RecordingClient();
        client.processLine(":me!user@host MODE me +i");
        assertTrue("no channel changed", client.usersChangedChannels().isEmpty());
    }
}
