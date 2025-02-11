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
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.element.Actor;
import org.kitteh.irc.client.library.event.channel.*;
import org.kitteh.irc.client.library.event.client.ClientReceiveCommandEvent;
import org.kitteh.irc.client.library.event.client.ClientReceiveNumericEvent;
import org.kitteh.irc.client.library.event.connection.*;
import org.kitteh.irc.client.library.event.user.*;
import net.engio.mbassy.listener.Handler;
import org.kitteh.irc.client.library.feature.auth.SaslPlain;
import org.kitteh.irc.client.library.feature.filter.CommandFilter;

import javax.inject.Inject;
import javax.swing.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
    private String currentNick;

    @Override
    protected void startUp() {
        connectToIrc();
        if (config.sidePanel()) {
            setupPanel();
        }
        joinDefaultChannel();
    }

    @Override
    protected void shutDown() {
        if (ircClient != null) {
            ircClient.shutdown("Plugin shutting down");
            ircClient = null;
        }
        if (panel != null) {
            if (config.sidePanel()) {
                clientToolbar.removeNavigation(panel.getNavigationButton());
            }
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

        currentNick = config.username();

        ircClient = Client.builder()
                .nick(currentNick)
                .user("runelite")
                .realName(currentNick)
                .server()
                .host(config.server().getHostname())
                .port(6697)
                .secure(true)
                .then()
                .build();

        if(currentNick != null && config.password() != null) {
            if (!currentNick.isEmpty() && !config.password().isEmpty()) {
                ircClient.getAuthManager().addProtocol(new SaslPlain(ircClient, currentNick, config.password()));
            }
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

        if (config.sidePanel()) {
            clientToolbar.addNavigation(panel.getNavigationButton());
        }
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
            joinChannel(channel.startsWith("#") ? channel : "#" + channel, config.channelPassword());
        }
    }

    private void handleMessageSend(String channel, String message) {
        if (message.startsWith("/") ||
            (message.startsWith(config.prefix())
            && message.length() > config.prefix().length())) {
            handleCommand(message);
        } else {
            sendMessage(channel, message);
        }
    }

    private void handleCommand(String command) {
        String[] parts = command.split(" ", 2);
        String cmd = parts[0].toLowerCase().substring(1);
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "join":
                if (!arg.isEmpty()) {
                    String chan = arg.split(" ")[0];
                    String password = arg.split(" ").length > 1 ? arg.split(" ")[1] : "";
                    joinChannel(chan.startsWith("#") ? chan : "#" + chan, password);
                }
                break;

            case "c":
            case "close":
            case "leave":
            case "part":
                if (!arg.isEmpty()) {
                    closePane(arg);
                } else {
                    closePane("");
                }
                break;

            case "msg":
            case "query":
                String[] msgParts = arg.split(" ", 2);
                if (msgParts.length == 2) {
                    String target = msgParts[0];
                    String message = msgParts[1];

                    if (panel != null) {
                        SwingUtilities.invokeLater(() -> panel.addChannel(target));
                    }

                    sendMessage(target, message);
                }
                break;

            case "me":
                if (!arg.isEmpty()) {
                    sendAction(panel.getCurrentChannel(), arg);
                }
                break;

            case "notice":
                String[] noticeParts = arg.split(" ", 2);
                if (noticeParts.length == 2) {
                    String target = noticeParts[0];
                    String message = noticeParts[1];
                    ircClient.sendNotice(target, message);

                    processMessage(new IrcMessage(
                            "System",
                            currentNick,
                            "Notice to " + target + ": " + message,
                            IrcMessage.MessageType.SYSTEM,
                            Instant.now()
                    ));
                }
                break;

            case "whois":
                if (!arg.isEmpty()) {
                    ircClient.sendRawLine("WHOIS " + arg);
                }
                break;

            case "away":
                if (arg.isEmpty()) {
                    ircClient.sendRawLine("AWAY"); // Remove away status
                } else {
                    ircClient.sendRawLine("AWAY :" + arg);
                }
                break;

            case "names":
                if (panel.getCurrentChannel().startsWith("#")) {
                    ircClient.sendRawLine("NAMES " + panel.getCurrentChannel());
                }
                break;

            case "nick":
                if (!arg.isEmpty()) {
                    ircClient.sendRawLine("NICK :" + arg);
                }
                break;

            case "ns":
                if (!arg.isEmpty()) {
                    sendMessage("NickServ", arg);
                }
                break;

            case "cs":
                if (!arg.isEmpty()) {
                    sendMessage("ChanServ", arg);
                }
                break;

            case "bs":
                if (!arg.isEmpty()) {
                    sendMessage("BotServ", arg);
                }
                break;

            case "ms":
                if (!arg.isEmpty()) {
                    sendMessage("MemoServ", arg);
                }
                break;

            case "hs":
                if (!arg.isEmpty()) {
                    sendMessage("HostServ", arg);
                }
                break;

            case "mode":
                if (!arg.isEmpty()) {
                    mode(arg);
                } else {
                    mode("");
                }
                break;

            case "umode":
            case "umode2":
                if (!arg.isEmpty()) {
                    ircClient.sendRawLine("mode " + currentNick + " :" + arg);
                }
                break;

            case "topic":
                if (!arg.isEmpty()) {
                    ircClient.sendRawLine("TOPIC " + panel.getCurrentChannel() + " :" + arg);
                } else {
                    ircClient.sendRawLine("TOPIC " + panel.getCurrentChannel());
                }
                break;

            case "clear":
                panel.clearCurrentPane();
                break;

            case "help":
                showCommandHelp();
                break;

            default:
                processMessage(new IrcMessage(
                        "System",
                        "System",
                        "Unknown command: " + cmd,
                        IrcMessage.MessageType.SYSTEM,
                        Instant.now()
                ));
                break;
        }
    }

    private void mode(String mode) {
        String[] split = mode.split(" ");

        if (mode.startsWith("#")) {
            ircClient.sendRawLine("MODE " + mode);
        } else if (split.length > 0) {
            ircClient.sendRawLine("MODE " + panel.getCurrentChannel() + " " + mode);
        } else {
            ircClient.sendRawLine("MODE " + panel.getCurrentChannel());
        }
    }

    private void showCommandHelp() {
        String[] helpLines = {
                "Available commands:",
                "/join <channel> - Join a channel",
                "/leave <channel> - Leave a channel",
                "/msg <nick> <message> - Send private message",
                "/me <action> - Send action message",
                "/notice <nick> <message> - Send notice",
                "/whois <nick> - Query user information",
                "/away [message] - Set or remove away status"
        };

        for (String line : helpLines) {
            processMessage(new IrcMessage(
                    "System",
                    "System",
                    line,
                    IrcMessage.MessageType.SYSTEM,
                    Instant.now()
            ));
        }
    }

    private void joinChannel(String channel, String password) {
        ircClient.sendRawLine("JOIN " + channel + " " + password);
    }

    private void closePane(String name) {
        String[] split = name.split(" ", 2);

        if (split.length == 0 || name.isEmpty()) {
            if (panel.getCurrentChannel().startsWith("#")) {
                leaveChannel(panel.getCurrentChannel());
            } else if (panel != null && !panel.getCurrentChannel().equals("System")) {
                SwingUtilities.invokeLater(() -> panel.removeChannel(panel.getCurrentChannel()));
            }
        } else if (split.length == 1) {
            if (panel != null && !name.equals("System") && panel.isPane(name)) {
                SwingUtilities.invokeLater(() -> panel.removeChannel(name));
            }

            if (name.startsWith("#")) {
                leaveChannel(name);
            } else if (!panel.isPane(name) && panel.getCurrentChannel().startsWith("#")) {
                leaveChannel(panel.getCurrentChannel(), split[0]);
            }
        } else {
            if (name.startsWith("#")) {
                leaveChannel(split[0], split[1]);
                if (panel != null) {
                    SwingUtilities.invokeLater(() -> panel.removeChannel(split[0]));
                }
            } else if (panel != null && panel.isPane(split[0])) {
                SwingUtilities.invokeLater(() -> panel.removeChannel(split[0]));
            } else if (panel != null && !panel.isPane(split[0])) {
                String active = panel.getCurrentChannel();
                SwingUtilities.invokeLater(() -> panel.removeChannel(active));
                if (active.startsWith("#")) {
                    leaveChannel(active, name);
                }
            }
        }

    }

    private void leaveChannel(String channel) {
        if (channel.startsWith("#")) {
            ircClient.removeChannel(channel);
        }

        if (panel != null) {
            SwingUtilities.invokeLater(() -> panel.removeChannel(channel));
        }
    }

    private void leaveChannel(String channel, String reason) {
        ircClient.removeChannel(channel, reason);

        if (panel != null) {
            SwingUtilities.invokeLater(() -> panel.removeChannel(channel));
        }
    }

    private void handleChannelJoin(String channel, String password) {
        joinChannel(channel, password);
    }

    private void handleChannelLeave(String channel) {
        leaveChannel(channel);
    }

    private void sendMessage(String target, String message) {
        ircClient.sendMessage(target, message);

        processMessage(new IrcMessage(
                target,
                currentNick,
                message,
                IrcMessage.MessageType.PRIVATE,
                Instant.now()
        ));
    }

    private void sendAction(String target, String message) {
        ircClient.sendMessage(target, "\u0001ACTION " + message + "\u0001");

        processMessage(new IrcMessage(
                target,
                "* " + currentNick,
                message,
                IrcMessage.MessageType.PRIVATE,
                Instant.now()
        ));
    }

    private String stripStyles(String message) {
        return message.replaceAll("\u0002|\u0003(\\d\\d?(,\\d\\d)?)?|\u001D|\u0015|\u000F", "");
    }

    private void processMessage(IrcMessage message) {
        if (client.getGameState() == GameState.LOGGED_IN
                && ((config.activeChannelOnly() && panel.getCurrentChannel().equals(message.getChannel()))
                || !config.activeChannelOnly())) {
            chatMessageManager.queue(QueuedMessage.builder()
                    .type(ChatMessageType.FRIENDSCHAT)
                    .sender(message.getChannel())
                    .name(message.getSender())
                    .runeLiteFormattedMessage(
                            new ChatMessageBuilder()
                            .append(ChatColorType.NORMAL)
                            .append(EmojiParser.parseToAliases(
                                    stripStyles(message.getContent())
                            ))
                            .build())
                    .timestamp((int) (message.getTimestamp().getEpochSecond()))
                    .build());
        }

        if (panel != null) {
            SwingUtilities.invokeLater(() -> panel.addMessage(message));
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged configChanged) {
        if (!configChanged.getGroup().equals("irc")) {
            return;
        }

        try {
            stopIrcPanel();
        } catch (Exception e) {}

        if (config.sidePanel()) {
            clientToolbar.addNavigation(panel.generateNavigationButton());
        }
    }

    private void stopIrcPanel() {
        clientToolbar.removeNavigation(panel.getNavigationButton());
    }

    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent event) {
        if (!"chatDefaultReturn".equals(event.getEventName())) {
            return;
        }

        String message = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);

        if (message.startsWith(config.prefix())
                && !message.matches("^.[opOP)(]")) {
            final int[] intStack = client.getIntStack();
            int intStackCount = client.getIntStackSize();
            intStack[intStackCount - 3] = 1;

            handleMessageSend(panel.getCurrentChannel(), message.substring(1));
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

        @CommandFilter("PRIVMSG")
        @Handler
        public void privmsg(ClientReceiveCommandEvent event) {
            String timestamp = event.getRawMessage().split(" ",  2)[0];
            String rawMessage = event.getRawMessage().split(" ", 2)[1];

            String nick = event.getActor().getName().split("!")[0];
            String target = rawMessage.split(" ")[2];
            if (target.equalsIgnoreCase(currentNick)) {
                target = nick;
            }
            String msg = rawMessage.split(" ", 4)[3].substring(1);

            if (msg.startsWith("\u0001ACTION ") && msg.endsWith("\u0001")) {
                msg = msg.substring("\u0001ACTION ".length(), msg.length()-1);
                nick = "* " + nick;
            }

            processMessage(new IrcMessage(
                    target,
                    nick,
                    msg,
                    IrcMessage.MessageType.CHAT,
                    Instant.now()
            ));
        }

        @Handler
        public void onTopic(ChannelTopicEvent event) {
            Optional<Actor> setter = event.getNewTopic().getSetter();

            processMessage(new IrcMessage(
                    event.getChannel().getName(),
                    "* Topic set by " + (setter.map(Actor::getName).orElse("unknown!").split("!")[0]),
                    event.getNewTopic().getValue().orElse(""),
                    IrcMessage.MessageType.TOPIC,
                    Instant.now()
            ));
        }

        @Handler
        public void onServerNotice(ServerNoticeEvent event) {
            if (panel != null) {
                panel.addChannel(event.getActor().getName());
            }
            processMessage(new IrcMessage(
                    event.getActor().getName(),
                    event.getActor().getName(),
                    event.getMessage(),
                    IrcMessage.MessageType.NOTICE,
                    Instant.now()
            ));
        }

        @Handler
        public void onPrivateNotice(PrivateNoticeEvent event) {
            if (panel != null) {
                panel.addChannel(event.getActor().getNick());
            }
            processMessage(new IrcMessage(
                    event.getActor().getNick(),
                    event.getActor().getNick(),
                    event.getMessage(),
                    IrcMessage.MessageType.NOTICE,
                    Instant.now()
            ));
        }

        @Handler
        public void onChannelNotice(ChannelNoticeEvent event) {
            String pmChannel = "Notice: " + event.getActor().getNick() + " @ " + event.getChannel().getName();
            if (panel != null) {
                panel.addChannel(pmChannel);
            }
            processMessage(new IrcMessage(
                    pmChannel,
                    event.getActor().getNick(),
                    event.getMessage(),
                    IrcMessage.MessageType.NOTICE,
                    Instant.now()
            ));
        }

        @Handler
        public void onNickChange(UserNickChangeEvent event) {
            if (event.getOldUser().getNick().equals(currentNick)) {
                currentNick = event.getNewUser().getNick();
            }

            processMessage(new IrcMessage(
                    "System",
                    "System",
                    event.getOldUser().getNick() + " is now known as " + event.getNewUser().getNick(),
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
                    "Quit" + (!event.getMessage().isEmpty() ? ": " + event.getMessage() : ""),
                    IrcMessage.MessageType.QUIT,
                    Instant.now()
            ));
        }

        @Handler
        public void onChannelKick(ChannelKickEvent event) {
            processMessage(new IrcMessage(
                    event.getChannel().getName(),
                    event.getActor().getName().split("!")[0],
                    "kicked " + event.getTarget().getNick() + " (" + event.getMessage() + ")",
                    IrcMessage.MessageType.KICK,
                    Instant.now()
            ));
        }

        @Handler
        public void onNumericReply(ClientReceiveNumericEvent event) {
            int code = event.getNumeric();
            List<String> parameters = event.getParameters();
            String channel;

            switch (code) {
                case 221: // User modes
                    String modes = parameters.get(2);
                    processMessage(
                            new IrcMessage(
                                    "System",
                                    "System",
                                    modes,
                                    IrcMessage.MessageType.SYSTEM,
                                    Instant.now()
                            )
                    );


                case 353: // RPL_NAMREPLY - User list
                    channel = parameters.get(2);
                    String usersRaw = parameters.get(3);
                    List<String> users = Arrays.stream(usersRaw.split(" "))
                            .map(s -> s.split("!")[0])
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());

                    processMessage(new IrcMessage(
                            channel,
                            "Users",
                            users.toString(),
                            IrcMessage.MessageType.JOIN,
                            Instant.now()
                    ));
                    break;
                case 475: // Cannot join channel (+k)
                    channel = parameters.get(1);

                    processMessage(new IrcMessage(
                            channel,
                            "ERROR",
                            "Channel requires a passphrase.",
                            IrcMessage.MessageType.JOIN,
                            Instant.now()
                    ));
                    break;
                case 448: // Cannot join channel: Channel name contains illegal characters (must be valid UTF8)
                    channel = parameters.get(1);

                    processMessage(new IrcMessage(
                            channel,
                            "ERROR",
                            "Channel name contains invalid characters.",
                            IrcMessage.MessageType.JOIN,
                            Instant.now()
                    ));
                    break;
            }
        }

        @Handler
        public void onWhoisReply(ClientReceiveNumericEvent event) {
            if (event.getNumeric() >= 311 && event.getNumeric() <= 319) {
                List<String> parameters = event.getParameters();
                String whoisInfo = String.join(" ", parameters.subList(1, parameters.size()));

                processMessage(new IrcMessage(
                        "System",
                        "WHOIS",
                        whoisInfo,
                        IrcMessage.MessageType.SYSTEM,
                        Instant.now()
                ));
            }
        }

        @Handler
        public void onNickInUse(ClientReceiveNumericEvent event) {
            if (event.getNumeric() == 433) {
                String fallbackNick = currentNick + "_";
                processMessage(new IrcMessage(
                        "System",
                        "System",
                        "Nickname is already in use. Trying: " + fallbackNick,
                        IrcMessage.MessageType.SYSTEM,
                        Instant.now()
                ));

                ircClient.setNick(fallbackNick);
                currentNick = fallbackNick;
            }
        }
    }
}