package com.irc;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Adapter class to bridge between SimpleIrcClient
 */
@Slf4j
public class IrcAdapter {
    @Getter
    private SimpleIrcClient client;
    private String currentNick;
    private Consumer<IrcMessage> messageConsumer;
    private IrcConfig config;
    private IrcPanel panel;
    /**
     * The query behind the in-flight LIST, replayed by the dialog's Refresh button. Written on
     * the caller's thread ({@link #requestChannelList}) and read on the IRC reader thread when
     * {@code CHANNEL_LIST} fires - volatile so that handoff doesn't depend on the incidental
     * happens-before edge created by both sides passing through the same monitor.
     */
    private volatile String lastChannelListQuery = "";
    /** Lines stamped earlier than this before registration are replayed history (ZNC playback). */
    static final Duration PLAYBACK_THRESHOLD = Duration.ofSeconds(5);
    private String networkId = NetworkConfig.SWIFTIRC_ID;
    /** When this connection registered; null until it has. Set on the reader thread. */
    private volatile Instant registeredAt;

    public IrcAdapter() {
        client = new SimpleIrcClient();
    }

    /**
     * Initialize the client with the provided config
     */
    public void initialize(IrcConfig config, Consumer<IrcMessage> messageConsumer, IrcPanel panel, String currentNick) {
        initialize(config, NetworkConfig.swiftIrc(config), messageConsumer, panel, currentNick);
    }

    public void initialize(IrcConfig config, NetworkConfig network, Consumer<IrcMessage> messageConsumer,
                           IrcPanel panel, String currentNick) {
        this.messageConsumer = messageConsumer;
        this.currentNick = currentNick;
        this.config = config;
        this.panel = panel;
        this.networkId = network.getId();

        client = new SimpleIrcClient()
                .server(network.getHost(), network.getPort(), network.isTls())
                .credentials(currentNick, "runelite", currentNick)
                .serverPassword(network.getServerPassword());

        if (!network.getSaslPassword().isEmpty()) {
            client.sasl(network.getSaslAccount(), network.getSaslPassword());
        }

        client.setRawLogging(config.logRawLines());

        setupEventHandlers();
    }

    public String getNetworkId() {
        return networkId;
    }

    /**
     * Connect to the IRC server
     */
    public void connect() {
        client.connect();
    }

    /**
     * Disconnect from the IRC server
     */
    public void disconnect(String reason) {
        client.disconnect(reason);
    }
    public void disconnect() {
        client.disconnect();
    }

    /**
     * Join a channel
     */
    public void joinChannel(String channel, String password) {
        client.executeWhenRegistered(() -> client.joinChannel(channel, password));
    }

    /**
     * Leave a channel
     */
    public void leaveChannel(String channel) {
        client.leaveChannel(channel);
    }

    /**
     * Leave a channel with a reason
     */
    public void leaveChannel(String channel, String reason) {
        client.leaveChannel(channel, reason);
    }

    /**
     * Send a message to a target (channel or user)
     */
    public void sendMessage(String target, String message) {
        client.sendMessage(target, message);
        processMessage(new IrcMessage(
                target,
                currentNick,
                message,
                IrcMessage.MessageType.PRIVATE,
                Instant.now(),
                client.getChannelPrefix(target, currentNick)
        ));
    }

    /**
     * Send an action (/me) to a target
     */
    public void sendAction(String target, String action) {
        client.sendAction(target, action);
        processMessage(new IrcMessage(
                target,
                "* " + currentNick,
                action,
                IrcMessage.MessageType.PRIVATE,
                Instant.now(),
                client.getChannelPrefix(target, currentNick)
        ));
    }

    /**
     * Send a notice to a target
     */
    public void sendNotice(String target, String message) {
        client.sendNotice(target, message);
        processMessage(new IrcMessage(
                "System",
                currentNick,
                "Notice to " + target + ": " + message,
                IrcMessage.MessageType.SYSTEM,
                Instant.now()
        ));
    }

    /**
     * Change nickname
     */
    public void setNick(String nick) {
        client.setNick(nick);
    }

    /**
     * Send a raw IRC command
     */
    public void sendRawLine(String command) {
        client.sendRawLine(command);
    }

    /**
     * Asks the server for its channel list. {@code query} is passed through verbatim so server
     * filters such as ">50" or "*quest*" work as the user typed them.
     */
    public void requestChannelList(String query) {
        String trimmed = query != null ? query.trim() : "";
        lastChannelListQuery = trimmed;
        // A run abandoned by an earlier request (server truncated its reply, no 323, connection
        // survives) must not have this request's rows appended onto its stale leftovers.
        client.resetChannelListRun();
        // No trailing space on a bare LIST: some servers read "LIST " as an empty filter.
        client.sendRawLine(trimmed.isEmpty() ? "LIST" : "LIST " + trimmed);
    }

