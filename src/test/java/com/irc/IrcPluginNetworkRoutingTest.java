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
        assertEquals(Arrays.asList("TOPIC #foo :fresh topic", "WHOIS Luna"), rizon.sent);
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
        assertTrue(IrcPlugin.echoesInGame(live));
        assertFalse(IrcPlugin.echoesInGame(live.withNetworkId(RIZON)));
        assertFalse(IrcPlugin.echoesInGame(new IrcMessage("#rshelp", "Ash", "old",
                IrcMessage.MessageType.HISTORY, Instant.now())));
    }
}
