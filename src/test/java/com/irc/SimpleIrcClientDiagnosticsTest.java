package com.irc;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Diagnostics for the two failure modes users actually report: a connection that never comes up,
 * and one that is torn down without explanation.
 *
 * The server almost always says why - in an ERROR line, a KILL, or an error numeric - and the
 * client used to drop all of it on the floor. These tests pin down that the text survives as far
 * as a fired event, because that text is the whole of what a reporting user can give us.
 */
public class SimpleIrcClientDiagnosticsTest {

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

        IrcEvent firstOf(IrcEvent.Type type) {
            return events.stream().filter(e -> e.getType() == type).findFirst().orElse(null);
        }

        IrcEvent lastOf(IrcEvent.Type type) {
            return events.stream().filter(e -> e.getType() == type)
                    .reduce((a, b) -> b).orElse(null);
        }
    }

    @Test
    public void serverErrorLineSurfacesTheServersOwnReason() {
        RecordingClient client = new RecordingClient();

        client.processLine("ERROR :Closing Link: someone[1.2.3.4] (Ping timeout: 240 seconds)");

        SimpleIrcClient.IrcEvent event = client.firstOf(SimpleIrcClient.IrcEvent.Type.ERROR);
        assertNotNull("server ERROR must fire an ERROR event", event);
        assertTrue("the server's reason must survive: " + event.getMessage(),
                event.getMessage().contains("Ping timeout: 240 seconds"));
    }

    @Test
    public void killNamesTheOperatorAndTheReason() {
        RecordingClient client = new RecordingClient();
        client.credentials("me", "runelite", "me");

        client.processLine(":oper!o@staff.example KILL me :staff.example (spamming)");

        SimpleIrcClient.IrcEvent event = client.firstOf(SimpleIrcClient.IrcEvent.Type.ERROR);
        assertNotNull("a KILL against us must fire an ERROR event", event);
        assertTrue("the killing operator must be named: " + event.getMessage(),
                event.getMessage().contains("oper"));
        assertTrue("the kill reason must survive: " + event.getMessage(),
                event.getMessage().contains("spamming"));
    }

    @Test
    public void killAgainstAnotherUserIsNotOurDisconnect() {
        RecordingClient client = new RecordingClient();
        client.credentials("me", "runelite", "me");

        client.processLine(":oper!o@staff.example KILL someoneelse :staff.example (spamming)");

        assertNull("another user being killed must not report our own link as dead",
                client.firstOf(SimpleIrcClient.IrcEvent.Type.ERROR));
    }

    @Test
    public void unhandledErrorNumericSurfacesItsText() {
        RecordingClient client = new RecordingClient();

        client.processLine(":server 465 me :You are banned from this server: spamming");

        SimpleIrcClient.IrcEvent event = client.firstOf(SimpleIrcClient.IrcEvent.Type.SERVER_ERROR);
        assertNotNull("an error numeric we do not handle explicitly must still be reported", event);
        assertTrue("the server's explanation must survive: " + event.getMessage(),
                event.getMessage().contains("You are banned from this server: spamming"));
    }

    @Test
    public void informationalNumericsAreNotReportedAsErrors() {
        RecordingClient client = new RecordingClient();

        client.processLine(":server 372 me :- welcome to the message of the day");
        client.processLine(":server 251 me :There are 4000 users on the network");

        assertNull("MOTD and stats numerics are not failures and must stay quiet",
                client.firstOf(SimpleIrcClient.IrcEvent.Type.SERVER_ERROR));
    }

    /** UnrealIRCd's RPL_WHOISASN sits in the error range but is just another WHOIS line. */
    @Test
    public void whoisAsnIsAWhoisLineNotAnError() {
        RecordingClient client = new RecordingClient();

        client.processLine(":server 569 me bob 7922 :is connecting from AS7922 [Comcast Cable Communications, LLC]");

        assertNull("569 is not a failure",
                client.firstOf(SimpleIrcClient.IrcEvent.Type.SERVER_ERROR));
        SimpleIrcClient.IrcEvent event = client.firstOf(SimpleIrcClient.IrcEvent.Type.WHOIS_REPLY);
        assertNotNull("569 is shown with the rest of the WHOIS", event);
        assertEquals("bob is connecting from AS7922 [Comcast Cable Communications, LLC]", event.getMessage());
    }

    /** A WHOWAS answer, found or not, is shown with the WHOIS lines and is never an error. */
    @Test
    public void whowasRepliesAreWhoisLinesNotErrors() {
        RecordingClient client = new RecordingClient();

        client.processLine(":server 314 me bob ~bob host.example * :Bob Smith");
        client.processLine(":server 369 me bob :End of WHOWAS");
        client.processLine(":server 406 me ghost :There was no such nickname");

        assertNull("WHOWAS replies are not failures",
                client.firstOf(SimpleIrcClient.IrcEvent.Type.SERVER_ERROR));
        List<String> lines = new ArrayList<>();
        for (SimpleIrcClient.IrcEvent event : client.events) {
            if (event.getType() == SimpleIrcClient.IrcEvent.Type.WHOIS_REPLY) lines.add(event.getMessage());
        }
        assertEquals(java.util.Arrays.asList(
                "bob was ~bob@host.example (Bob Smith)",
                "End of WHOWAS for bob",
                "ghost: There was no such nickname"), lines);
    }

    /**
     * The real host and IP come in their own line - 378, or 338 on some servers, in either the
     * middle parameters or the text - during both WHOIS and WHOWAS. They used to be dropped.
     */
    @Test
    public void hostAndIpLinesAreShown() {
        RecordingClient client = new RecordingClient();

        client.processLine(":server 378 me bob :is connecting from *@cpe.example.net 203.0.113.7");
        client.processLine(":server 338 me bob :is actually ~bob@cpe.example.net [203.0.113.7]");
        client.processLine(":server 338 me bob 203.0.113.7 :actually using host");

        List<String> lines = new ArrayList<>();
        for (SimpleIrcClient.IrcEvent event : client.events) {
            if (event.getType() == SimpleIrcClient.IrcEvent.Type.WHOIS_REPLY) lines.add(event.getMessage());
        }
        assertEquals(java.util.Arrays.asList(
                "bob is connecting from *@cpe.example.net 203.0.113.7",
                "bob is actually ~bob@cpe.example.net [203.0.113.7]",
                "bob 203.0.113.7 actually using host"), lines);
    }

    @Test
    public void numericWithItsOwnHandlerIsNotAlsoReportedGenerically() {
        RecordingClient client = new RecordingClient();

        client.processLine(":server 433 me takenNick :Nickname is already in use");

        assertNotNull("433 keeps its dedicated handling",
                client.firstOf(SimpleIrcClient.IrcEvent.Type.NICK_IN_USE));
        assertNull("a numeric we handle explicitly must not be double-reported",
                client.firstOf(SimpleIrcClient.IrcEvent.Type.SERVER_ERROR));
    }

    @Test
    public void unknownHostIsExplainedRatherThanEchoedBack() {
        // UnknownHostException.getMessage() is just the hostname, so surfacing it raw showed the
        // user "Error: irc.swiftirc.net" and nothing else.
        String described = SimpleIrcClient.describeFailure(
                SimpleIrcClient.ConnectPhase.CONNECTING, "irc.swiftirc.net", 6697,
                new java.net.UnknownHostException("irc.swiftirc.net"));

        assertTrue("must say what went wrong, not just echo the host: " + described,
                described.toLowerCase().contains("dns"));
        assertTrue("must still name the host: " + described,
                described.contains("irc.swiftirc.net"));
    }

    @Test
    public void readTimeoutIsExplainedAsALostConnectionWithItsDuration() {
        String described = SimpleIrcClient.describeFailure(
                SimpleIrcClient.ConnectPhase.ESTABLISHED, "irc.swiftirc.net", 6697,
                new java.net.SocketTimeoutException("Read timed out"));

        assertTrue("must explain the silence, not just relay 'Read timed out': " + described,
                described.contains("240"));
    }

    @Test
    public void failureNamesThePhaseAndTheExceptionType() {
        String described = SimpleIrcClient.describeFailure(
                SimpleIrcClient.ConnectPhase.TLS_HANDSHAKE, "irc.swiftirc.net", 6697,
                new javax.net.ssl.SSLHandshakeException("certificate unknown"));

        assertTrue("must name the phase: " + described,
                described.toLowerCase().contains("tls"));
        assertTrue("must name the exception type: " + described,
                described.contains("SSLHandshakeException"));
        assertTrue("must carry the underlying message: " + described,
                described.contains("certificate unknown"));
        assertTrue("must name where we were connecting: " + described,
                described.contains("irc.swiftirc.net:6697"));
    }

    // Raw line logging exists so a user can hand us the log. That makes redaction a correctness
    // requirement, not a nicety: the SASL exchange and NickServ traffic carry the password.

    @Test
    public void saslResponseIsRedactedFromTheLog() {
        String redacted = SimpleIrcClient.redactForLog("AUTHENTICATE AGFjY291bnQAaHVudGVyMg==");

        assertFalse("the SASL PLAIN payload decodes to the password: " + redacted,
                redacted.contains("AGFjY291bnQAaHVudGVyMg=="));
        assertTrue("the exchange must still be visible: " + redacted,
                redacted.startsWith("AUTHENTICATE"));
    }

    @Test
    public void saslMechanismAndContinuationAreKept() {
        // These carry no secret and are exactly what you need to see to debug a stalled CAP/SASL.
        assertEquals("AUTHENTICATE PLAIN", SimpleIrcClient.redactForLog("AUTHENTICATE PLAIN"));
        assertEquals("AUTHENTICATE +", SimpleIrcClient.redactForLog("AUTHENTICATE +"));
    }

    @Test
    public void nickServPasswordIsRedactedFromTheLog() {
        String redacted = SimpleIrcClient.redactForLog("PRIVMSG NickServ :IDENTIFY hunter2");

        assertFalse("the NickServ password must not reach the log: " + redacted,
                redacted.contains("hunter2"));
        assertTrue("the fact that we identified must still be visible: " + redacted,
                redacted.contains("NickServ"));
    }

    @Test
    public void serverPasswordIsRedactedFromTheLog() {
        String redacted = SimpleIrcClient.redactForLog("PASS s3rverp4ss");

        assertFalse("the server password must not reach the log: " + redacted,
                redacted.contains("s3rverp4ss"));
    }

    @Test
    public void ordinaryTrafficIsLoggedVerbatim() {
        String line = ":nick!user@host PRIVMSG #rshelp :hello there";
        assertEquals("redaction must not damage the lines we actually want to read",
                line, SimpleIrcClient.redactForLog(line));
    }

    @Test
    public void disconnectCarriesTheReasonTheServerGave() {
        RecordingClient client = new RecordingClient();
        client.credentials("me", "runelite", "me");
        client.processLine(":server 001 me :Welcome to SwiftIRC");
        client.processLine("ERROR :Closing Link: me[1.2.3.4] (Ping timeout: 240 seconds)");

        client.disconnect();

        SimpleIrcClient.IrcEvent event = client.firstOf(SimpleIrcClient.IrcEvent.Type.DISCONNECT);
        assertNotNull("disconnecting must still announce itself", event);
        assertNotNull("a disconnect with a known cause must carry it", event.getMessage());
        assertTrue("the cause must reach the disconnect message: " + event.getMessage(),
                event.getMessage().contains("Ping timeout: 240 seconds"));
    }

    @Test
    public void deliberateQuitReportsNoFailureReason() {
        RecordingClient client = new RecordingClient();
        client.credentials("me", "runelite", "me");
        client.processLine(":server 001 me :Welcome to SwiftIRC");

        client.disconnect();

        SimpleIrcClient.IrcEvent event = client.firstOf(SimpleIrcClient.IrcEvent.Type.DISCONNECT);
        assertNotNull(event);
        assertNull("a clean /quit has no failure to report", event.getMessage());
    }

    @Test
    public void serverErrorReportsItsOwnTextNotAnEarlierReason() {
        // The recorded disconnect reason is first-writer-wins, so reporting the field rather than
        // the line's own text made the ERROR event repeat the KILL reason under "closed the link".
        RecordingClient client = new RecordingClient();
        client.credentials("me", "runelite", "me");
        client.processLine(":oper!o@staff.example KILL me :staff.example (spamming)");

        client.processLine("ERROR :Closing Link: me[1.2.3.4] (Killed)");

        SimpleIrcClient.IrcEvent last = client.lastOf(SimpleIrcClient.IrcEvent.Type.ERROR);
        assertTrue("the ERROR line must report its own text: " + last.getMessage(),
                last.getMessage().contains("Closing Link"));
    }

    @Test
    public void tlsSocketIsConfiguredToVerifyTheServerHostname() throws Exception {
        // SSLSocket validates the certificate chain but does NOT check that the certificate
        // belongs to the host we asked for, unless endpoint identification is turned on. Without
        // it, any certificate signed by a trusted CA - for any hostname at all - is accepted.
        javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket)
                javax.net.ssl.SSLSocketFactory.getDefault().createSocket();

        SimpleIrcClient.applyTlsSettings(socket);

        assertEquals("the socket must verify the hostname against the certificate",
                "HTTPS", socket.getSSLParameters().getEndpointIdentificationAlgorithm());
    }

    @Test
    public void anUnconfiguredTlsSocketWouldNotVerifyTheHostname() throws Exception {
        // Pins the JDK default this guards against, so the test above cannot silently start
        // passing for the wrong reason.
        javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket)
                javax.net.ssl.SSLSocketFactory.getDefault().createSocket();

        assertNull("JDK default is no hostname verification",
                socket.getSSLParameters().getEndpointIdentificationAlgorithm());
    }

    // Channel keys are passwords too, and they travel in both directions: we send them on JOIN
    // and MODE, and the server hands them back in a MODE change and in RPL_CHANNELMODEIS.

    @Test
    public void joinKeyIsRedactedFromTheLog() {
        String redacted = SimpleIrcClient.redactForLog("JOIN #secret hunter2");

        assertFalse("the channel key must not reach the log: " + redacted,
                redacted.contains("hunter2"));
        assertTrue("which channel we joined must still be visible: " + redacted,
                redacted.contains("#secret"));
    }

    @Test
    public void multipleJoinKeysAreRedacted() {
        String redacted = SimpleIrcClient.redactForLog("JOIN #a,#b key1,key2");

        assertFalse("no key may survive: " + redacted, redacted.contains("key1"));
        assertFalse("no key may survive: " + redacted, redacted.contains("key2"));
    }

    @Test
    public void joinWithoutAKeyIsLoggedVerbatim() {
        assertEquals("an ordinary join must not be damaged",
                "JOIN #rshelp", SimpleIrcClient.redactForLog("JOIN #rshelp"));
    }

    @Test
    public void modeKeyWeSetIsRedacted() {
        String redacted = SimpleIrcClient.redactForLog("MODE #chan +k hunter2");

        assertFalse("the key we set must not reach the log: " + redacted,
                redacted.contains("hunter2"));
        assertTrue("the mode change itself must stay visible: " + redacted,
                redacted.contains("+k"));
    }

    @Test
    public void modeKeyFromAnotherUserIsRedacted() {
        String redacted = SimpleIrcClient.redactForLog(":op!u@host MODE #chan +k hunter2");

        assertFalse("an incoming key must not reach the log either: " + redacted,
                redacted.contains("hunter2"));
    }

    @Test
    public void modeKeyRemovalIsRedacted() {
        // Removing a key names it on most ircds, so -k leaks just as readily as +k.
        String redacted = SimpleIrcClient.redactForLog(":op!u@host MODE #chan -k hunter2");

        assertFalse("the key must not reach the log: " + redacted, redacted.contains("hunter2"));
    }

    @Test
    public void channelModeReplyKeyIsRedacted() {
        // RPL_CHANNELMODEIS: the server volunteers the key when we join a keyed channel.
        String redacted = SimpleIrcClient.redactForLog(":server 324 me #chan +nk hunter2");

        assertFalse("the server's echo of the key must not reach the log: " + redacted,
                redacted.contains("hunter2"));
        assertTrue("the mode string must stay visible: " + redacted, redacted.contains("+nk"));
    }

    @Test
    public void modeChangesWithoutAKeyAreLoggedVerbatim() {
        String line = ":op!u@host MODE #chan +o someone";
        assertEquals("ordinary mode changes must not be damaged",
                line, SimpleIrcClient.redactForLog(line));
    }

    @Test
    public void chanServPasswordIsRedactedFromTheLog() {
        // Reachable today: /cs routes to ChanServ, whose IDENTIFY carries a channel password.
        String redacted = SimpleIrcClient.redactForLog("PRIVMSG ChanServ :IDENTIFY #chan hunter2");

        assertFalse("the channel password must not reach the log: " + redacted,
                redacted.contains("hunter2"));
        assertTrue("the service we talked to must stay visible: " + redacted,
                redacted.contains("ChanServ"));
    }

    @Test
    public void bareServiceAliasPasswordIsRedacted() {
        // Not reachable through the current command set, but the log is only safe to hand over
        // if this holds regardless of how the line was produced.
        assertFalse("bare NS alias must be redacted too",
                SimpleIrcClient.redactForLog("NS IDENTIFY hunter2").contains("hunter2"));
        assertFalse("bare CS alias must be redacted too",
                SimpleIrcClient.redactForLog("CS IDENTIFY #chan hunter2").contains("hunter2"));
    }

    @Test
    public void harmlessServiceCommandsAreLoggedVerbatim() {
        String line = "PRIVMSG ChanServ :INFO #rshelp";
        assertEquals("a service command with no secret must not be damaged",
                line, SimpleIrcClient.redactForLog(line));
    }

    @Test
    public void ordinaryChannelTalkAboutIdentifyingIsNotRedacted() {
        String line = "PRIVMSG #rshelp :you need to identify to nickserv first";
        assertEquals("redaction must not fire on ordinary conversation",
                line, SimpleIrcClient.redactForLog(line));
    }

    @Test
    public void setPasswordIsRedactedForBothServices() {
        // ChanServ puts the channel between the verb and the keyword: SET <chan> PASSWORD <pw>,
        // while NickServ has no middle argument. Both must be caught.
        assertFalse("ChanServ SET PASSWORD must be redacted",
                SimpleIrcClient.redactForLog("PRIVMSG ChanServ :SET #chan PASSWORD hunter2")
                        .contains("hunter2"));
        assertFalse("NickServ SET PASSWORD must be redacted",
                SimpleIrcClient.redactForLog("PRIVMSG NickServ :SET PASSWORD hunter2")
                        .contains("hunter2"));
    }

    @Test
    public void nonPasswordSettingsAreLoggedVerbatim() {
        String line = "PRIVMSG ChanServ :SET #chan SECURE on";
        assertEquals("settings that carry no secret must not be damaged",
                line, SimpleIrcClient.redactForLog(line));
    }
}
