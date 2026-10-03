package com.irc;

import com.google.gson.Gson;
import net.runelite.client.events.ConfigChanged;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Turning SwiftIRC's own connection off, and choosing which network feeds the game chat. */
public class SwiftIrcSwitchTest {
    private static final String ZNC = "znc-id";

    /** A config whose two new settings can be flipped between calls. */
    private static class Settings implements IrcConfig {
        boolean swiftIrcEnabled = true;
        String inGameNetwork = NetworkConfig.SWIFTIRC_ID;
        String networks = "";

        @Override public String username() { return "me"; }
        @Override public String password() { return ""; }
        @Override public boolean swiftIrcEnabled() { return swiftIrcEnabled; }
        @Override public String inGameNetwork() { return inGameNetwork; }
        @Override public String networks() { return networks; }
    }

    private final List<String> calls = new ArrayList<>();
    private final Settings settings = new Settings();

    private IrcPlugin plugin() throws Exception {
        NetworkManager networks = new NetworkManager(new NetworkManager.Connector() {
            @Override public IrcAdapter open(NetworkConfig network) {
                calls.add("open " + network.getId());
                return new IrcAdapter();
            }
            @Override public void close(IrcAdapter adapter, NetworkConfig network, boolean removed, String reason) {
                calls.add("close " + network.getId());
            }
        });
        IrcPlugin plugin = new IrcPlugin();
        set(plugin, "config", settings);
        set(plugin, "gson", new Gson());
        set(plugin, "networks", networks);
        return plugin;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = IrcPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object call(IrcPlugin plugin, String name) throws Exception {
        Method method = IrcPlugin.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(plugin);
    }

    private static ConfigChanged changed(String key) {
        ConfigChanged event = new ConfigChanged();
        event.setGroup("irc");
        event.setKey(key);
        return event;
    }

    @Test
    public void swiftIrcFollowsItsConnectAutomaticallySetting() {
        assertTrue(NetworkConfig.swiftIrc(settings).isEnabled());
        settings.swiftIrcEnabled = false;
        assertFalse(NetworkConfig.swiftIrc(settings).isEnabled());
    }

    @Test
    public void aDisabledSwiftIrcDoesNotConnectAndTheSwitchTakesEffectLive() throws Exception {
        settings.swiftIrcEnabled = false;
        IrcPlugin plugin = plugin();
        call(plugin, "applyNetworks");
        assertTrue(calls.isEmpty());

        settings.swiftIrcEnabled = true;
        plugin.onConfigChanged(changed("swiftIrcEnabled"));
        assertEquals(Collections.singletonList("open swiftirc"), calls);

        settings.swiftIrcEnabled = false;
        plugin.onConfigChanged(changed("swiftIrcEnabled"));
        assertEquals("close swiftirc", calls.get(calls.size() - 1));
    }

    @Test
    public void onlyTheChosenNetworksLiveTrafficEchoesInGame() {
        IrcMessage live = new IrcMessage("#rshelp", "Ash", "hi", IrcMessage.MessageType.CHAT, Instant.now());
        assertTrue(IrcPlugin.echoesInGame(live, NetworkConfig.SWIFTIRC_ID));
        assertFalse(IrcPlugin.echoesInGame(live, ZNC));
        assertTrue(IrcPlugin.echoesInGame(live.withNetworkId(ZNC), ZNC));
        assertFalse(IrcPlugin.echoesInGame(new IrcMessage("#rshelp", "Ash", "old",
                IrcMessage.MessageType.HISTORY, Instant.now()).withNetworkId(ZNC), ZNC));
    }

    @Test
    public void theInGameNetworkFallsBackToSwiftIrcWhenItIsGone() throws Exception {
        settings.networks = "[{\"id\":\"" + ZNC + "\",\"name\":\"ZNC\",\"host\":\"znc.example\",\"enabled\":false}]";
        IrcPlugin plugin = plugin();
        call(plugin, "applyNetworks");

        settings.inGameNetwork = ZNC;
        assertEquals(ZNC, call(plugin, "inGameNetwork"));
        settings.inGameNetwork = "removed-id";
        assertEquals(NetworkConfig.SWIFTIRC_ID, call(plugin, "inGameNetwork"));
    }
}
