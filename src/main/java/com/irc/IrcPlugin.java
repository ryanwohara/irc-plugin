package com.irc;

import com.google.inject.Provides;
import com.irc.emoji.EmojiParser;
import com.irc.emoji.EmojiService;
import lombok.extern.slf4j.Slf4j;
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

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    @Nullable
    private IrcAdapter ircAdapter;
    private IrcPanel panel;
    private String currentNick;
    @Inject
    private EmojiService emojiService;

    private static final Pattern VALID_WINKS = Pattern.compile("^.[opdOPD)(]");
    private static final Pattern STRIP_STYLES = Pattern.compile("\u0002|\u0003(\\d\\d?(,\\d\\d)?)?|\u001D|\u0015|\u000F");

    @Override
    protected void startUp() {
        if (config.sidePanel()) {
            setupPanel();
        }
        emojiService.initialize();

        connectToIrc();

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
        ircAdapter.initialize(config, this::processMessage, panel);
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
        if (ircAdapter == null) return;

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

            case "go":
                if (!arg.isEmpty()) {
                    for (String channel : panel.getChannelPanes().keySet()) {
                        if (channel.contains(arg)) {
                            panel.setFocusedChannel(channel);
                            break;
                        }
                    }
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
        if (ircAdapter == null) return;

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
                "/away [message] - Set or remove away status",
                "/go <channel> - Focus on this channel (uses regex)",
                "/join <#channel> - Join a channel",
                "/leave [#channel] - Leave a channel",
                "/me <action> - Send action message",
                "/mode [#channel] [+modes|-modes] - Modify channel modes",
                "/msg <nick> <message> - Send private message",
                "/notice <nick> <message> - Send notice",
                "/topic [#channel] [topic] - View or set the channel topic",
                "/umode [+modes|-modes] - Modify user modes",
                "/whois <nick> - Query user information",
                "/bs <message> - Talk to BotServ",
                "/cs <message> - Talk to ChanServ",
                "/hs <message> - Talk to HostServ",
                "/ms <message> - Talk to MemoServ",
                "/ns <message> - Talk to NickServ"
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
        if (ircAdapter == null) return;

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
            } else if (panel != null && !panel.isPane(name) && panel.getCurrentChannel().startsWith("#")) {
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
        if (ircAdapter == null) return;

        if (channel.startsWith("#")) {
            ircAdapter.leaveChannel(channel);
        }

        if (panel != null) {
            SwingUtilities.invokeLater(() -> panel.removeChannel(channel));
        }
    }

    private void leaveChannel(String channel, String reason) {
        if (ircAdapter == null) return;

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
        if (ircAdapter == null) return;

        ircAdapter.sendMessage(target, message);
    }

    private void sendAction(String target, String message) {
        if (ircAdapter == null) return;

        ircAdapter.sendAction(target, message);
    }

    private String stripStyles(String message) {
        return STRIP_STYLES.matcher(message).replaceAll("");
    }

    private void processMessage(IrcMessage message) {
        IrcMessage.MessageType[] chatBoxEvents = {IrcMessage.MessageType.QUIT, IrcMessage.MessageType.NICK_CHANGE};
        if (client.getGameState() == GameState.LOGGED_IN
                && ((config.activeChannelOnly()
                && (panel.getCurrentChannel().equals(message.getChannel())
                || (message.getChannel().equals("System") && Arrays.binarySearch(chatBoxEvents, message.getType()) > -1)))
                || !config.activeChannelOnly())) {
            chatMessageManager.queue(QueuedMessage.builder()
                    .type(config.getChatboxType().getType())
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
        if (!configChanged.getGroup().equals("irc") || configChanged.getKey().equals("fontFamily") || configChanged.getKey().equals("fontSize")) {
            return;
        }

        try {
            stopIrcPanel();
        } catch (Exception ignored) {
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
        if (!"chatDefaultReturn".equals(event.getEventName()) || ircAdapter == null) {
            return;
        }

        String message = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);

        Matcher matcher = VALID_WINKS.matcher(message);

        if (message.startsWith(config.prefix())
                && !matcher.matches()) {
            final int[] intStack = client.getIntStack();
            int intStackCount = client.getIntStackSize();
            intStack[intStackCount - 3] = 1;

            handleMessageSend(panel.getCurrentChannel(), message.substring(1));
        }
    }
}