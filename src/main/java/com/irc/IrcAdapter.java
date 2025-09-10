package com.irc;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Adapter class to bridge between SimpleIrcClient
 */
@Slf4j
public class IrcAdapter {
    @Getter
    private SimpleIrcClient client;
    private String currentNick;
    private final Map<String, Set<String>> channelUsers = new HashMap<>();
    private Consumer<IrcMessage> messageConsumer;
    private IrcConfig config;
    private IrcPanel panel;

    public IrcAdapter() {
        client = new SimpleIrcClient();
    }

    /**
     * Initialize the client with the provided config
     */
    public void initialize(IrcConfig config, Consumer<IrcMessage> messageConsumer, IrcPanel panel, String currentNick) {
        this.messageConsumer = messageConsumer;
        this.currentNick = currentNick;
        this.config = config;
        this.panel = panel;

        client = new SimpleIrcClient()
                .server(config.server().getHostname(), 6697, true)
                .credentials(currentNick, "runelite", currentNick);

        if (config.password() != null && !config.password().isEmpty()) {
            client.password(config.password());
        }

        setupEventHandlers();
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
                Instant.now()
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
                Instant.now()
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
            messageConsumer.accept(message);
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
                    processMessage(new IrcMessage("System", "System", "Connected to IRC server, registering...", IrcMessage.MessageType.SYSTEM, Instant.now()));
                    break;

                case REGISTERED:
                    processMessage(new IrcMessage("System", "System", "Registration complete - ready for commands", IrcMessage.MessageType.SYSTEM, Instant.now()));
                    processMessage(new IrcMessage("System", "System", "Welcome to IRC! To chat in the current channel, use '" + config.prefix() + "' followed by your message in the game chatbox.", IrcMessage.MessageType.SYSTEM, Instant.now()));
                    processMessage(new IrcMessage("System", "System", "For a list of commands, type '/help' in the side panel input box.", IrcMessage.MessageType.SYSTEM, Instant.now()));
                    if (config.password() != null && !config.password().isEmpty()) {
                        client.sendMessage("NickServ", "id " + config.password());
                    }
                    break;

                case DISCONNECT:
                    processMessage(new IrcMessage("System", "System", "Disconnected from IRC", IrcMessage.MessageType.SYSTEM, Instant.now()));
                    for (String channel : client.getChannels()) {
                        processMessage(new IrcMessage(channel, "System", "Disconnected from IRC", IrcMessage.MessageType.SYSTEM, Instant.now()));
                    }
                    break;

                case MESSAGE:
                    if (Objects.equals(target, source)) {
                        switch (config.filterPMs()) {
                            case Current:
                                source = "[PM] " + source;
                                target = panel != null ? panel.getCurrentChannel() : "System";
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
                    processMessage(new IrcMessage(target, source, event.getMessage(), IrcMessage.MessageType.CHAT, Instant.now()));
                    break;

                case ACTION:
                    processMessage(new IrcMessage(event.getTarget(), "* " + event.getSource(), event.getMessage(), IrcMessage.MessageType.CHAT, Instant.now()));
                    break;

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

                    if (panel != null && panel.isPane(oldNick)) {
                        SwingUtilities.invokeLater(() -> panel.renameChannel(oldNick, newNick));
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
                                target = panel != null ? panel.getCurrentChannel() : "System";
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
                    processMessage(new IrcMessage(event.getTarget(), "* Topic", event.getMessage(), IrcMessage.MessageType.TOPIC, Instant.now()));
                    break;

                case NAMES:
                    processMessage(new IrcMessage(event.getTarget(), "Users", event.getMessage(), IrcMessage.MessageType.JOIN, Instant.now()));
                    break;

                case NICK_IN_USE:
                    processMessage(new IrcMessage("System", "System", "Nickname '" + event.getMessage() + "' is already in use.", IrcMessage.MessageType.SYSTEM, Instant.now()));
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

                case ERROR:
                    processMessage(new IrcMessage("System", "Error", event.getMessage() != null ? event.getMessage() : "Unknown error", IrcMessage.MessageType.SYSTEM, Instant.now()));
                    break;

                case TOPIC_INFO:
                    processMessage(new IrcMessage(event.getTarget(), event.getSource(), event.getMessage(), IrcMessage.MessageType.TOPIC, Instant.now()));
                    break;
            }
        });
    }
}