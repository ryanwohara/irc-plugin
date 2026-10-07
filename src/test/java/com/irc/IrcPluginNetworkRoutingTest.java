package com.irc;

import org.junit.Test;

import javax.swing.JTabbedPane;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class IrcPluginNetworkRoutingTest {
    private static final String RIZON = "rizon-id";

    private static class RecordingAdapter extends IrcAdapter {
        final List<String> sent = new ArrayList<>();
        @Override public void sendMessage(String target, String message) { sent.add("PRIVMSG " + target + " :" + message); }
        @Override public void sendRawLine(String line) { sent.add(line); }
        @Override public boolean isConnected() { return true; }
        @Override public void disconnect(String reason) { sent.add("QUIT " + reason); }
        @Override public void joinChannel(String channel, String password) { sent.add("JOIN " + channel); }
    }

    private static IrcConfig stubConfig() {
        return new IrcConfig() {
            @Override public String username() { return "me"; }
            @Override public String password() { return ""; }
        };
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private final RecordingAdapter swift = new RecordingAdapter();
    private final RecordingAdapter rizon = new RecordingAdapter();

    private IrcPlugin plugin() throws Exception {
        IrcPanel panel = new IrcPanel();
        set(IrcPanel.class, panel, "config", stubConfig());
        set(IrcPanel.class, panel, "tabbedPane", new JTabbedPane());
        panel.addChannel("System");
        panel.addChannel(BufferKey.of(RIZON, "#foo"));

        NetworkManager networks = new NetworkManager(new NetworkManager.Connector() {
            @Override public IrcAdapter open(NetworkConfig network) { throw new AssertionError("no real connections"); }
            @Override public void close(IrcAdapter adapter, NetworkConfig network, boolean removed, String reason) { }
        });
        networks.register(NetworkConfig.swiftIrc(stubConfig()), swift);
        networks.register(NetworkConfig.builder().id(RIZON).name("Rizon").host("irc.rizon.net").build(), rizon);

        IrcPlugin plugin = new IrcPlugin();
        set(IrcPlugin.class, plugin, "config", stubConfig());
        set(IrcPlugin.class, plugin, "panel", panel);
        set(IrcPlugin.class, plugin, "networks", networks);
        return plugin;
    }

    private static void send(IrcPlugin plugin, BufferKey buffer, String text) throws Exception {
        Method m = IrcPlugin.class.getDeclaredMethod("handleMessageSend", BufferKey.class, String.class);
        m.setAccessible(true);
        m.invoke(plugin, buffer, text);
    }

    @Test
    public void chatGoesToTheBuffersNetworkOnly() throws Exception {
        IrcPlugin plugin = plugin();
        send(plugin, BufferKey.of(RIZON, "#foo"), "hello");
        assertEquals(Collections.singletonList("PRIVMSG #foo :hello"), rizon.sent);
        assertTrue(swift.sent.isEmpty());
    }

    @Test
    public void commandsActOnTheBuffersNetwork() throws Exception {
        IrcPlugin plugin = plugin();
        send(plugin, BufferKey.of(RIZON, "#foo"), "/topic fresh topic");
        send(plugin, BufferKey.of(RIZON, "#foo"), "/whois Luna");
        send(plugin, BufferKey.of(RIZON, "#foo"), "/whowas Luna");
        assertEquals(Arrays.asList("TOPIC #foo :fresh topic", "WHOIS Luna", "WHOWAS Luna"), rizon.sent);
        assertTrue(swift.sent.isEmpty());
    }

    @Test
    public void quitDisconnectsOnlyThatNetwork() throws Exception {
        IrcPlugin plugin = plugin();
        send(plugin, BufferKey.of(RIZON, "#foo"), "/quit bye");
        assertEquals(Collections.singletonList("QUIT bye"), rizon.sent);
        assertTrue(swift.sent.isEmpty());
    }

    @Test
    public void onlySwiftIrcLiveTrafficEchoesInGame() {
        IrcMessage live = new IrcMessage("#rshelp", "Ash", "hi", IrcMessage.MessageType.CHAT, Instant.now());
        assertTrue(IrcPlugin.echoesInGame(live, NetworkConfig.SWIFTIRC_ID));
        assertFalse(IrcPlugin.echoesInGame(live.withNetworkId(RIZON), NetworkConfig.SWIFTIRC_ID));
        assertFalse(IrcPlugin.echoesInGame(new IrcMessage("#rshelp", "Ash", "old",
                IrcMessage.MessageType.HISTORY, Instant.now()), NetworkConfig.SWIFTIRC_ID));
    }

    @Test(timeout = 10000)
    public void connectorCloseDoesNotBlockOnAStalledDisconnect() throws Exception {
        IrcPlugin plugin = plugin();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        RecordingAdapter stalled = new RecordingAdapter() {
            @Override public void disconnect(String reason) {
                entered.countDown();
                try { release.await(); } catch (InterruptedException ignored) { }
            }
        };
        Class<?> cls = Class.forName("com.irc.IrcPlugin$PluginConnector");
        java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor(IrcPlugin.class);
        ctor.setAccessible(true);
        NetworkManager.Connector connector = (NetworkManager.Connector) ctor.newInstance(plugin);
        try {
            connector.close(stalled, NetworkConfig.builder().id(RIZON).name("Rizon").host("irc.rizon.net").build(), false, "bye");
            assertTrue("disconnect should have started on another thread", entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, release.getCount());
        } finally {
            release.countDown();
        }
    }

    private static void joinOnOpen(IrcPlugin plugin, IrcAdapter adapter, NetworkConfig network) throws Exception {
        Method m = IrcPlugin.class.getDeclaredMethod("joinOnOpen", IrcAdapter.class, NetworkConfig.class);
        m.setAccessible(true);
        m.invoke(plugin, adapter, network);
    }

    private static IrcPanel panelOf(IrcPlugin plugin) throws Exception {
        Field field = IrcPlugin.class.getDeclaredField("panel");
        field.setAccessible(true);
        return (IrcPanel) field.get(plugin);
    }

    @Test
    public void defaultChannelIsJoinedOnlyOnTheFirstOpen() throws Exception {
        IrcPlugin plugin = plugin();
        NetworkConfig swiftIrc = NetworkConfig.swiftIrc(stubConfig());
        RecordingAdapter first = new RecordingAdapter();
        joinOnOpen(plugin, first, swiftIrc);
        assertEquals(Collections.singletonList("JOIN #rshelp"), first.sent);

        // The user parted it, then reconnected: it stays parted.
        RecordingAdapter second = new RecordingAdapter();
        joinOnOpen(plugin, second, swiftIrc);
        assertTrue(second.sent.toString(), second.sent.isEmpty());

        // Its buffer is open again: the reconnect rejoins it, once.
        panelOf(plugin).addChannel("#rshelp");
        RecordingAdapter third = new RecordingAdapter();
        joinOnOpen(plugin, third, swiftIrc);
        assertEquals(Collections.singletonList("JOIN #rshelp"), third.sent);
    }

    @Test
    public void normalizedDefaultChannelIsNotJoinedTwice() throws Exception {
        IrcPlugin plugin = plugin();
        IrcConfig bare = new IrcConfig() {
            @Override public String username() { return "me"; }
            @Override public String password() { return ""; }
            @Override public String channel() { return "RSHelp"; }
        };
        set(IrcPlugin.class, plugin, "config", bare);
        panelOf(plugin).addChannel("#rshelp");
        RecordingAdapter adapter = new RecordingAdapter();
        joinOnOpen(plugin, adapter, NetworkConfig.swiftIrc(bare));
        assertEquals(Collections.singletonList("JOIN #rshelp"), adapter.sent);
    }

    private static IrcConfig orderedConfig(String networks, String networkOrder, String channelOrder) {
        return new IrcConfig() {
            @Override public String username() { return "me"; }
            @Override public String password() { return ""; }
            @Override public String networks() { return networks; }
            @Override public String networkOrder() { return networkOrder; }
            @Override public String channelOrder() { return channelOrder; }
        };
    }

    @Test
    public void joinsFollowTheSavedChannelOrderThenTheRest() throws Exception {
        IrcPlugin plugin = plugin();
        set(IrcPlugin.class, plugin, "gson", new com.google.gson.Gson());
        set(IrcPlugin.class, plugin, "config", orderedConfig("", "", "{\"rizon-id\":[\"#b\",\"#A\"]}"));
        panelOf(plugin).addChannel(BufferKey.of(RIZON, "#b"));
        RecordingAdapter adapter = new RecordingAdapter();
        joinOnOpen(plugin, adapter, NetworkConfig.builder().id(RIZON).name("Rizon").host("irc.rizon.net")
                .autojoin("#x,#a,#b").build());
        assertEquals(Arrays.asList("JOIN #b", "JOIN #a", "JOIN #x", "JOIN #foo"), adapter.sent);
    }

    @Test
    public void networksAreAppliedInTheSavedOrder() throws Exception {
        IrcPlugin plugin = plugin();
        set(IrcPlugin.class, plugin, "gson", new com.google.gson.Gson());
        set(IrcPlugin.class, plugin, "config", orderedConfig(
                "[{\"id\":\"a1\",\"name\":\"Rizon\",\"host\":\"irc.rizon.net\"},"
                        + "{\"id\":\"b2\",\"name\":\"Libera\",\"host\":\"irc.libera.chat\"}]",
                "b2,gone,swiftirc", ""));
        Method m = IrcPlugin.class.getDeclaredMethod("networkConfigs");
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<NetworkConfig> configs = (List<NetworkConfig>) m.invoke(plugin);
        List<String> ids = new ArrayList<>();
        for (NetworkConfig network : configs) ids.add(network.getId());
        assertEquals(Arrays.asList("b2", "swiftirc", "a1"), ids);
    }

    @Test
    public void closingANetworkEmptiesItsUserListsOnly() throws Exception {
        IrcPlugin plugin = plugin();
        IrcPanel panel = panelOf(plugin);
        panel.addChannel("#rshelp");
        List<ChannelUserList.Entry> users = Collections.singletonList(new ChannelUserList.Entry("Ash", "@", 0));
        panel.setChannelUsers(BufferKey.of(RIZON, "#foo"), users);
        panel.setChannelUsers(BufferKey.swiftIrc("#rshelp"), users);
        Class<?> cls = Class.forName("com.irc.IrcPlugin$PluginConnector");
        java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor(IrcPlugin.class);
        ctor.setAccessible(true);
        NetworkManager.Connector connector = (NetworkManager.Connector) ctor.newInstance(plugin);
        connector.close(rizon, NetworkConfig.builder().id(RIZON).name("Rizon").host("irc.rizon.net").build(), false, "bye");
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        Field field = IrcPanel.class.getDeclaredField("channelUserSnapshots");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<BufferKey, List<ChannelUserList.Entry>> snapshots =
                (java.util.Map<BufferKey, List<ChannelUserList.Entry>>) field.get(panel);
        assertEquals(Collections.emptyList(), snapshots.get(BufferKey.of(RIZON, "#foo").folded()));
        assertEquals(users, snapshots.get(BufferKey.swiftIrc("#rshelp").folded()));
    }
}
