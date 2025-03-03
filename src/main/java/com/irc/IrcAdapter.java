package com.irc;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Adapter class to bridge between SimpleIrcClient
 */
@Slf4j
public class IrcAdapter {
    private SimpleIrcClient client;
    private String currentNick;
    private final Map<String, Set<String>> channelUsers = new HashMap<>();
    private Consumer<IrcMessage> messageConsumer;

    public IrcAdapter() {
        client = new SimpleIrcClient();
    }

    /**
     * Initialize the client with the provided config
     */
    public void initialize(IrcConfig config, Consumer<IrcMessage> messageConsumer) {
        this.messageConsumer = messageConsumer;
        this.currentNick = config.username();

        client = new SimpleIrcClient()
                .server(config.server().getHostname(), 6697, true)
                .credentials(config.username(), "runelite", config.username());

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
        client.disconnect();
    }

    /**
     * Join a channel
     */
    public void joinChannel(String channel, String password) {
        // Use executeWhenRegistered to ensure we're properly connected to the server
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

        // Create a message for the local display
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

        // Create a message for the local display
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

        // Create a message for the local display
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
        this.currentNick = nick;
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
            switch (event.getType()) {
                case CONNECT:
                    processMessage(new IrcMessage(
                            "System",
                            "System",
                            "Connected to IRC server, registering...",
                            IrcMessage.MessageType.SYSTEM,
                            Instant.now()
                    ));
                    break;

                case REGISTERED:
                    processMessage(new IrcMessage(
                            "System",
                            "System",
                            "Registration complete - ready for commands",
                            IrcMessage.MessageType.SYSTEM,
                            Instant.now()
                    ));
                    break;

                case DISCONNECT:
                    processMessage(new IrcMessage(
                            "System",
                            "System",
                            "Disconnected from IRC",
                            IrcMessage.MessageType.SYSTEM,
                            Instant.now()
                    ));
                    break;

                case MESSAGE:
                    processMessage(new IrcMessage(
                            event.getTarget(),
                            event.getSource(),
                            event.getMessage(),
                            IrcMessage.MessageType.CHAT,
                            Instant.now()
                    ));
                    break;

                case ACTION:
                    processMessage(new IrcMessage(
                            event.getTarget(),
                            "* " + event.getSource(),
                            event.getMessage(),
                            IrcMessage.MessageType.CHAT,
                            Instant.now()
                    ));
                    break;

                case JOIN:
                    processMessage(new IrcMessage(
                            event.getTarget(),
                            event.getSource(),
                            "joined",
                            IrcMessage.MessageType.JOIN,
                            Instant.now()
                    ));
                    break;

                case PART:
                    processMessage(new IrcMessage(
                            event.getTarget(),
                            event.getSource(),
                            "left" + (event.getMessage() != null ? ": " + event.getMessage() : ""),
                            IrcMessage.MessageType.PART,
                            Instant.now()
                    ));
                    break;

                case QUIT:
                    processMessage(new IrcMessage(
                            "System",
                            event.getSource(),
                            "Quit" + (event.getMessage() != null ? ": " + event.getMessage() : ""),
                            IrcMessage.MessageType.QUIT,
                            Instant.now()
                    ));
                    
                    if (event.getAdditionalData() != null && !event.getAdditionalData().isEmpty()) {
                        String[] channels = event.getAdditionalData().split(",");
                        for (String channel : channels) {
                            processMessage(new IrcMessage(
                                channel,
                                event.getSource(),
                                "Quit" + (event.getMessage() != null ? ": " + event.getMessage() : ""),
                                IrcMessage.MessageType.QUIT,
                                Instant.now()
                            ));
                        }
                    }
                break;

                case NICK_CHANGE:
                    String oldNick = event.getSource();
                    String newNick = event.getMessage();

                    if (oldNick.equals(currentNick)) {
                        currentNick = newNick;
                    }

                    processMessage(new IrcMessage(
                            "System",
                            "System",
                            oldNick + " is now known as " + newNick,
                            IrcMessage.MessageType.NICK_CHANGE,
                            Instant.now()
                    ));
                    break;

                case KICK:
                    String[] kickParts = event.getMessage().split(" ", 2);
                    String kickedUser = kickParts[0];
                    String kickReason = kickParts.length > 1 ? kickParts[1] : "";

                    processMessage(new IrcMessage(
                            event.getTarget(),
                            event.getSource(),
                            "kicked " + kickedUser + " (" + kickReason + ")",
                            IrcMessage.MessageType.KICK,
                            Instant.now()
                    ));
                    break;

                case NOTICE:
                    processMessage(new IrcMessage(
                            event.getTarget(),
                            event.getSource(),
                            event.getMessage(),
                            IrcMessage.MessageType.NOTICE,
                            Instant.now()
                    ));
                    break;

                case SERVER_NOTICE:
                    processMessage(new IrcMessage(
                            "System",
                            event.getSource(),
                            event.getMessage(),
                            IrcMessage.MessageType.NOTICE,
                            Instant.now()
                    ));
                    break;

                case CHANNEL_MODE:
                    processMessage(new IrcMessage(
                            event.getTarget(),
                            "* " + event.getSource() + " sets modes",
                            event.getMessage(),
                            IrcMessage.MessageType.MODE,
                            Instant.now()
                    ));
                    break;

                case USER_MODE:
                    processMessage(new IrcMessage(
                            "System",
                            currentNick,
                            event.getMessage(),
                            IrcMessage.MessageType.MODE,
                            Instant.now()
                    ));
                    break;

                case TOPIC:
                    processMessage(new IrcMessage(
                            event.getTarget(),
                            "* Topic set by " + event.getSource(),
                            event.getMessage(),
                            IrcMessage.MessageType.TOPIC,
                            Instant.now()
                    ));
                    break;

                case NAMES:
                    processMessage(new IrcMessage(
                            event.getTarget(),
                            "Users",
                            event.getMessage(),
                            IrcMessage.MessageType.JOIN,
                            Instant.now()
                    ));
                    break;

                case NICK_IN_USE:
                    String fallbackNick = event.getMessage() + "_";
                    processMessage(new IrcMessage(
                            "System",
                            "System",
                            "Nickname is already in use. Trying: " + fallbackNick,
                            IrcMessage.MessageType.SYSTEM,
                            Instant.now()
                    ));
                    break;

                case ERROR:
                    processMessage(new IrcMessage(
                            "System",
                            "Error",
                            event.getMessage() != null ? event.getMessage() : "Unknown error",
                            IrcMessage.MessageType.SYSTEM,
                            Instant.now()
                    ));
                    break;
            }
        });
    }
}