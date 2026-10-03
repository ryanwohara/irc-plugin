package com.irc;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.irc.emoji.EmojiParser;
import com.irc.emoji.EmojiService;
import joptsimple.internal.Strings;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.Color;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@PluginDescriptor(
        name = "Global Chat (IRC)",
        description = "Integrates IRC with the OSRS chatbox"
)
@Slf4j
public class IrcPlugin extends Plugin {
    @Inject
    private IrcConfig config;
    @Inject
    private Client client;
    @Inject
    private ChatMessageManager chatMessageManager;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private ClientToolbar clientToolbar;
    @Inject
    private KeyManager keyManager;
    @Inject
    private ConfigManager configManager;
    private IrcOverlay overlay;
    @Inject
    private Gson gson;
    private NetworkManager networks;
    private NetworksDialog networksDialog;
    private IrcPanel panel;
    @Inject
    private EmojiService emojiService;

    private static final Pattern VALID_WINKS = Pattern.compile("^;([opdOPD)(<>]|[-_];)");

    private final Map<String, String> channelPasswords = new HashMap<>();
    /** Networks opened since startUp; later opens are reconnects. */
    private final Set<String> openedThisSession = ConcurrentHashMap.newKeySet();

    @Override
    protected void startUp() throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            setupPanel();
        } else {
            SwingUtilities.invokeAndWait(this::setupPanel);
        }
        updatePanelHost(false);
        overlay = new IrcOverlay(client, panel, config, keyManager);
        overlayManager.add(overlay);
        emojiService.initialize();
        networks = new NetworkManager(new PluginConnector());
        SwingUtilities.invokeLater(() -> {
            if (panel != null) panel.setNetworkActions(new PanelNetworkActions());
        });
        applyNetworks();
    }

    @Override
    protected void shutDown() {
        // Cut the adapter's path to the UI first: a LIST reply already in flight would otherwise
        // re-open the channel browser onto a panel that is on its way out.
        if (networks != null) {
            for (IrcAdapter adapter : networks.adapters()) adapter.clearPanel();
        }
        if (panel != null) {
            // Capture the old panel so deferred cleanup cannot dispose a newly enabled panel.
            IrcPanel closingPanel = panel;
            panel = null;
            clientToolbar.removeNavigation(closingPanel.getNavigationButton());
            SwingUtilities.invokeLater(closingPanel::shutdown);
        }
        if (networks != null) {
            networks.shutdown();
            networks = null;
        }
        if (networksDialog != null) {
            NetworksDialog closing = networksDialog;
            networksDialog = null;
            SwingUtilities.invokeLater(closing::dispose);
        }
        if (overlay != null) {
            overlay.shutdown();
            overlayManager.remove(overlay);
            overlay = null;
        }
        channelPasswords.clear();
        openedThisSession.clear();
    }

    @Provides
    IrcConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(IrcConfig.class);
    }

    /** SwiftIRC from the Connection settings, then the saved extras. */
    private List<NetworkConfig> networkConfigs() {
        List<NetworkConfig> all = new ArrayList<>();
        all.add(NetworkConfig.swiftIrc(config));
        all.addAll(NetworkStore.parse(gson, config.networks()));
        return all;
    }

    /** The saved network order as ids; it may name networks that have since been removed. */
    private List<String> networkOrder() {
        return OrderStore.parseNetworkOrder(config.networkOrder());
    }

    /** Saves the network order, dropping ids of networks that no longer exist. */
    private void saveNetworkOrder(List<String> ids) {
        Set<String> known = new HashSet<>();
        for (NetworkConfig network : networkConfigs()) known.add(network.getId());
        List<String> kept = new ArrayList<>();
        for (String id : ids) {
            if (known.contains(id)) kept.add(id);
        }
        configManager.setConfiguration("irc", OrderStore.NETWORK_ORDER_KEY, OrderStore.serializeNetworkOrder(kept));
    }

    private void applyNetworks() {
        if (networks == null) return;
        List<NetworkConfig> all = networkConfigs();
        SwingUtilities.invokeLater(() -> {
            if (panel == null) return;
            for (NetworkConfig network : all) panel.setNetworkName(network.getId(), network.getName());
        });
        networks.apply(all);
        if (networksDialog != null) {
            SwingUtilities.invokeLater(() -> {
                if (networksDialog != null) {
                    networksDialog.setNetworks(all.get(0), all.subList(1, all.size()), networkOrder());
                }
            });
        }
    }

    private String seedNick() {
        if (Strings.isNullOrEmpty(config.username())) {
            return "RLGuest" + (int) (Math.random() * 9999 + 1);
        }
        return config.username().replace(" ", "_");
    }

    /** Opens and closes the real connections for NetworkManager. */
    private class PluginConnector implements NetworkManager.Connector {
        @Override
        public IrcAdapter open(NetworkConfig network) {
            // Seed nick only; once connected the network's confirmed nick (tracked by the
            // adapter from the raw NICK event) is the source of truth - see adapter.getNick().
            String nick = network.isBuiltIn() || network.getNick().isEmpty()
                    ? seedNick() : sanitizeNick(network.getNick());
            IrcAdapter adapter = new IrcAdapter();
            adapter.initialize(config, network, IrcPlugin.this::processMessage, panel, nick);
            adapter.connect();
            joinOnOpen(adapter, network);
            return adapter;
        }

        @Override
        public void close(IrcAdapter adapter, NetworkConfig network, boolean removed, String reason) {
            // NetworkManager holds its lock while calling close, and on a stalled socket disconnect
            // can block (the reader thread holds the stream lock inside readLine), which would
            // freeze the EDT and every other network. Disconnect off the caller's thread.
            if (adapter != null) {
                // Cut it off from the UI first: a stalled disconnect that completes after the
                // replacement session is up would otherwise report "Disconnected" and empty the
                // user lists of that live session. Say it here instead, once.
                adapter.detach();
                systemMessage(network.getId(), "Disconnected (" + reason + ")");
                Thread disconnecter = new Thread(() -> adapter.disconnect(reason), "irc-disconnect");
                disconnecter.setDaemon(true);
                disconnecter.start();
            }
            String id = network.getId();
            SwingUtilities.invokeLater(() -> {
                if (panel == null) return;
                panel.setNetworkConnected(id, false);
                if (removed) {
                    panel.removeNetworkBuffers(id);
                    panel.forgetNetwork(id);
                }
            });
        }
    }

    /** What the pop-out's tree, buttons and the dialog ask of networks. */
    private class PanelNetworkActions implements IrcDesktopLayout.NetworkActions {
        @Override
        public void reconnect(String networkId) {
            reconnectNetwork(networkId);
        }

        @Override
        public void setConnected(String networkId, boolean connected) {
            if (networks != null) networks.setConnected(networkId, connected);
        }

        @Override
        public void edit(String networkId) {
            openNetworksDialog(networkId);
        }
    }

    /** EDT only. Reuses one dialog; selects {@code networkId} when given. */
    private void openNetworksDialog(String networkId) {
        if (panel == null || networks == null) return;
        List<NetworkConfig> all = networkConfigs();
        if (networksDialog == null) {
            networksDialog = new NetworksDialog(SwingUtilities.getWindowAncestor(panel.getChatContent()),
                    all.get(0), all.subList(1, all.size()), networkOrder(), new NetworksDialog.Callbacks() {
                        @Override
                        public void save(List<NetworkConfig> extras) {
                            configManager.setConfiguration("irc", NetworkStore.CONFIG_KEY,
                                    NetworkStore.serialize(gson, extras));
                        }

                        @Override
                        public void saveOrder(List<String> ids) {
                            saveNetworkOrder(ids);
                        }

                        @Override
                        public boolean isConnected(String id) {
                            IrcAdapter adapter = networks != null ? networks.get(id) : null;
                            return adapter != null && adapter.isConnected();
                        }

                        @Override
                        public void setConnected(String id, boolean connected) {
                            if (networks != null) networks.setConnected(id, connected);
                        }
                    });
        } else {
            networksDialog.setNetworks(all.get(0), all.subList(1, all.size()), networkOrder());
        }
        networksDialog.selectNetwork(networkId);
        networksDialog.setVisible(true);
        networksDialog.toFront();
    }

    /** Commands that work without a live connection to the buffer's network. */
    private static final Set<String> OFFLINE_COMMANDS = new HashSet<>(Arrays.asList(
            "go", "clear", "popout", "help", "networks", "c", "close", "leave", "part"));

    private static String passwordKey(String networkId, String channel) {
        return networkId + "\u0000" + channel.toLowerCase();
    }

    private void systemMessage(String networkId, String text) {
        processMessage(new IrcMessage("System", "System", text, IrcMessage.MessageType.SYSTEM, Instant.now())
                .withNetworkId(networkId));
    }

    private IrcAdapter adapterFor(String networkId) {
        return networks != null ? networks.get(networkId) : null;
    }

    private void setupPanel() {
        panel = injector.getInstance(IrcPanel.class);
        panel.init(
                this::handleMessageSend,
                this::handleChannelJoin,
                this::handleChannelLeave,
                this::handleReconnect,
                this::handleChannelListRequest,
                this::reportChannelListTimeout
        );
        panel.initializeGui();
    }

    /** The channels a freshly opened connection joins. */
    private void joinOnOpen(IrcAdapter adapter, NetworkConfig network) {
        Set<String> joined = new HashSet<>();
        boolean firstOpen = openedThisSession.add(network.getId());
        if (network.isBuiltIn()) {
            // Like main: the default channel is joined once per session; a reconnect only
            // rejoins open buffers, so a channel the user parted stays parted.
            if (firstOpen) {
                for (String channel : joinDefaultChannel(adapter).split(",")) {
                    joined.add(channel.toLowerCase());
                }
            }
        } else {
            for (String channel : network.autojoinChannels()) {
                joinChannel(adapter, network.getId(), channel, channelPasswords.getOrDefault(passwordKey(network.getId(), channel), ""));
                joined.add(channel.toLowerCase());
            }
        }
        // A reconnect rejoins whatever channel buffers are still open for this network.
        if (panel != null) {
            for (BufferKey key : panel.getBuffers()) {
                if (key.getNetworkId().equals(network.getId()) && key.getName().startsWith("#")
                        && joined.add(key.getName().toLowerCase())) {
                    joinChannel(adapter, network.getId(), key.getName(),
                            channelPasswords.getOrDefault(passwordKey(network.getId(), key.getName()), ""));
                }
            }
        }
    }

    /** Joins the configured channel(s) and returns them as joined (normalized). */
    private String joinDefaultChannel(IrcAdapter adapter) {
        String channel;
        if (config.channel().isEmpty()) {
            channel = "#rshelp";
        } else {
            channel = config.channel().toLowerCase();
            if (!channel.startsWith("#")) {
                channel = "#" + channel;
            }
        }
        joinChannel(adapter, NetworkConfig.SWIFTIRC_ID, channel, config.channelPassword());
        return channel;
    }

    private void handleMessageSend(BufferKey buffer, String message) {
        if (message.startsWith("/") ||
                (message.startsWith(config.prefix())
                        && message.length() > config.prefix().length())) {
            handleCommand(buffer, message);
        } else {
            IrcAdapter adapter = adapterFor(buffer.getNetworkId());
            if (adapter == null) {
                systemMessage(buffer.getNetworkId(), "Not connected.");
                return;
            }
            adapter.sendMessage(buffer.getName(), message);
        }
    }

    private void handleCommand(BufferKey buffer, String command) {
        if (panel == null) return;
        String net = buffer.getNetworkId();
        String current = buffer.getName();

        String[] parts = command.split(" ", 2);
        String cmd = parts[0].toLowerCase().substring(1);
        String arg = parts.length > 1 ? parts[1].trim() : "";

        IrcAdapter adapter = adapterFor(net);
        if (adapter == null && !OFFLINE_COMMANDS.contains(cmd)) {
            systemMessage(net, "Not connected.");
            return;
        }

        switch (cmd) {
            case "join":
                if (arg.isEmpty()) {
                    // Join with nothing to join: browse the server's channels instead of
                    // silently doing nothing.
                    handleChannelListRequest(net, "");
                } else {
                    String chan = arg.split(" ")[0];
                    String password = arg.split(" ").length > 1 ? arg.split(" ")[1] : "";
                    joinChannel(adapter, net, chan.startsWith("#") ? chan : "#" + chan, password);
                }
                break;

            case "c":
            case "close":
            case "leave":
            case "part":
                closePane(adapter, buffer, arg);
                break;

            case "quit":
                adapter.disconnect(arg.isEmpty() ? "Quitting the plugin" : arg);
                break;

            case "go":
                if (!arg.isEmpty()) {
                    for (BufferKey key : panel.getBuffers()) {
                        if (key.getName().contains(arg)) {
                            SwingUtilities.invokeLater(() -> { if (panel != null) panel.setFocusedChannel(key); });
                            break;
                        }
                    }
                }
                break;

            case "msg":
            case "query":
                String[] msgParts = arg.split(" ", 2);
                if (msgParts.length > 0 && !msgParts[0].isEmpty()) {
                    BufferKey query = BufferKey.of(net, msgParts[0]);
                    SwingUtilities.invokeLater(() -> { if (panel != null) panel.addChannel(query); });
                    if (msgParts.length == 2) adapter.sendMessage(msgParts[0], msgParts[1]);
                }
                break;

            case "me":
                if (!arg.isEmpty()) {
                    adapter.sendAction(current, arg);
                }
                break;

            case "notice":
                String[] noticeParts = arg.split(" ", 2);
                if (noticeParts.length == 2) {
                    String target = noticeParts[0];
                    String noticeMsg = noticeParts[1];
                    adapter.sendNotice(target, noticeMsg);
                }
                break;

            case "whois":
                if (!arg.isEmpty()) {
                    adapter.sendRawLine("WHOIS " + arg);
                }
                break;

            case "away":
                if (arg.isEmpty()) {
                    adapter.sendRawLine("AWAY");
                } else {
                    adapter.sendRawLine("AWAY :" + arg);
                }
                break;

            case "names":
                if (current.startsWith("#")) adapter.sendRawLine("NAMES " + current);
                break;

            case "nick": {
                // A typed nick often has a space in it; underscore it rather than ignoring the
                // command, which is what refusing multi-word input used to look like.
                String requestedNick = sanitizeNick(arg);
                if (!requestedNick.isEmpty()) {
                    adapter.setNick(requestedNick);
                }
                break;
            }

            case "id": {
                String idCommand = identifyCommandFromArgs(arg);
                if (idCommand != null) {
                    // Both account and password supplied inline.
                    adapter.getClient().sendMessage("NickServ", idCommand);
                } else {
                    // A lone token is the account; no token means prompt for the account too.
                    String idAccount = arg.isEmpty() ? null : arg.trim().split("\\s+")[0];
                    promptForIdentify(adapter, idAccount);
                }
                break;
            }

            case "ns":
                if (!arg.isEmpty()) {
                    adapter.sendMessage("NickServ", arg);
                }
                break;

            case "cs":
                if (!arg.isEmpty()) {
                    adapter.sendMessage("ChanServ", arg);
                }
                break;

            case "bs":
                if (!arg.isEmpty()) {
                    adapter.sendMessage("BotServ", arg);
                }
                break;

            case "ms":
                if (!arg.isEmpty()) {
                    adapter.sendMessage("MemoServ", arg);
                }
                break;

            case "hs":
                if (!arg.isEmpty()) {
                    adapter.sendMessage("HostServ", arg);
                }
                break;

            case "mode":
                if (!arg.isEmpty()) {
                    mode(adapter, current, arg);
                } else {
                    mode(adapter, current, "");
                }
                break;

            case "umode":
            case "umode2":
                if (!arg.isEmpty()) {
                    adapter.sendRawLine("MODE " + adapter.getNick() + " :" + arg);
                } else {
                    adapter.sendRawLine("MODE " + adapter.getNick());
                }
                break;

            case "topic":
                adapter.sendRawLine(arg.isEmpty() ? "TOPIC " + current : "TOPIC " + current + " :" + arg);
                break;

            case "list":
                handleChannelListRequest(net, arg);
                break;

            case "clear":
                SwingUtilities.invokeLater(() -> { if (panel != null) panel.clearCurrentPane(); });
                break;

            case "popout":
                // Reachable from the chat box, so this works even when the sidebar is hidden.
                if (config.popOut()) {
                    SwingUtilities.invokeLater(() -> {
                        if (panel != null) panel.bringPopOutToFront();
                    });
                } else {
                    configManager.setConfiguration("irc", "popOut", true);
                }
                break;

            case "networks":
                SwingUtilities.invokeLater(() -> openNetworksDialog(null));
                break;

            case "help":
                showCommandHelp(net);
                break;

            default:
                systemMessage(net, "Unknown command: " + cmd);
                break;
        }
    }

    /**
     * Single entry point for both /list and the panel's Browse button.
     *
     * sendRawLine silently no-ops when the socket is down, so an unconnected /list would look
     * like nothing happened at all - check first and say so.
     */
    private void handleChannelListRequest(String networkId, String query) {
        if (panel == null) return;
        IrcAdapter adapter = adapterFor(networkId);
        if (adapter == null || !adapter.isConnected()) {
            systemMessage(networkId, "Not connected.");
            return;
        }
        systemMessage(networkId, "Requesting channel list...");
        panel.setChannelListNetworkId(networkId);
        // Arm before sending. sendRawLine writes and flushes synchronously, so arming afterwards
        // leaves a window - vanishingly small, but real - in which a 323 round-trips and queues
        // its cancel ahead of this arm, and the arm then fires "no response" over a rendered list.
        SwingUtilities.invokeLater(panel::armChannelListTimeout);
        adapter.requestChannelList(query);
    }

    /**
     * Reports a LIST that never came back, on the panel's behalf.
     *
     * Routed through processMessage rather than written straight to the panel so it reaches the
     * game chatbox too. "Requesting channel list..." above and a 263 refusal from the adapter both
     * go to both sinks; this used to be the one channel-list message that did not, which left a
     * user watching game chat with the sidebar collapsed seeing the request and never the outcome.
     */
    private void reportChannelListTimeout() {
        String networkId = panel != null ? panel.getChannelListNetworkId() : NetworkConfig.SWIFTIRC_ID;
        systemMessage(networkId, "No channel list response from the server.");
    }

    private void mode(IrcAdapter adapter, String current, String mode) {
        String[] split = mode.split(" ");
        if (mode.startsWith("#")) {
            adapter.sendRawLine("MODE " + mode);
        } else if (split.length > 0) {
            adapter.sendRawLine("MODE " + current + " " + mode);
        } else {
            adapter.sendRawLine("MODE " + current);
        }
    }

    /**
     * Makes a typed nick safe to send. Whitespace cannot reach the wire - {@code setNick} writes
     * "NICK " + nick as one raw line, so "foo bar" would go out as NICK with two parameters and the
     * server would take only "foo".
     *
     * A run of whitespace collapses to a single underscore: someone who typed three spaces did not
     * mean three underscores, and the server caps nick length. Returns "" when nothing usable is
     * left, so the caller can decline to send rather than sending a lone underscore.
     */
    static String sanitizeNick(String nick) {
        if (nick == null) {
            return "";
        }
        return nick.trim().replaceAll("\\s+", "_");
    }

    static String identifyCommand(String account, String password) {
        if (account != null && !account.isEmpty()) {
            return "identify " + account + " " + password;
        }
        return "identify " + password;
    }

    static String identifyCommandFromArgs(String arg) {
        String[] parts = (arg == null || arg.isEmpty()) ? new String[0] : arg.trim().split("\\s+");
        if (parts.length >= 2) {
            return identifyCommand(parts[0], parts[1]);
        }
        return null;
    }

    private void promptForIdentify(IrcAdapter adapter, String account) {

        SwingUtilities.invokeLater(() -> {
            String acct = account;
            if (acct == null) {
                acct = JOptionPane.showInputDialog(panel.getChatContent(),
                        "Enter your NickServ account (leave blank to identify by nick):",
                        "Account (optional)", JOptionPane.QUESTION_MESSAGE);
                if (acct == null) return; // cancelled
                acct = acct.trim();
            }

            JPasswordField passwordField = new JPasswordField();
            int result = JOptionPane.showConfirmDialog(panel.getChatContent(), passwordField,
                    "Enter your NickServ password", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return;

            String password = new String(passwordField.getPassword());
            if (password.isEmpty()) return;

            adapter.getClient().sendMessage("NickServ", identifyCommand(acct, password));
        });
    }

    private void showCommandHelp(String networkId) {
        String[] helpLines = {
                "Available commands:",
                "/away [message] - Set or remove away status",
                "/go <channel> - Focus on this channel (uses regex)",
                "/join [#channel] - Join a channel, or browse the list if omitted",
                "/list [filter] - Browse the server's channel list (e.g. /list >50)",
                "/part [#channel] - Leave a channel (aliased as /leave)",
                "/popout - Open IRC in its own window",
                "/me <action> - Send action message",
                "/mode [#channel] [+modes|-modes] - Modify channel modes",
                "/msg <nick> <message> - Send private message",
                "/networks - Add, edit or remove IRC networks",
                "/notice <nick> <message> - Send notice",
                "/topic [#channel] [topic] - View or set the channel topic",
                "/umode [+modes|-modes] - Modify user modes",
                "/whois <nick> - Query user information",
                "/bs <message> - Talk to BotServ",
                "/cs <message> - Talk to ChanServ",
                "/hs <message> - Talk to HostServ",
                "/ms <message> - Talk to MemoServ",
                "/ns <message> - Talk to NickServ",
                "/id [account] [password] - Identify to NickServ (prompts for the password if omitted)"
        };

        for (String line : helpLines) {
            systemMessage(networkId, line);
        }
    }

    private void joinChannel(IrcAdapter adapter, String networkId, String channels, String password) {
        if (adapter == null) return;
        for (String channel : channels.split(",")) {
            if (password != null && !password.isEmpty()) {
                channelPasswords.put(passwordKey(networkId, channel), password);
            }
            adapter.joinChannel(channel, password);
        }
    }

    private void closePane(IrcAdapter adapter, BufferKey buffer, String argument) {
        if (panel == null) return;
        String net = buffer.getNetworkId();
        String currentChannel = buffer.getName();
        if ("System".equalsIgnoreCase(currentChannel) && (argument.isEmpty() || !argument.split(" ", 2)[0].startsWith("#"))) {
            return;
        }
        String[] parts = argument.split(" ", 2);
        String target = parts[0];
        String reason = parts.length > 1 ? parts[1] : null;

        if (argument.isEmpty()) {
            if (currentChannel.startsWith("#")) {
                leaveChannel(adapter, BufferKey.of(net, currentChannel), null);
            } else {
                SwingUtilities.invokeLater(() -> panel.removeChannel(buffer));
            }
        } else if (target.startsWith("#")) {
            leaveChannel(adapter, BufferKey.of(net, target), reason);
        } else if (panel.isPane(BufferKey.of(net, target))) {
            SwingUtilities.invokeLater(() -> panel.removeChannel(BufferKey.of(net, target)));
        } else if (currentChannel.startsWith("#")) {
            leaveChannel(adapter, BufferKey.of(net, currentChannel), argument);
        }
    }

    /** Parts a channel when connected; the buffer closes either way. */
    private void leaveChannel(IrcAdapter adapter, BufferKey channel, String reason) {
        if (adapter != null && channel.getName().startsWith("#")) {
            if (reason != null) adapter.leaveChannel(channel.getName(), reason);
            else adapter.leaveChannel(channel.getName());
        }
        channelPasswords.remove(passwordKey(channel.getNetworkId(), channel.getName()));
        if (panel != null) {
            SwingUtilities.invokeLater(() -> { if (panel != null) panel.removeChannel(channel); });
        }
    }

    private void handleChannelJoin(String networkId, String channel, String password) {
        joinChannel(adapterFor(networkId), networkId, channel, password);
    }

    private void handleChannelLeave(BufferKey channel) {
        leaveChannel(adapterFor(channel.getNetworkId()), channel, null);
    }

    private void handleReconnect(String networkId) {
        reconnectNetwork(networkId);
    }

    /** Reconnects with the live settings for {@code networkId}, as main rebuilt from config. */
    private void reconnectNetwork(String networkId) {
        if (networks == null) return;
        for (NetworkConfig network : networkConfigs()) {
            if (network.getId().equals(networkId)) {
                networks.reconnect(network);
                return;
            }
        }
        networks.reconnect(networkId);
    }

    /** A colour code (the background, if any, is matched only to be dropped) or any other formatting code. */
    private static final Pattern CHATBOX_CODES =
            Pattern.compile("\\x03(?:(\\d\\d?)(?:,\\d\\d?)?)?|[\\x02\\x0F\\x11\\x15\\x16\\x1D\\x1E\\x1F]");

    /**
     * Builds the in-game chat box form of {@code text}. Uncoloured text uses the base colour: the
     * custom in-game colour if set, otherwise RuneLite's NORMAL chat colour. With IRC colours on,
     * colour codes become col tags; every other formatting code is stripped either way.
     */
    static String chatboxMessage(String text, IrcConfig config) {
        Color base = config.inGameTextColorEnabled() ? config.inGameTextColor() : null;
        ChatMessageBuilder builder = new ChatMessageBuilder();
        if (!config.ircColorsInGame()) {
            appendChatboxRun(builder, IrcFormatting.stripCodes(text), null, base);
            return builder.build();
        }

        Matcher matcher = CHATBOX_CODES.matcher(text);
        StringBuilder run = new StringBuilder();
        Color runColor = null;
        int last = 0;
        while (matcher.find()) {
            run.append(text, last, matcher.start());
            last = matcher.end();
            char code = matcher.group().charAt(0);
            if (code != '\u0003' && code != '\u000F') {
                continue;
            }
            // A reset, a bare colour code, or code 99 all mean "back to the default colour".
            Color next = code == '\u0003' ? chatboxColorById(matcher.group(1)) : null;
            if (!Objects.equals(next, runColor)) {
                appendChatboxRun(builder, run.toString(), runColor, base);
                run.setLength(0);
                runColor = next;
            }
        }
        run.append(text, last, text.length());
        appendChatboxRun(builder, run.toString(), runColor, base);
        return builder.build();
    }

    private static void appendChatboxRun(ChatMessageBuilder builder, String run, Color color, Color base) {
        if (run.isEmpty()) {
            return;
        }
        Color effective = color != null ? color : base;
        if (effective != null) {
            // Unlike append(String), append(Color, String) doesn't escape game tags like <img=1>.
            builder.append(effective, Text.escapeJagex(run));
        } else {
            builder.append(ChatColorType.NORMAL).append(run);
        }
    }

    /** The palette colour for an IRC colour code, or null for 99, no code, or an unknown code. */
    private static Color chatboxColorById(String id) {
        if (id == null || Integer.parseInt(id) > 98) {
            return null;
        }
        String html = IrcPanel.ChannelPane.htmlColorById(id);
        switch (html) {
            case "white":
                return Color.WHITE;
            case "black":
                return Color.BLACK;
            default:
                return Color.decode(html);
        }
    }

    /** Phase 1: only SwiftIRC's live traffic is echoed into the game chatbox and overlay. */
    static boolean echoesInGame(IrcMessage message) {
        return NetworkConfig.SWIFTIRC_ID.equals(message.getNetworkId())
                && message.getType() != IrcMessage.MessageType.HISTORY
                && message.getType() != IrcMessage.MessageType.HISTORY_SEPARATOR;
    }

    private void processMessage(IrcMessage message) {
        // An adapter that was removed can still deliver a line or two while it shuts down.
        NetworkManager current = networks;
        if (current != null && !current.isKnown(message.getNetworkId())) return;

        IrcMessage.MessageType[] chatBoxEvents = {IrcMessage.MessageType.QUIT, IrcMessage.MessageType.NICK_CHANGE};
        BufferKey target = message.getBuffer();

        if (panel != null) {
            List<BufferKey> buffers = panel.getBuffers();
            if (!buffers.contains(target)) {
                for (BufferKey key : buffers) {
                    if (key.getNetworkId().equals(target.getNetworkId())
                            && key.getName().equalsIgnoreCase(target.getName())) {
                        SwingUtilities.invokeLater(() -> {
                            if (panel != null) panel.renameChannel(key, target.getName());
                        });
                    }
                }
            }
        }

        if (echoesInGame(message) && client.getGameState() == GameState.LOGGED_IN) {
            BufferKey focused = panel != null ? panel.getCurrentBuffer() : null;
            boolean activeChannelCondition = focused == null
                    || (focused.getNetworkId().equals(target.getNetworkId())
                    && focused.getName().equalsIgnoreCase(target.getName()));
            boolean isSystemEvent = message.getChannel().equals("System") && Arrays.binarySearch(chatBoxEvents, message.getType()) > -1;

            if (!config.activeChannelOnly() || (config.activeChannelOnly() && (activeChannelCondition || isSystemEvent))) {
                chatMessageManager.queue(QueuedMessage.builder()
                        .type(config.getChatboxType().getType())
                        .sender(message.getChannel())
                        .name(message.getDisplaySender())
                        .runeLiteFormattedMessage(chatboxMessage(
                                EmojiParser.parseToAliases(message.getContent()), config))
                        .timestamp((int) (message.getTimestamp().getEpochSecond()))
                        .build());
            }
        }

        if (panel != null) {
            SwingUtilities.invokeLater(() -> {
                if (panel != null) panel.addMessage(message);
            });
        }
    }

    private void updatePanelHost(boolean rebuildNavigation) {
        SwingUtilities.invokeLater(() -> {
            if (panel == null) return;
            clientToolbar.removeNavigation(panel.getNavigationButton());
            if (rebuildNavigation) panel.generateNavigationButton();
            panel.setDetached(config.popOut(), config.popOutAlwaysOnTop());
            if (config.sidePanel() && !config.popOut()) {
                clientToolbar.addNavigation(panel.getNavigationButton());
            }
        });
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged configChanged) {
        if (!configChanged.getGroup().equals("irc")) {
            return;
        }

        if ("sidePanel".equals(configChanged.getKey())
                || "popOut".equals(configChanged.getKey())
                || "panelPriority".equals(configChanged.getKey())) {
            updatePanelHost("panelPriority".equals(configChanged.getKey()));
        } else if ("popOutAlwaysOnTop".equals(configChanged.getKey())) {
            SwingUtilities.invokeLater(() -> {
                if (panel != null) {
                    panel.setDetached(config.popOut(), config.popOutAlwaysOnTop());
                }
            });
        } else if ("fontSize".equals(configChanged.getKey())) {
            SwingUtilities.invokeLater(() -> {
                if (panel != null) {
                    panel.updateFont();
                }
            });
        } else if ("chatBackgroundColor".equals(configChanged.getKey())
                || "chatTextColor".equals(configChanged.getKey())) {
            SwingUtilities.invokeLater(() -> {
                if (panel != null) {
                    panel.updateColors();
                }
            });
        } else if ("overlayEnabled".equals(configChanged.getKey())) {
            if (overlay != null) {
                overlay.setEnabled(config.overlayEnabled());
            }
        } else if (NetworkStore.CONFIG_KEY.equals(configChanged.getKey())) {
            applyNetworks();
        } else if ("server".equals(configChanged.getKey())
                || "accountName".equals(configChanged.getKey())
                || "password".equals(configChanged.getKey())
                || "channel".equals(configChanged.getKey())) {
            // Like main: picked up on the next Reconnect, not applied live.
            if (networks != null) networks.update(NetworkConfig.swiftIrc(config));
        } else if ("logRawLines".equals(configChanged.getKey())) {
            // Applied live: the connection failure worth capturing usually happens during
            // connect, so requiring a reconnect to arm the log would miss it.
            if (networks != null) {
                for (IrcAdapter adapter : networks.adapters()) adapter.setRawLogging(config.logRawLines());
            }
        } else if ("overlayDynamic".equals(configChanged.getKey())) {
            if (overlay != null) {
                overlayManager.remove(overlay);
                overlay = new IrcOverlay(client, panel, config, keyManager);
                overlayManager.add(overlay);
            }
        }
    }

    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent event) {
        if (!"chatDefaultReturn".equals(event.getEventName()) || networks == null) {
            return;
        }

        String message = client.getVarcStrValue(VarClientID.CHATINPUT);
        Matcher matcher = VALID_WINKS.matcher(message);

        if (message.startsWith(config.prefix()) && !matcher.matches()) {
            final int[] intStack = client.getIntStack();
            int intStackCount = client.getIntStackSize();
            intStack[intStackCount - 3] = 1;

            BufferKey current = panel != null ? panel.getCurrentBuffer() : BufferKey.swiftIrc(this.config.channel());
            handleMessageSend(current, message.substring(1));
        }
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded entry) {
        // Only target chat message widgets
        if (entry.getType() != MenuAction.CC_OP.getId() && entry.getType() != MenuAction.CC_OP_LOW_PRIORITY.getId()) {
            return;
        }

        final int groupId = WidgetUtil.componentToInterface(entry.getActionParam1());
        final int childId = WidgetUtil.componentToId(entry.getActionParam1());

        // Make sure we're in the chatbox
        if (groupId != InterfaceID.CHATBOX) {
            return;
        }

        if (!entry.getOption().equals("Report")) {
            return;
        }

        final Widget widget = client.getWidget(groupId, childId);
        if (widget == null) {
            return;
        }

        final Widget parent = widget.getParent();
        if (parent == null || InterfaceID.Chatbox.SCROLLAREA != parent.getId()) {
            return;
        }

        // Get child id of first chat message static child so we can subtract this offset to link to dynamic child
        final int first = WidgetUtil.componentToId(InterfaceID.Chatbox.LINE0);

        // Convert current message static widget id to dynamic widget id of message node with message contents
        final int dynamicChildId = (childId - first) * 4 + 1;

        // Extract message contents from the specific widget being right-clicked
        final Widget messageContents = parent.getChild(dynamicChildId);
        if (messageContents == null) {
            return;
        }

        String currentMessage = messageContents.getText();
        if (currentMessage == null) {
            return;
        }

        // Remove formatting tags and check for URLs in this specific message
        String cleanMessage = Text.removeTags(currentMessage);
        List<String> urls = extractUrls(cleanMessage);

        if (!urls.isEmpty()) {
            // Add menu entries for each URL found in this specific message
            for (int i = 0; i < urls.size(); i++) {
                final String url = urls.get(i);

                client.createMenuEntry(1)
                        .setOption("Open URL: " + url.substring(0, Math.min(25, url.length())) + (url.length() > 25 ? "..." : ""))
                        .setTarget(entry.getTarget())
                        .setType(MenuAction.RUNELITE)
                        .onClick(e -> LinkBrowser.browse(url));
            }
        }
    }

    private List<String> extractUrls(String message) {
        List<String> urls = new ArrayList<>();
        Matcher matcher = IrcPanel.VALID_LINK.matcher(message);

        while (matcher.find()) {
            String url = matcher.group().trim();
            // Ensure URLs have proper protocol
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                if (url.startsWith("www.")) {
                    url = "https://" + url;
                } else {
                    url = "https://" + url;
                }
            }
            urls.add(url);
        }

        return urls;
    }
}