    /**
     * True once the socket is up and the registration handshake has been written - NOT that the
     * server has accepted it. A command sent in the window between the handshake and RPL_WELCOME
     * (longer with SASL) will reach the server but may be answered with 451 ERR_NOTREGISTERED
     * rather than doing anything. Do not read this as "ready for commands".
     */
    /** Applies the raw-logging setting to a live connection, so it can be turned on mid-problem. */
    public void setRawLogging(boolean enabled) {
        if (client != null) {
            client.setRawLogging(enabled);
        }
    }

    public boolean isConnected() {
        return client.isConnected();
    }

    /**
     * Drops the reference to the panel, so an in-flight reply cannot drive a UI that is being
     * torn down. Without it a 323 arriving during shutdown still pops the channel browser for a
     * plugin that no longer exists.
     */
    public void clearPanel() {
        this.panel = null;
    }

    /**
     * Get the current nickname
     */
    public String getNick() {
        return currentNick;
    }

    /**
     * Process and forward incoming messages to the plugin
     */
    private void processMessage(IrcMessage message) {
        if (messageConsumer != null) {
            messageConsumer.accept(message.withNetworkId(networkId));
        }
    }

    /** The line's server-time, or null when absent or unreadable. */
    private static Instant serverTime(String tag) {
        if (tag == null || tag.isEmpty()) return null;
        try {
            return Instant.parse(tag);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Older than registration by more than the threshold: replayed, not live. */
    private boolean isPlayback(Instant sent) {
        if (sent == null) return false;
        Instant reference = registeredAt != null ? registeredAt : Instant.now();
        return sent.isBefore(reference.minus(PLAYBACK_THRESHOLD));
    }

    /** A clock ahead of ours would date lines in the future; show those as now. */
    private static Instant displayTime(Instant sent) {
        Instant now = Instant.now();
        return sent == null || sent.isAfter(now) ? now : sent;
    }

    private void reportConnected(boolean connected) {
        IrcPanel target = panel;
        if (target != null) {
            SwingUtilities.invokeLater(() -> target.setNetworkConnected(networkId, connected));
        }
    }

    /**
     * Set up event handlers for the SimpleIrcClient
     */
    private void setupEventHandlers() {
        client.addEventListener(event -> {
            String target = event.getTarget();
            String source = event.getSource();

            switch (event.getType()) {
                case CONNECT:
                    registeredAt = null;
                    processMessage(new IrcMessage("System", "System", "Connected to IRC server, registering...", IrcMessage.MessageType.SYSTEM, Instant.now()));
                    break;

                case REGISTERED:
                    registeredAt = Instant.now();
                    currentNick = client.getNick();
                    processMessage(new IrcMessage("System", "System", "Registration complete - ready for commands", IrcMessage.MessageType.SYSTEM, Instant.now()));
                    if (NetworkConfig.SWIFTIRC_ID.equals(networkId)) {
                        processMessage(new IrcMessage("System", "System", "Welcome to IRC! To chat in the current channel, use '" + config.prefix() + "' followed by your message in the game chatbox.", IrcMessage.MessageType.SYSTEM, Instant.now()));
                        processMessage(new IrcMessage("System", "System", "For a list of commands, type '/help' in the side panel input box.", IrcMessage.MessageType.SYSTEM, Instant.now()));
                    }
                    reportConnected(true);
                    break;

                case SASL_SUCCESS:
                    processMessage(new IrcMessage("System", "System", "Authenticated via SASL.", IrcMessage.MessageType.SYSTEM, Instant.now()));
                    break;

                case SASL_FAILED:
                    processMessage(new IrcMessage("System", "System", "SASL authentication failed: " + event.getMessage() + " (continuing unauthenticated).", IrcMessage.MessageType.SYSTEM, Instant.now()));
                    break;

                case DISCONNECT:
                    // The client records why the link went down; without it this line is
                    // indistinguishable from the user's own /quit.
                    String disconnectText = event.getMessage() != null && !event.getMessage().isEmpty()
                            ? "Disconnected from IRC (" + event.getMessage() + ")"
                            : "Disconnected from IRC";
                    processMessage(new IrcMessage("System", "System", disconnectText, IrcMessage.MessageType.SYSTEM, Instant.now()));
                    for (String channel : client.getChannels()) {
                        processMessage(new IrcMessage(channel, "System", disconnectText, IrcMessage.MessageType.SYSTEM, Instant.now()));
                    }
                    // Any outcome must cancel a pending LIST timeout, not just success - otherwise
                    // a disconnect while one is armed fires a spurious "no response" 30s later.
                    if (panel != null) {
                        SwingUtilities.invokeLater(panel::cancelChannelListTimeout);
                    }
                    reportConnected(false);
                    break;

                case MESSAGE:
                    if (Objects.equals(target, source)) {
                        switch (config.filterPMs()) {
                            case Current:
                                source = "[PM] " + source;
                                target = panel != null ? panel.getCurrentChannelOn(networkId) : "System";
                                break;
                            case Status:
                                source = "[PM] " + source;
                                target = "System";
                                break;
                            case Private:
                                target = event.getSource();
                                break;
                        }
                    }
                    Instant chatSent = serverTime(event.getAdditionalData());
                    IrcMessage.MessageType chatType = isPlayback(chatSent) ? IrcMessage.MessageType.HISTORY : IrcMessage.MessageType.CHAT;
                    // Looked up by the original target: PMs have no channel, so they get no prefix.
                    processMessage(new IrcMessage(target, source, event.getMessage(), chatType, displayTime(chatSent),
                            client.getChannelPrefix(event.getTarget(), event.getSource())));
                    break;

                case ACTION: {
                    Instant sent = serverTime(event.getAdditionalData());
                    IrcMessage.MessageType actionType = isPlayback(sent) ? IrcMessage.MessageType.HISTORY : IrcMessage.MessageType.CHAT;
                    processMessage(new IrcMessage(event.getTarget(), "* " + event.getSource(), event.getMessage(), actionType, displayTime(sent),
                            client.getChannelPrefix(event.getTarget(), event.getSource())));
                    break;
                }

                case JOIN:
                    if (!config.hideConnectionMessages()) {
                        processMessage(new IrcMessage(
                                event.getTarget(),
                                "*",
                                event.getSource() + " has joined.",
                                IrcMessage.MessageType.JOIN,
                                Instant.now()
                        ));
                    }
                    break;

                case PART:
                    if (!config.hideConnectionMessages()) {
                        processMessage(new IrcMessage(event.getTarget(), event.getSource() + " parted", (event.getMessage() != null ? event.getMessage() : " "), IrcMessage.MessageType.PART, Instant.now()));
                    }
                    break;

                case QUIT:
                    if (!config.hideConnectionMessages() && event.getAdditionalData() != null && !event.getAdditionalData().isEmpty()) {
                        String[] channels = event.getAdditionalData().split(",");
                        for (String channel : channels) {
                            processMessage(new IrcMessage(channel, event.getSource() + " quit", event.getMessage() != null ? event.getMessage() : " ", IrcMessage.MessageType.QUIT, Instant.now()));
                        }
                    }
                    break;

                case NICK_CHANGE:
                    String oldNick = event.getSource();
                    String newNick = event.getMessage();

                    if (oldNick.equals(currentNick)) {
                        currentNick = newNick;
                    }

                    BufferKey oldQuery = BufferKey.of(networkId, oldNick);
                    if (panel != null && panel.isPane(oldQuery)) {
                        IrcPanel renameTarget = panel;
                        SwingUtilities.invokeLater(() -> renameTarget.renameChannel(oldQuery, newNick));
                    }

                    if (event.getAdditionalData() != null) {
                        String[] channels = event.getAdditionalData().split(",");
                        for (String channel : channels) {
                            if (channel != null && !channel.isEmpty()) {
                                processMessage(new IrcMessage(channel, oldNick + " is now known as", newNick, IrcMessage.MessageType.NICK_CHANGE, Instant.now()));
                            }
                        }
                    }
                    break;

                case KICK:
                    if (!config.hideConnectionMessages()) {
                        String[] kickParts = event.getMessage().split(" ", 2);
                        String kickedUser = kickParts[0];
                        String kickReason = kickParts.length > 1 ? kickParts[1] : "";
                        processMessage(new IrcMessage(event.getTarget(), event.getSource() + " kicked " + kickedUser, kickReason, IrcMessage.MessageType.KICK, Instant.now()));
                    }
                    break;

                case SERVER_NOTICE:
                case NOTICE:
                    if (source != null && source.endsWith(".SwiftIRC.net")) {
                        if (!config.filterServerNotices()) {
                            target = "System";
                        } else {
                            target = source;
                        }
                    } else {
                        source = "[N] " + source;
                        switch (config.filterNotices()) {
                            case Current:
                                target = panel != null ? panel.getCurrentChannelOn(networkId) : "System";
                                break;
                            case Status:
                                target = "System";
                                break;
                            case Private:
                                target = source;
                                break;
                        }
                    }
                    processMessage(new IrcMessage(target, source, event.getMessage(), IrcMessage.MessageType.NOTICE, Instant.now()));
                    break;

                case CHANNEL_MODE:
                    processMessage(new IrcMessage(event.getTarget(), event.getSource(), event.getMessage(), IrcMessage.MessageType.MODE, Instant.now()));
                    break;

                case USER_MODE:
                    processMessage(new IrcMessage("System", currentNick, event.getMessage(), IrcMessage.MessageType.MODE, Instant.now()));
                    break;

                case TOPIC:
                    processMessage(new IrcMessage(event.getTarget(), IrcMessage.TOPIC_SENDER, event.getMessage(), IrcMessage.MessageType.TOPIC, Instant.now()));
                    break;

                case NAMES:
                    processMessage(new IrcMessage(event.getTarget(), "Users", event.getMessage(), IrcMessage.MessageType.JOIN, Instant.now()));
                    break;

                case USERS_CHANGED:
                    if (panel != null) {
                        String usersChannel = event.getTarget();
                        // Snapshot on the IRC thread; it is immutable, so the EDT can hold it.
                        List<ChannelUserList.Entry> users = client.getChannelUsers(usersChannel);
                        SwingUtilities.invokeLater(() -> panel.setChannelUsers(BufferKey.of(networkId, usersChannel), users));
                    }
                    break;

                case CHANNEL_LIST:
                    if (panel != null) {
                        // Snapshot on the IRC thread; it is immutable, so the EDT can hold it.
                        List<ChannelListEntry> channelList = client.getChannelListSnapshot();
                        boolean truncated = client.isChannelListTruncated();
                        String listQuery = lastChannelListQuery;
                        SwingUtilities.invokeLater(
                                () -> panel.showChannelList(networkId, channelList, listQuery, truncated));
                    }
                    break;

                case CHANNEL_LIST_FAILED:
                    processMessage(new IrcMessage("System", "System",
                            "Channel list unavailable: " + event.getMessage(),
                            IrcMessage.MessageType.SYSTEM, Instant.now()));
                    if (panel != null) {
                        SwingUtilities.invokeLater(panel::cancelChannelListTimeout);
                    }
                    break;

                case NICK_IN_USE:
                    currentNick += "_";
                    processMessage(new IrcMessage("System", "System", "Nickname is already in use. Trying: " + currentNick, IrcMessage.MessageType.SYSTEM, Instant.now()));
                    sendRawLine("NICK " + currentNick);
                    break;

                case BAD_CHANNEL_KEY:
                    String badChannel = event.getTarget();
                    processMessage(new IrcMessage("System", "System", "Cannot join " + badChannel + ": " + event.getMessage(), IrcMessage.MessageType.SYSTEM, Instant.now()));
                    SwingUtilities.invokeLater(() -> {
                        Component parent = panel != null ? panel : null;
                        String password = JOptionPane.showInputDialog(parent, "Enter password for " + badChannel + ":", "Channel Key Required", JOptionPane.QUESTION_MESSAGE);
                        if (password != null && !password.isEmpty()) {
                            joinChannel(badChannel, password);
                        }
                    });
                    break;

                case WHOIS_REPLY:
                    processMessage(new IrcMessage("System", "WHOIS", event.getMessage(), IrcMessage.MessageType.SYSTEM, Instant.now()));
                    break;

                case SERVER_ERROR:
                    // Reported, but never disconnects: most error numerics (no such nick, not a
                    // channel operator) leave a perfectly healthy connection in place.
                    processMessage(new IrcMessage("System", "Error", event.getMessage(), IrcMessage.MessageType.SYSTEM, Instant.now()));
                    break;

                case ERROR:
                    processMessage(new IrcMessage("System", "Error", event.getMessage() != null ? event.getMessage() : "Unknown error", IrcMessage.MessageType.SYSTEM, Instant.now()));
                    disconnect();
                    break;

                case TOPIC_INFO:
                    processMessage(new IrcMessage(event.getTarget(), event.getSource(), event.getMessage(), IrcMessage.MessageType.TOPIC, Instant.now()));
                    break;

                case HISTORY_BATCH:
                    if (event.getHistoryMessages() != null && !event.getHistoryMessages().isEmpty()) {
                        for (SimpleIrcClient.IrcEvent accEvent : event.getHistoryMessages()) {
                            Instant timestamp;
                            try {
                                String timeStr = accEvent.getAdditionalData();
                                timestamp = (timeStr != null && !timeStr.isEmpty())
                                    ? Instant.parse(timeStr)
                                    : Instant.now();
                            } catch (Exception e) {
                                timestamp = Instant.now();
                            }
                            String sender = accEvent.getSource() != null ? accEvent.getSource() : "";
                            if (accEvent.getType() == SimpleIrcClient.IrcEvent.Type.ACTION) {
                                sender = "* " + sender;
                            }
                            processMessage(new IrcMessage(
                                event.getTarget(), sender, accEvent.getMessage(),
                                IrcMessage.MessageType.HISTORY, timestamp
                            ));
                        }
                        processMessage(new IrcMessage(
                            event.getTarget(), "*", "--- Begin of chat ---",
                            IrcMessage.MessageType.HISTORY_SEPARATOR, Instant.now()
                        ));
                    }
                    break;
            }
        });
    }
}