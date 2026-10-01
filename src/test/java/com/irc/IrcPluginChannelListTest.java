package com.irc;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ClientUI;
import org.junit.Test;

import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The plugin's half of the channel browser: the order it arms the response timeout in, how it
 * reports that timeout, and what it releases when it shuts down.
 *
 * IrcPlugin is normally built by Guice. Only three of its injected fields are reached on this path
 * - the RuneLite Client, the config and the panel - so they are set directly, and the Client is a
 * dynamic proxy because the real one is an interface with hundreds of methods of which exactly one
 * is called here.
 */
public class IrcPluginChannelListTest {

    private static IrcConfig stubConfig() {
        // IrcConfig has two abstract methods; everything else is a default.
        return new IrcConfig() {
            @Override
            public String username() {
                return "tester";
            }

            @Override
            public String password() {
                return "";
            }
        };
    }

    /**
     * Stands in for the RuneLite client and counts the one question the plugin asks it.
     *
     * That count is the signal two of these tests lean on: {@code getGameState()} is consulted by
     * {@code IrcPlugin.processMessage} and by nothing else reachable from here, so a non-zero count
     * means a message went through processMessage - the single funnel that feeds the game chatbox
     * as well as the panel - rather than being written straight into the panel.
     *
     * LOGIN_SCREEN keeps the chatbox branch itself out of the way: actually queueing a chat message
     * would need a ChatMessageManager, a RuneLite service with no usable constructor. Reaching the
     * gate is what is being asserted.
     */
    private static final class GameStateProbe implements InvocationHandler {
        final AtomicInteger gameStateQueries = new AtomicInteger();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getGameState":
                    gameStateQueries.incrementAndGet();
                    return GameState.LOGIN_SCREEN;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "GameStateProbe";
                default:
                    throw new AssertionError("unexpected call to the RuneLite client: " + method);
            }
        }
    }

    private static Client clientProxy(GameStateProbe probe) {
        return (Client) Proxy.newProxyInstance(
                Client.class.getClassLoader(), new Class<?>[]{Client.class}, probe);
    }

    /** Same headless construction as IrcPanelChannelListTest: no Window, but real panes. */
    private static <T extends IrcPanel> T dressPanel(T panel) throws Exception {
        setField(IrcPanel.class, panel, "config", stubConfig());
        setField(IrcPanel.class, panel, "tabbedPane", new JTabbedPane());
        panel.addChannel("System");
        panel.setFocusedChannel("System");
        return panel;
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Class<?> owner, Object target, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * Drains the EDT twice. A message reaches a pane through two hops of invokeLater - the
     * plugin's processMessage queues panel.addMessage, and ChannelPane.appendMessage queues the
     * document edit - so a single drain runs the first hop and only enqueues the second. Draining
     * once would make every "the pane must NOT contain X" assertion pass vacuously.
     */
    private static void drainEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static Method privateMethod(String name, Class<?>... types) throws Exception {
        Method method = IrcPlugin.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    private static IrcPlugin pluginWith(IrcPanel panel, IrcAdapter adapter, GameStateProbe probe) throws Exception {
        IrcPlugin plugin = new IrcPlugin();
        setField(IrcPlugin.class, plugin, "config", stubConfig());
        setField(IrcPlugin.class, plugin, "client", clientProxy(probe));
        setField(IrcPlugin.class, plugin, "panel", panel);
        setField(IrcPlugin.class, plugin, "ircAdapter", adapter);
        return plugin;
    }

    /** Records that the timeout was armed, without starting a real 30 second Swing Timer. */
    private static class ProbePanel extends IrcPanel {
        volatile boolean armed;
        volatile boolean tornDown;

        @Override
        public void armChannelListTimeout() {
            armed = true;
        }

        @Override
        public void shutdown() {
            tornDown = true;
        }
    }

    /**
     * Answers "was the timeout already armed at the moment the LIST hit the wire?".
     *
     * requestChannelList stands in for the real write-and-flush and drains the EDT before looking,
     * so anything the plugin queued with invokeLater beforehand has already run and anything queued
     * afterwards has not.
     */
    private static class WireProbeAdapter extends IrcAdapter {
        private final ProbePanel probePanel;
        volatile boolean sent;
        volatile boolean armedWhenSent;
        volatile boolean panelCleared;
        volatile boolean connected = true;

        WireProbeAdapter(ProbePanel probePanel) {
            this.probePanel = probePanel;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void requestChannelList(String query) {
            sent = true;
            try {
                SwingUtilities.invokeAndWait(() -> { });
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            armedWhenSent = probePanel.armed;
        }

        @Override
        public void clearPanel() {
            panelCleared = true;
        }

        @Override
        public void disconnect(String reason) {
            // No socket in a unit test.
        }
    }

    /**
     * Whole-branch review finding (arm-after-send inversion): sendRawLine writes and flushes
     * synchronously, so arming afterwards left a window in which a 323 round-trips, queues its
     * cancel first, and the arm then lands on top of it - firing "no response" over a list the
     * user is already reading. Hoisting the arm above the send closes it.
     *
     * Not reachable over a real network (one invokeLater against a round trip), which is exactly
     * why the ordering needs pinning rather than trusting.
     */
    @Test
    public void theTimeoutIsArmedBeforeTheListGoesOnTheWire() throws Exception {
        ProbePanel panel = dressPanel(new ProbePanel());
        WireProbeAdapter adapter = new WireProbeAdapter(panel);
        IrcPlugin plugin = pluginWith(panel, adapter, new GameStateProbe());

        privateMethod("handleChannelListRequest", String.class).invoke(plugin, ">50");
        drainEdt();

        assertTrue("the request must reach the adapter", adapter.sent);
        assertTrue("the timeout must already be armed when the LIST is written",
                adapter.armedWhenSent);
    }

    /** An unconnected /list says so, sends nothing and arms nothing. */
    @Test
    public void anUnconnectedRequestNeitherSendsNorArms() throws Exception {
        ProbePanel panel = dressPanel(new ProbePanel());
        WireProbeAdapter adapter = new WireProbeAdapter(panel);
        adapter.connected = false;
        IrcPlugin plugin = pluginWith(panel, adapter, new GameStateProbe());

        privateMethod("handleChannelListRequest", String.class).invoke(plugin, "");
        drainEdt();

        assertFalse(adapter.sent);
        assertFalse(panel.armed);

        Map<String, IrcPanel.ChannelPane> panes = panel.getChannelPanes();
        assertTrue("the user has to be told why nothing happened",
                panes.get("System").getText().toLowerCase().contains("not connected"));
    }

    /**
     * Whole-branch review finding: the 30 second timeout notice was the only channel-list message
     * that skipped the game chatbox. The request announces itself through processMessage (chatbox
     * and panel) and a 263 refusal does too, but the expiry called panel.addMessage directly - so a
     * user watching game chat with the sidebar collapsed saw the request and never its outcome.
     *
     * The plugin now owns emitting it. Two things are pinned: the notice still reaches the panel,
     * and getGameState() was consulted while producing it - which only happens inside
     * processMessage. Reinstating the direct panel write leaves the first assertion passing and
     * fails the second.
     */
    @Test
    public void theTimeoutNoticeGoesThroughTheSharedMessagePath() throws Exception {
        IrcPanel panel = dressPanel(new IrcPanel());
        GameStateProbe probe = new GameStateProbe();
        IrcPlugin plugin = pluginWith(panel, null, probe);

        // setupPanel wires exactly this callback; it cannot be called here because it also builds
        // the panel's Swing GUI, so the same method reference is bound by hand.
        Method report = privateMethod("reportChannelListTimeout");
        panel.init(null, null, null, null, null, () -> {
            try {
                report.invoke(plugin);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        panel.armChannelListTimeout();
        probe.gameStateQueries.set(0);

        Timer timer = (Timer) getField(IrcPanel.class, panel, "channelListTimeout");
        for (java.awt.event.ActionListener listener : timer.getActionListeners()) {
            listener.actionPerformed(null);
        }
        panel.cancelChannelListTimeout();
        drainEdt();

        Map<String, IrcPanel.ChannelPane> panes = panel.getChannelPanes();
        String text = panes.get("System").getText();
        assertTrue("the panel must still see the notice, got: " + text,
                text.toLowerCase().contains("no channel list response"));
        assertTrue("the notice must be emitted through processMessage, the same funnel that queues"
                        + " it to the game chatbox",
                probe.gameStateQueries.get() > 0);
    }

    /**
     * Whole-branch review finding: the browser outlived the plugin. shutDown() dropped the panel
     * reference but disposed nothing, so the JDialog stayed on screen with Refresh and Join both
     * returning silently; and the adapter kept its own panel reference, so a 323 arriving during
     * teardown could still pop the browser for a plugin that was already gone.
     */
    @Test
    public void shutdownTearsDownThePanelAndCutsTheAdapterLoose() throws Exception {
        ProbePanel panel = dressPanel(new ProbePanel());
        WireProbeAdapter adapter = new WireProbeAdapter(panel);
        IrcPlugin plugin = pluginWith(panel, adapter, new GameStateProbe());
        setField(IrcPlugin.class, plugin, "clientToolbar", clientToolbar());

        privateMethod("shutDown").invoke(plugin);
        drainEdt();

        assertTrue("the panel must be torn down, not merely dereferenced", panel.tornDown);
        assertTrue("the adapter must lose its panel reference before the panel goes",
                adapter.panelCleared);
        assertNull("and only then is the panel dropped",
                getField(IrcPlugin.class, plugin, "panel"));
        assertNull(getField(IrcPlugin.class, plugin, "ircAdapter"));
    }

    /**
     * ClientToolbar's only constructor is private and takes a ClientUI. shutDown() hands it the
     * panel's navigation button, which is null on a panel that never built its GUI, and the real
     * method marshals that onto the EDT - so an instance with no ClientUI gets through.
     */
    private static ClientToolbar clientToolbar() throws Exception {
        Constructor<ClientToolbar> constructor =
                ClientToolbar.class.getDeclaredConstructor(ClientUI.class);
        constructor.setAccessible(true);
        return constructor.newInstance((ClientUI) null);
    }
}
