package com.irc;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import static org.junit.Assert.*;

public class SimpleIrcClientTest {

    @Test
    public void historyBatchEventCarriesMessages() {
        SimpleIrcClient.IrcEvent inner = new SimpleIrcClient.IrcEvent(
            SimpleIrcClient.IrcEvent.Type.MESSAGE, "nick", "#chan", "hello", "2024-01-01T12:00:00.000Z"
        );
        List<SimpleIrcClient.IrcEvent> batch = List.of(inner);

        SimpleIrcClient.IrcEvent batchEvent = new SimpleIrcClient.IrcEvent(
            SimpleIrcClient.IrcEvent.Type.HISTORY_BATCH, null, "#chan", null, null, batch
        );

        assertEquals(SimpleIrcClient.IrcEvent.Type.HISTORY_BATCH, batchEvent.getType());
        assertEquals("#chan", batchEvent.getTarget());
        assertNotNull(batchEvent.getHistoryMessages());
        assertEquals(1, batchEvent.getHistoryMessages().size());
        assertEquals("hello", batchEvent.getHistoryMessages().get(0).getMessage());
    }

    @Test
    public void existingFiveArgConstructorSetsHistoryMessagesToNull() {
        SimpleIrcClient.IrcEvent event = new SimpleIrcClient.IrcEvent(
            SimpleIrcClient.IrcEvent.Type.MESSAGE, "nick", "#chan", "hello", null
        );
        assertNull(event.getHistoryMessages());
    }

    @Test
    public void tagParsingExtractsTimeAndBatch() {
        TestableIrcClient client = new TestableIrcClient();
        client.processLine("@time=2024-01-01T12:00:00.000Z;batch=ref1 :nick!user@host PRIVMSG #chan :hello");
        assertEquals("2024-01-01T12:00:00.000Z", client.getLastTagTime());
        assertEquals("ref1", client.getLastTagBatch());
    }

    @Test
    public void noTagsLeavesFieldsNull() {
        TestableIrcClient client = new TestableIrcClient();
        client.processLine(":nick!user@host PRIVMSG #chan :hello");
        assertNull(client.getLastTagTime());
        assertNull(client.getLastTagBatch());
    }

    @Test
    public void capLsWithChathistoryRequestsCaps() {
        TestableIrcClient client = new TestableIrcClient();
        // Final LS line (no continuation *): params = [*, LS, cap-list]
        client.processLine(":server CAP * LS :chathistory batch server-time");
        assertTrue(client.sentLines.stream().anyMatch(l -> l.startsWith("CAP REQ")));
        assertTrue(client.sentLines.stream().anyMatch(l -> l.contains("chathistory")));
    }

    @Test
    public void capLsWithCapValueStillRequestsChathistory() {
        TestableIrcClient client = new TestableIrcClient();
        // Server advertises chathistory with a value (e.g. max limit)
        client.processLine(":server CAP * LS :chathistory=50 batch server-time");
        assertTrue(client.sentLines.stream().anyMatch(l -> l.startsWith("CAP REQ")));
        assertTrue(client.sentLines.stream().anyMatch(l -> l.contains("chathistory")));
    }

    @Test
    public void capLsWithoutChathistorySendsCapEnd() {
        TestableIrcClient client = new TestableIrcClient();
        client.processLine(":server CAP * LS :multi-prefix");
        assertTrue(client.sentLines.contains("CAP END"));
        assertFalse(client.isCapHistorySupported());
    }

    @Test
    public void capAckWithChathistorySetsFlag() {
        TestableIrcClient client = new TestableIrcClient();
        client.processLine(":server CAP * LS :chathistory batch server-time");
        client.sentLines.clear();
        client.processLine(":server CAP * ACK :chathistory batch server-time");
        assertTrue(client.isCapHistorySupported());
        assertTrue(client.sentLines.contains("CAP END"));
    }

    @Test
    public void capNakSendsCapEndAndLeavesHistoryUnsupported() {
        TestableIrcClient client = new TestableIrcClient();
        client.processLine(":server CAP * LS :chathistory");
        client.sentLines.clear();
        client.processLine(":server CAP * NAK :chathistory batch server-time");
        assertFalse(client.isCapHistorySupported());
        assertTrue(client.sentLines.contains("CAP END"));
    }

    @Test
    public void capEndSentOnlyOnce() {
        TestableIrcClient client = new TestableIrcClient();
        client.processLine(":server CAP * LS :chathistory");
        client.processLine(":server CAP * ACK :chathistory");
        client.processLine(":server CAP * ACK :batch"); // second ACK line
        long count = client.sentLines.stream().filter("CAP END"::equals).count();
        assertEquals(1, count);
    }

    @Test
    public void multiLineCapLsAccumulatesBeforeSendingReq() {
        TestableIrcClient client = new TestableIrcClient();
        // Continuation line — has * before the cap list
        client.processLine(":server CAP * LS * :batch server-time");
        assertTrue(client.sentLines.isEmpty()); // no CAP REQ yet
        // Final line — no continuation *
        client.processLine(":server CAP * LS :chathistory");
        assertTrue(client.sentLines.stream().anyMatch(l -> l.contains("chathistory")));
    }

