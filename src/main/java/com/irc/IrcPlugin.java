package com.irc;

import com.google.common.base.Strings;
import com.google.inject.Provides;
import com.vdurmont.emoji.EmojiParser;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.VarClientStr;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.event.channel.*;
import org.kitteh.irc.client.library.event.client.ClientReceiveNumericEvent;
import org.kitteh.irc.client.library.event.connection.*;
import org.kitteh.irc.client.library.event.user.*;
import net.engio.mbassy.listener.Handler;
import org.kitteh.irc.client.library.feature.auth.SaslPlain;

import javax.inject.Inject;
import javax.swing.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@PluginDescriptor(
        name = "IRC",
        description = "Integrates IRC with the OSRS chatbox"
)
@Slf4j
public class IrcPlugin extends Plugin {
    @Inject
    private IrcConfig config;
    @Inject
    private net.runelite.api.Client client;
    @Inject
    private ChatMessageManager chatMessageManager;
    @Inject
    private ClientToolbar clientToolbar;

    private Client ircClient;

    private IrcPanel panel;

    @Override
    protected void startUp() {
        connectToIrc();
        setupPanel();
        joinDefaultChannel();
    }

    @Override
    protected void shutDown() {
        if (ircClient != null) {
            ircClient.shutdown("Plugin shutting down");
            ircClient = null;
        }
        if (panel != null) {
            clientToolbar.removeNavigation(panel.getNavigationButton());
            panel = null;
        }
    }

