package com.irc;

import org.junit.Test;

import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class IrcAdapterNetworkTest {
    private static final String RIZON = "rizon-id";

    private static IrcConfig stubConfig() {
        return new IrcConfig() {
            @Override public String username() { return "me"; }
            @Override public String password() { return ""; }
        };
    }

    private static NetworkConfig rizon() {
        return NetworkConfig.builder().id(RIZON).name("Rizon").host("irc.rizon.net").port(6697).tls(true)
                .serverPassword("me/rizon:pw").build();
    }

    private static IrcMessage last(List<IrcMessage> messages, String content) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (content.equals(messages.get(i).getContent())) return messages.get(i);
        }
        throw new AssertionError("no message " + content);
    }

    @Test
    public void usesTheNetworksAddressAndPassword() {
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), rizon(), m -> { }, null, "me");
        assertEquals("irc.rizon.net", adapter.getClient().getHost());
        assertEquals(6697, adapter.getClient().getPort());
        assertTrue(adapter.getClient().isSecure());
        assertEquals("me/rizon:pw", adapter.getClient().getServerPassword());
        assertEquals(RIZON, adapter.getNetworkId());
    }

    @Test
    public void messagesAreStampedWithTheNetwork() {
        List<IrcMessage> messages = new ArrayList<>();
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), rizon(), messages::add, null, "me");
        adapter.getClient().processLine(":Ash!u@h PRIVMSG #chan :hi");
        assertEquals(RIZON, last(messages, "hi").getNetworkId());
    }

    @Test
    public void legacyInitializeIsSwiftIrc() {
        List<IrcMessage> messages = new ArrayList<>();
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), messages::add, null, "me");
        adapter.getClient().processLine(":Ash!u@h PRIVMSG #chan :hi");
        assertEquals(NetworkConfig.SWIFTIRC_ID, last(messages, "hi").getNetworkId());
        assertEquals("fiery.swiftirc.net", adapter.getClient().getHost());
    }

    @Test
    public void linesTimestampedBeforeRegistrationAreHistory() {
        List<IrcMessage> messages = new ArrayList<>();
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), rizon(), messages::add, null, "me");
        adapter.getClient().processLine(":server 001 me :Welcome");
        Instant hourAgo = Instant.now().minus(Duration.ofHours(1));
        adapter.getClient().processLine("@time=" + hourAgo + " :Ash!u@h PRIVMSG #chan :old");
        adapter.getClient().processLine("@time=" + Instant.now() + " :Ash!u@h PRIVMSG #chan :new");
        IrcMessage old = last(messages, "old");
        assertEquals(IrcMessage.MessageType.HISTORY, old.getType());
        assertEquals(hourAgo, old.getTimestamp());
        assertEquals(IrcMessage.MessageType.CHAT, last(messages, "new").getType());
    }

    @Test
    public void unparseableTimeTagIsLive() {
        List<IrcMessage> messages = new ArrayList<>();
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), rizon(), messages::add, null, "me");
        adapter.getClient().processLine(":server 001 me :Welcome");
        Instant before = Instant.now();
        adapter.getClient().processLine("@time=yesterday :Ash!u@h PRIVMSG #chan :odd");
        IrcMessage odd = last(messages, "odd");
        assertEquals(IrcMessage.MessageType.CHAT, odd.getType());
        assertFalse(odd.getTimestamp().isBefore(before));
    }

    @Test
    public void futureTimeTagIsLiveAndStampedNow() {
        List<IrcMessage> messages = new ArrayList<>();
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), rizon(), messages::add, null, "me");
        adapter.getClient().processLine(":server 001 me :Welcome");
        Instant future = Instant.now().plus(Duration.ofHours(2));
        adapter.getClient().processLine("@time=" + future + " :Ash!u@h PRIVMSG #chan :skewed");
        IrcMessage skewed = last(messages, "skewed");
        assertEquals(IrcMessage.MessageType.CHAT, skewed.getType());
        assertTrue(skewed.getTimestamp().isBefore(future));
    }

    @Test
    public void ownLineFromAnotherClientLandsInTheQuery() {
        List<IrcMessage> messages = new ArrayList<>();
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), rizon(), messages::add, null, "me");
        adapter.getClient().processLine(":server 001 me :Welcome");
        adapter.getClient().processLine(":me!u@h PRIVMSG Luna :from my phone");
        IrcMessage line = last(messages, "from my phone");
        assertEquals("Luna", line.getChannel());
        assertEquals("me", line.getSender());
    }

    @Test
    public void nickChangeRenamesOnlyThisNetworksBuffer() throws Exception {
        IrcPanel panel = headlessPanel();
        panel.addChannel(BufferKey.swiftIrc("Luna"));
        panel.addChannel(BufferKey.of(RIZON, "Luna"));
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), rizon(), m -> { }, panel, "me");
        adapter.getClient().processLine(":Luna!u@h NICK Luna2");
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(panel.isPane(BufferKey.swiftIrc("Luna")));
        assertTrue(panel.isPane(BufferKey.of(RIZON, "Luna2")));
    }

    @Test
    public void privateMessageForAnotherNetworksBufferGoesToThisNetworksSystem() throws Exception {
        IrcPanel panel = headlessPanel();
        panel.addChannel("#rshelp");
        panel.setFocusedChannel("#rshelp");
        List<IrcMessage> messages = new ArrayList<>();
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), rizon(), messages::add, panel, "me");
        adapter.getClient().processLine(":Luna!u@h PRIVMSG me :psst");
        IrcMessage pm = last(messages, "psst");
        assertEquals("System", pm.getChannel());
        assertEquals(RIZON, pm.getNetworkId());
    }

    @Test
    public void detachedAdapterEmitsNothing() throws Exception {
        List<String> panelCalls = new ArrayList<>();
        IrcPanel panel = new IrcPanel() {
            @Override public void setNetworkConnected(String networkId, boolean connected) {
                panelCalls.add("connected " + connected);
            }
            @Override public void setChannelUsers(BufferKey channel, List<ChannelUserList.Entry> entries) {
                panelCalls.add("users " + channel.getName());
            }
        };
        List<IrcMessage> messages = new ArrayList<>();
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), rizon(), messages::add, panel, "me");
        adapter.getClient().processLine(":server 001 me :Welcome");
        adapter.getClient().processLine(":me!u@h JOIN #chan");
        markConnected(adapter.getClient());
        SwingUtilities.invokeAndWait(() -> { });
        messages.clear();
        panelCalls.clear();

        adapter.detach();
        adapter.getClient().processLine(":Ash!u@h PRIVMSG #chan :late");
        adapter.disconnect("Reloading, brb");
        SwingUtilities.invokeAndWait(() -> { });

        assertTrue(messages.toString(), messages.isEmpty());
        assertTrue(panelCalls.toString(), panelCalls.isEmpty());
    }

    private static void markConnected(SimpleIrcClient client) throws Exception {
        Field connected = SimpleIrcClient.class.getDeclaredField("connected");
        connected.setAccessible(true);
        connected.set(client, true);
    }

    private static IrcPanel headlessPanel() throws Exception {
        IrcPanel panel = new IrcPanel();
        Field config = IrcPanel.class.getDeclaredField("config");
        config.setAccessible(true);
        config.set(panel, stubConfig());
        Field tabs = IrcPanel.class.getDeclaredField("tabbedPane");
        tabs.setAccessible(true);
        tabs.set(panel, new JTabbedPane());
        panel.addChannel("System");
        panel.setFocusedChannel("System");
        return panel;
    }
}