    @Test
    public void batchAccumulatesMessagesAndFiresHistoryBatchEvent() {
        TestableIrcClient client = new TestableIrcClient();
        List<SimpleIrcClient.IrcEvent> received = new ArrayList<>();
        client.addEventListener(event -> {
            if (event.getType() == SimpleIrcClient.IrcEvent.Type.HISTORY_BATCH) {
                received.add(event);
            }
        });

        client.processLine(":server BATCH +ref1 chathistory #chan");
        client.processLine("@time=2024-01-01T10:00:00.000Z;batch=ref1 :alice!a@host PRIVMSG #chan :hello");
        client.processLine("@time=2024-01-01T10:01:00.000Z;batch=ref1 :bob!b@host PRIVMSG #chan :world");
        client.processLine(":server BATCH -ref1");

        assertEquals(1, received.size());
        SimpleIrcClient.IrcEvent batch = received.get(0);
        assertEquals("#chan", batch.getTarget());
        assertEquals(2, batch.getHistoryMessages().size());
        assertEquals("hello", batch.getHistoryMessages().get(0).getMessage());
        assertEquals("2024-01-01T10:00:00.000Z", batch.getHistoryMessages().get(0).getAdditionalData());
        assertEquals("world", batch.getHistoryMessages().get(1).getMessage());
    }

    @Test
    public void batchTaggedMessageDoesNotFireNormalMessageEvent() {
        TestableIrcClient client = new TestableIrcClient();
        List<SimpleIrcClient.IrcEvent> messages = new ArrayList<>();
        client.addEventListener(event -> {
            if (event.getType() == SimpleIrcClient.IrcEvent.Type.MESSAGE) {
                messages.add(event);
            }
        });

        client.processLine(":server BATCH +ref1 chathistory #chan");
        client.processLine("@batch=ref1 :alice!a@host PRIVMSG #chan :hello");
        client.processLine(":server BATCH -ref1");

        assertTrue("Batch-tagged PRIVMSG should not fire a normal MESSAGE event", messages.isEmpty());
    }

    @Test
    public void actionInBatchUsesSourceWithoutStarPrefix() {
        TestableIrcClient client = new TestableIrcClient();
        List<SimpleIrcClient.IrcEvent> received = new ArrayList<>();
        client.addEventListener(event -> {
            if (event.getType() == SimpleIrcClient.IrcEvent.Type.HISTORY_BATCH) received.add(event);
        });

        client.processLine(":server BATCH +ref1 chathistory #chan");
        client.processLine("@batch=ref1 :alice!a@host PRIVMSG #chan :\u0001ACTION waves\u0001");
        client.processLine(":server BATCH -ref1");

        SimpleIrcClient.IrcEvent batch = received.get(0);
        assertEquals(SimpleIrcClient.IrcEvent.Type.ACTION, batch.getHistoryMessages().get(0).getType());
        assertEquals("alice", batch.getHistoryMessages().get(0).getSource()); // no "* " prefix
        assertEquals("waves", batch.getHistoryMessages().get(0).getMessage());
    }

    @Test
    public void disconnectClearsBatchState() {
        TestableIrcClient client = new TestableIrcClient();
        client.processLine(":server BATCH +ref1 chathistory #chan");
        client.disconnect();
        List<SimpleIrcClient.IrcEvent> received = new ArrayList<>();
        client.addEventListener(event -> {
            if (event.getType() == SimpleIrcClient.IrcEvent.Type.HISTORY_BATCH) received.add(event);
        });
        client.processLine(":server BATCH +ref1 chathistory #chan");
        client.processLine("@batch=ref1 :alice!a@host PRIVMSG #chan :hello");
        client.processLine(":server BATCH -ref1");
        assertEquals(1, received.size());
    }

    @Test
    public void selfJoinRequestsChatHistoryWhenSupported() {
        TestableIrcClient client = new TestableIrcClient();
        client.credentials("TestUser", "user", "user");
        client.setCapHistorySupportedForTest(true);
        client.processLine(":TestUser!u@host JOIN #chan");
        assertTrue(client.sentLines.stream().anyMatch(l -> l.equals("CHATHISTORY LATEST #chan * 100")));
    }

    @Test
    public void selfJoinDoesNotRequestHistoryWhenUnsupported() {
        TestableIrcClient client = new TestableIrcClient();
        client.credentials("TestUser", "user", "user");
        client.processLine(":TestUser!u@host JOIN #chan");
        assertFalse(client.sentLines.stream().anyMatch(l -> l.startsWith("CHATHISTORY")));
    }

    @Test
    public void otherUserJoinDoesNotRequestHistory() {
        TestableIrcClient client = new TestableIrcClient();
        client.credentials("TestUser", "user", "user");
        client.setCapHistorySupportedForTest(true);
        client.processLine(":OtherUser!u@host JOIN #chan");
        assertFalse(client.sentLines.stream().anyMatch(l -> l.startsWith("CHATHISTORY")));
    }

