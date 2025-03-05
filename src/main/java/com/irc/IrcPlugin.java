package com.irc;

import com.google.inject.Provides;
import com.irc.emoji.EmojiParser;
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

import javax.inject.Inject;
import javax.swing.*;
import java.time.Instant;
import java.util.Arrays;

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

    private IrcAdapter ircAdapter;
    private IrcPanel panel;
    private String currentNick;

    @Override
    protected void startUp() {
        connectToIrc();
        if (config.sidePanel()) {
            setupPanel();
        }

        // Join default channel after connection
        joinDefaultChannel();
    }

    @Override
    protected void shutDown() {
        if (panel != null) {
            if (config.sidePanel()) {
                clientToolbar.removeNavigation(panel.getNavigationButton());
            }
            panel = null;
        }
        if (ircAdapter != null) {
            ircAdapter.disconnect("Plugin shutting down");
            ircAdapter = null;
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

        ircAdapter = new IrcAdapter();
        ircAdapter.initialize(config, this::processMessage);
        ircAdapter.connect();
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
        if (!config.username().isEmpty()) {
            String channel;
            if (config.channel().isEmpty()) {
                channel = "#rshelp";
            } else {
                channel = config.channel().toLowerCase();
                if (!channel.startsWith("#")) {
                    channel = "#" + channel;
                }
            }
            joinChannel(channel, config.channelPassword());
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
                    String msg = msgParts[1];

                    if (panel != null) {
                        SwingUtilities.invokeLater(() -> panel.addChannel(target));
                    }

                    sendMessage(target, msg);
                }
                if (panel != null && msgParts.length > 0) {
                    panel.addChannel(msgParts[0]);
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
                    String noticeMsg = noticeParts[1];
                    ircAdapter.sendNotice(target, noticeMsg);
                }
                break;

            case "whois":
                if (!arg.isEmpty()) {
                    ircAdapter.sendRawLine("WHOIS " + arg);
                }
                break;

            case "away":
                if (arg.isEmpty()) {
                    ircAdapter.sendRawLine("AWAY"); // Remove away status
                } else {
                    ircAdapter.sendRawLine("AWAY :" + arg);
                }
                break;

            case "names":
                if (panel.getCurrentChannel().startsWith("#")) {
                    ircAdapter.sendRawLine("NAMES " + panel.getCurrentChannel());
                }
                break;

            case "nick":
                if (!arg.isEmpty() && arg.split(" ").length == 1) {
                    ircAdapter.setNick(arg);
                    processMessage(new IrcMessage(
                            "System",
                            "System",
                            currentNick + " is now known as " + arg,
                            IrcMessage.MessageType.NICK_CHANGE,
                            Instant.now()
                    ));
                    currentNick = arg;
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
                    ircAdapter.sendRawLine("MODE " + currentNick + " :" + arg);
                } else {
                    ircAdapter.sendRawLine("MODE " + currentNick);
                }
                break;

            case "topic":
                if (!arg.isEmpty()) {
                    ircAdapter.sendRawLine("TOPIC " + panel.getCurrentChannel() + " :" + arg);
                } else {
                    ircAdapter.sendRawLine("TOPIC " + panel.getCurrentChannel());
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
            ircAdapter.sendRawLine("MODE " + mode);
        } else if (split.length > 0) {
            ircAdapter.sendRawLine("MODE " + panel.getCurrentChannel() + " " + mode);
        } else {
            ircAdapter.sendRawLine("MODE " + panel.getCurrentChannel());
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
        ircAdapter.joinChannel(channel, password);
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
            ircAdapter.leaveChannel(channel);
        }

        if (panel != null) {
            SwingUtilities.invokeLater(() -> panel.removeChannel(channel));
        }
    }

    private void leaveChannel(String channel, String reason) {
        ircAdapter.leaveChannel(channel, reason);

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
        ircAdapter.sendMessage(target, message);
    }

    private void sendAction(String target, String message) {
        ircAdapter.sendAction(target, message);
    }

    private String stripStyles(String message) {
        return message.replaceAll("\u0002|\u0003(\\d\\d?(,\\d\\d)?)?|\u001D|\u0015|\u000F", "");
    }

    private void processMessage(IrcMessage message) {
        if (client.getGameState() == GameState.LOGGED_IN
                && ((config.activeChannelOnly() && panel.getCurrentChannel().equals(message.getChannel()))
                    || !config.activeChannelOnly())
                && (message.getType() != IrcMessage.MessageType.QUIT
                    || message.getChannel().equals("System"))) {
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
        } catch (Exception e) {
        }

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
}