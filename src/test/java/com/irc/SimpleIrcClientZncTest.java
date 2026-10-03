package com.irc;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class SimpleIrcClientZncTest {

    /** Feeds raw lines without a socket and records what came back out. */
    private static class RecordingClient extends SimpleIrcClient {
        final List<String> sentLines = new ArrayList<>();
        final List<IrcEvent> events = new ArrayList<>();

        RecordingClient() {
            addEventListener(events::add);
            credentials("me", "runelite", "me");
        }

        @Override
        public synchronized void sendRawLine(String line) {
            sentLines.add(line);
        }

        IrcEvent last(IrcEvent.Type type) {
            IrcEvent found = null;
            for (IrcEvent e : events) if (e.getType() == type) found = e;
            return found;
        }
    }

    @Test
    public void passPrecedesNickAndKeepsSpaces() {
        RecordingClient client = new RecordingClient();
        client.serverPassword("me/rizon:pass word");
        client.sendRegistration();
        assertEquals(Arrays.asList("PASS :me/rizon:pass word", "NICK me", "USER runelite 0 * :me", "CAP LS 302"),
                client.sentLines);
    }

    @Test
    public void noPassWithoutAPassword() {
        RecordingClient client = new RecordingClient();
        client.serverPassword(null);
        client.sendRegistration();
        assertEquals("NICK me", client.sentLines.get(0));
    }

    @Test
    public void requestsSelfMessageOnlyWhenAdvertisedAndNeverEchoMessage() {
        RecordingClient znc = new RecordingClient();
        znc.processLine(":irc.znc.in CAP * LS :batch server-time znc.in/self-message echo-message");
        assertTrue(znc.sentLines.contains("CAP REQ :batch server-time znc.in/self-message"));
        for (String line : znc.sentLines) assertFalse(line, line.contains("echo-message"));

        RecordingClient plain = new RecordingClient();
        plain.processLine(":server CAP * LS :batch");
        assertTrue(plain.sentLines.contains("CAP REQ :batch"));
    }

    @Test
    public void ownPrivmsgIsFiledUnderItsTarget() {
        RecordingClient client = new RecordingClient();
        client.processLine(":server 001 me :Welcome");
        client.processLine(":me!u@h PRIVMSG Luna :sent from my phone");
        SimpleIrcClient.IrcEvent event = client.last(SimpleIrcClient.IrcEvent.Type.MESSAGE);
        assertEquals("Luna", event.getTarget());
        assertEquals("me", event.getSource());
    }

    @Test
    public void ownActionIsFiledUnderItsTarget() {
        RecordingClient client = new RecordingClient();
        client.processLine(":server 001 me :Welcome");
        client.processLine(":me!u@h PRIVMSG Luna :\u0001ACTION waves\u0001");
        assertEquals("Luna", client.last(SimpleIrcClient.IrcEvent.Type.ACTION).getTarget());
    }

    @Test
    public void othersPrivmsgIsStillFiledUnderTheSender() {
        RecordingClient client = new RecordingClient();
        client.processLine(":server 001 me :Welcome");
        client.processLine(":Luna!u@h PRIVMSG me :hey");
        assertEquals("Luna", client.last(SimpleIrcClient.IrcEvent.Type.MESSAGE).getTarget());
    }

    @Test
    public void liveTimeTagIsPassedThrough() {
        RecordingClient client = new RecordingClient();
        client.processLine("@time=2026-10-01T12:00:00.000Z :Ash!u@h PRIVMSG #chan :old news");
        assertEquals("2026-10-01T12:00:00.000Z",
                client.last(SimpleIrcClient.IrcEvent.Type.MESSAGE).getAdditionalData());
        client.processLine(":Ash!u@h PRIVMSG #chan :no tag");
        assertNull(client.last(SimpleIrcClient.IrcEvent.Type.MESSAGE).getAdditionalData());
    }

    @Test
    public void zncPlaybackBatchBecomesAHistoryBatch() {
        RecordingClient client = new RecordingClient();
        client.processLine(":irc.znc.in BATCH +pb1 znc.in/playback #chan");
        client.processLine("@batch=pb1;time=2026-10-01T12:00:00.000Z :Ash!u@h PRIVMSG #chan :while you were away");
        client.processLine(":irc.znc.in BATCH -pb1");
        SimpleIrcClient.IrcEvent batch = client.last(SimpleIrcClient.IrcEvent.Type.HISTORY_BATCH);
        assertNotNull(batch);
        assertEquals("#chan", batch.getTarget());
        assertEquals(1, batch.getHistoryMessages().size());
        assertEquals("while you were away", batch.getHistoryMessages().get(0).getMessage());
    }
}