    @Test
    public void capLsWithSaslRequestsSaslWhenPasswordSet() {
        TestableIrcClient client = new TestableIrcClient();
        client.credentials("TestUser", "user", "user");
        client.sasl("acct", "secret");
        client.processLine(":server CAP * LS :sasl chathistory batch");
        assertTrue(client.sentLines.stream().anyMatch(l -> l.startsWith("CAP REQ") && l.contains("sasl")));
    }

    @Test
    public void capLsDoesNotRequestSaslWhenNoPassword() {
        TestableIrcClient client = new TestableIrcClient();
        client.credentials("TestUser", "user", "user");
        // No sasl() call, so SASL is disabled.
        client.processLine(":server CAP * LS :sasl chathistory");
        assertTrue(client.sentLines.stream().noneMatch(l -> l.contains("sasl")));
    }

    @Test
    public void capAckWithSaslSendsAuthenticatePlainAndDefersCapEnd() {
        TestableIrcClient client = new TestableIrcClient();
        client.credentials("TestUser", "user", "user");
        client.sasl("acct", "secret");
        client.processLine(":server CAP * LS :sasl chathistory");
        client.sentLines.clear();
        client.processLine(":server CAP * ACK :sasl chathistory");
        assertTrue(client.sentLines.contains("AUTHENTICATE PLAIN"));
        assertFalse("CAP END must wait until SASL completes", client.sentLines.contains("CAP END"));
    }

    @Test
    public void authenticatePlusSendsBase64PlainCredentials() {
        TestableIrcClient client = new TestableIrcClient();
        client.credentials("TestUser", "user", "user");
        client.sasl("myaccount", "secret");
        client.processLine("AUTHENTICATE +");

        String authLine = client.sentLines.stream()
                .filter(l -> l.startsWith("AUTHENTICATE ") && !l.equals("AUTHENTICATE PLAIN"))
                .reduce((a, b) -> b).orElse(null);
        assertNotNull("client should send an AUTHENTICATE payload", authLine);

        String decoded = new String(
                Base64.getDecoder().decode(authLine.substring("AUTHENTICATE ".length())),
                StandardCharsets.UTF_8);
        assertEquals("\0myaccount\0secret", decoded);
    }

    @Test
    public void authenticateUsesNickAsAuthcidWhenAccountBlank() {
        TestableIrcClient client = new TestableIrcClient();
        client.credentials("TestUser", "user", "user");
        client.sasl("", "secret");
        client.processLine("AUTHENTICATE +");

        String authLine = client.sentLines.stream()
                .filter(l -> l.startsWith("AUTHENTICATE ") && !l.equals("AUTHENTICATE PLAIN"))
                .reduce((a, b) -> b).orElse(null);
        assertNotNull(authLine);

        String decoded = new String(
                Base64.getDecoder().decode(authLine.substring("AUTHENTICATE ".length())),
                StandardCharsets.UTF_8);
        assertEquals("\0TestUser\0secret", decoded);
    }

    @Test
    public void saslSuccessNumericSendsCapEndAndFiresEvent() {
        TestableIrcClient client = new TestableIrcClient();
        client.credentials("TestUser", "user", "user");
        client.sasl("acct", "secret");
        List<SimpleIrcClient.IrcEvent> events = new ArrayList<>();
        client.addEventListener(e -> {
            if (e.getType() == SimpleIrcClient.IrcEvent.Type.SASL_SUCCESS) events.add(e);
        });
        client.processLine(":server CAP * LS :sasl");
        client.processLine(":server CAP * ACK :sasl");
        client.sentLines.clear();
        client.processLine(":server 903 TestUser :SASL authentication successful");
        assertTrue(client.sentLines.contains("CAP END"));
        assertEquals(1, events.size());
    }

    @Test
    public void saslFailureNumericSendsCapEndAndFiresEvent() {
        TestableIrcClient client = new TestableIrcClient();
        client.credentials("TestUser", "user", "user");
        client.sasl("acct", "secret");
        List<SimpleIrcClient.IrcEvent> events = new ArrayList<>();
        client.addEventListener(e -> {
            if (e.getType() == SimpleIrcClient.IrcEvent.Type.SASL_FAILED) events.add(e);
        });
        client.processLine(":server CAP * LS :sasl");
        client.processLine(":server CAP * ACK :sasl");
        client.sentLines.clear();
        client.processLine(":server 904 TestUser :SASL authentication failed");
        assertTrue("must send CAP END to continue registration even on SASL failure",
                client.sentLines.contains("CAP END"));
        assertEquals(1, events.size());
    }

    /** Test subclass that captures sent lines and exposes internal state. */
    static class TestableIrcClient extends SimpleIrcClient {
        final List<String> sentLines = new ArrayList<>();

        @Override
        public synchronized void sendRawLine(String line) {
            sentLines.add(line);
        }

        String getLastTagTime() { return currentTagTime; }
        String getLastTagBatch() { return currentTagBatch; }
        boolean isCapHistorySupported() { return capHistorySupported; }

        void setCapHistorySupportedForTest(boolean val) {
            capHistorySupported = val;
        }
    }
}