    @Provides
    IrcConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(IrcConfig.class);
    }

    private void connectToIrc() {
        if (config.username().isEmpty()) {
            return;
        }

        ircClient = Client.builder()
                .nick(config.username())
                .user("runelite")
                .realName(config.username())
                .server()
                .host("irc.swiftirc.net")
                .port(6697)
                .secure(true)
                .then()
                .build();

        if(!config.username().isEmpty() && !config.password().isEmpty()) {
            ircClient.getAuthManager().addProtocol(new SaslPlain(ircClient, config.username(), config.password()));
        }

        ircClient.getEventManager().registerEventListener(new IrcEventHandler());
        ircClient.connect();
    }

    private void setupPanel() {
        panel = injector.getInstance(IrcPanel.class);
        panel.init(
                this::handleMessageSend,
                this::handleChannelJoin,
                this::handleChannelLeave
        );
        panel.initializeGui();
        clientToolbar.addNavigation(panel.getNavigationButton());
    }

    private void joinDefaultChannel() {
        if (!Strings.isNullOrEmpty(config.username())) {
            String channel;
            if (Strings.isNullOrEmpty(config.channel())) {
                channel = "#rshelp";
            } else {
                channel = config.channel().toLowerCase();
                if (!channel.startsWith("#")) {
                    channel = "#" + channel;
                }
                if (channel.contains(",")) {
                    channel = channel.split(",")[0];
                }
            }
            joinChannel(channel.startsWith("#") ? channel : "#" + channel);
        }
    }

    private void handleMessageSend(String channel, String message) {
        if (message.startsWith("/")) {
            handleCommand(message);
        } else {
            sendMessage(channel, message);
        }
    }

    private void handleCommand(String command) {
        String[] parts = command.split(" ", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "/join":
                if (!arg.isEmpty()) {
                    joinChannel(arg.startsWith("#") ? arg : "#" + arg);
                }
                break;
            case "/leave":
                if (!arg.isEmpty()) {
                    leaveChannel(arg.startsWith("#") ? arg : "#" + arg);
                }
                break;
        }
    }

    private void joinChannel(String channel) {
        ircClient.addChannel(channel);
        if (panel != null) {
            SwingUtilities.invokeLater(() -> panel.addChannel(channel));
        }
    }

    private void leaveChannel(String channel) {
        ircClient.removeChannel(channel);
        if (panel != null) {
            SwingUtilities.invokeLater(() -> panel.removeChannel(channel));
        }
    }

    private void handleChannelJoin(String channel) {
        joinChannel(channel);
    }

    private void handleChannelLeave(String channel) {
        leaveChannel(channel);
    }

    private void sendMessage(String channel, String message) {
        String target = channel;

        if (channel.startsWith("PM: ")) {
            target = channel.substring(4);
        }

        ircClient.sendMessage(target, message);

        processMessage(new IrcMessage(
                channel,
                config.username(),
                message,
                IrcMessage.MessageType.PRIVATE,
                Instant.now()
        ));
    }

    private void processMessage(IrcMessage message) {
        if (client.getGameState() == GameState.LOGGED_IN) {
            chatMessageManager.queue(QueuedMessage.builder()
                    .type(ChatMessageType.FRIENDSCHAT)
                    .sender("IRC")
                    .name(message.getChannel() + " | " + message.getSender())
                    .runeLiteFormattedMessage(new ChatMessageBuilder()
                            .append(ChatColorType.NORMAL)
                            .append(EmojiParser.parseToAliases(message.getContent()))
                            .build())
                    .timestamp((int) (message.getTimestamp().getEpochSecond()))
                    .build());
        }

        if (panel != null) {
            SwingUtilities.invokeLater(() -> panel.addMessage(message));
        }
    }

    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent event) {
        if (!"chatDefaultReturn".equals(event.getEventName())) {
            return;
        }

        String message = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);
        String prefix = config.prefix();

        if (message.startsWith(prefix)) {
            final int[] intStack = client.getIntStack();
            int intStackCount = client.getIntStackSize();
            intStack[intStackCount - 3] = 1;

            message = message.substring(prefix.length()).trim();
            if (!message.isEmpty() && ircClient != null) {
                sendMessage(panel.getCurrentChannel(), message);
            }
        }
    }

    private class IrcEventHandler {
        @Handler
        public void onConnect(ClientConnectionEstablishedEvent event) {
            processMessage(new IrcMessage(
                    "System",
                    "System",
                    "Connected to IRC",
                    IrcMessage.MessageType.SYSTEM,
                    Instant.now()
            ));
        }

        @Handler
        public void onDisconnect(ClientConnectionEndedEvent event) {
            processMessage(new IrcMessage(
                    "System",
                    "System",
                    "Disconnected from IRC",
                    IrcMessage.MessageType.SYSTEM,
                    Instant.now()
            ));
        }

        @Handler
        public void onChannelMessage(ChannelMessageEvent event) {
            processMessage(new IrcMessage(
                    event.getChannel().getName(),
                    event.getActor().getNick(),
                    event.getMessage(),
                    IrcMessage.MessageType.CHAT,
                    Instant.now()
            ));
        }

        @Handler
        public void onPrivateMessage(PrivateMessageEvent event) {
            String pmChannel = "PM: " + event.getActor().getNick();
            if (panel != null) {
                panel.addChannel(pmChannel);
            }
            processMessage(new IrcMessage(
                    pmChannel,
                    event.getActor().getNick(),
                    event.getMessage(),
                    IrcMessage.MessageType.PRIVATE,
                    Instant.now()
            ));
        }

        @Handler
        public void onNickChange(UserNickChangeEvent event) {
            processMessage(new IrcMessage(
                    "System",
                    "System",
                    event.getOldUser() + " is now known as " + event.getNewUser(),
                    IrcMessage.MessageType.NICK_CHANGE,
                    Instant.now()
            ));
        }

        @Handler
        public void onChannelJoin(ChannelJoinEvent event) {
            processMessage(new IrcMessage(
                    event.getChannel().getName(),
                    event.getActor().getNick(),
                    "joined",
                    IrcMessage.MessageType.JOIN,
                    Instant.now()
            ));
        }

        @Handler
        public void onChannelPart(ChannelPartEvent event) {
            if(!event.getActor().getNick().equals(ircClient.getNick())) {
                processMessage(new IrcMessage(
                        event.getChannel().getName(),
                        event.getActor().getNick(),
                        "left",
                        IrcMessage.MessageType.PART,
                        Instant.now()
                ));
            }
        }

        @Handler
        public void onQuit(UserQuitEvent event) {
            processMessage(new IrcMessage(
                    "System",
                    event.getActor().getNick(),
                    "quit",
                    IrcMessage.MessageType.QUIT,
                    Instant.now()
            ));
        }

        @Handler
        public void onNumericReply(ClientReceiveNumericEvent event) {
            int code = event.getNumeric();
            List<String> parameters = event.getParameters();

            if (code == 353) { // RPL_NAMREPLY - User list
                String channel = parameters.get(2);
                String usersRaw = parameters.get(3);
                List<String> users = Arrays.stream(usersRaw.split(" "))
                        .map(s -> s.split("!")[0])
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());

                processMessage(new IrcMessage(
                        channel,
                        "User List",
                        users.toString(),
                        IrcMessage.MessageType.JOIN,
                        Instant.now()
                ));
            }
        }
    }
}