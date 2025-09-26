package com.irc;

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

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.*;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Instant;
import java.util.*;
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
    private IrcOverlay overlay;
    @Nullable
    private IrcAdapter ircAdapter;
    private IrcPanel panel;
    private String currentNick;
    @Inject
    private EmojiService emojiService;

    private static final Pattern VALID_WINKS = Pattern.compile("^;([opdOPD)(<>]|[-_];)");
    private static final Pattern STRIP_STYLES = Pattern.compile("\u0002|\u0003(\\d\\d?(,\\d\\d)?)?|\u001D|\u0015|\u000F");

    private final Map<String, String> channelPasswords = new HashMap<>();

    @Override
    protected void startUp() {
        setupPanel();
        if (config.sidePanel()) {
            clientToolbar.addNavigation(panel.getNavigationButton());
        }

        SwingUtilities.invokeLater(() -> {
            Window window = SwingUtilities.getWindowAncestor(panel);
            if (window != null) {
                window.addWindowFocusListener(new WindowAdapter() {
                    @Override
                    public void windowLostFocus(WindowEvent e) {
                        if (panel != null) {
                            panel.hideAllPreviews();
                        }
                    }
                });
                window.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowDeactivated(WindowEvent e) {
                        if (panel != null) {
                            panel.hideAllPreviews();
                        }
                    }

                    @Override
                    public void windowIconified(WindowEvent e) {
                        if (panel != null) {
                            panel.hideAllPreviews();
                        }
                    }
                });
                window.addComponentListener(new ComponentAdapter() {
                    @Override
                    public void componentMoved(ComponentEvent e) {
                        if (panel != null) {
                            panel.hideAllPreviews();
                        }
                    }
                });
                window.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseExited(MouseEvent e) {
                        if (panel != null) {
                            if (!window.getBounds().contains(e.getLocationOnScreen())) {
                                panel.hideAllPreviews();
                            }
                        }
                    }
                });
                window.addMouseWheelListener(e -> {
                    if (panel != null) {
                        panel.hideAllPreviews();
                    }
                });
            }
        });
        overlay = new IrcOverlay(client, panel, config);
        overlayManager.add(overlay);
        overlay.subscribeEvents();
        emojiService.initialize();
        connectToIrc();
        joinDefaultChannel();
    }

    @Override
    protected void shutDown() {
        if (panel != null) {
            clientToolbar.removeNavigation(panel.getNavigationButton());
            panel = null;
        }
        if (ircAdapter != null) {
            ircAdapter.disconnect("Plugin shutting down");
            ircAdapter = null;
        }
        if (overlayManager != null) {
            overlayManager.remove(overlay);
            overlay = null;
        }
        channelPasswords.clear();
    }

    @Provides
    IrcConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(IrcConfig.class);
    }

    private void connectToIrc() {
        if (Strings.isNullOrEmpty(config.username())) {
            currentNick = "RLGuest" + (int) (Math.random() * 9999 + 1);
        } else {
            currentNick = config.username().replace(" ", "_");
        }

        ircAdapter = new IrcAdapter();
        ircAdapter.initialize(config, this::processMessage, panel, currentNick);
        ircAdapter.connect();
    }

    private void setupPanel() {
        panel = injector.getInstance(IrcPanel.class);
        panel.init(
                this::handleMessageSend,
                this::handleChannelJoin,
                this::handleChannelLeave,
                this::handleReconnect
        );
        panel.initializeGui();
    }

    private void joinDefaultChannel() {
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
        if (ircAdapter == null || panel == null) return;

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
                closePane(arg);
                break;

            case "quit":
                if (arg.isEmpty()) {
                    ircAdapter.disconnect("Quitting the plugin");
                } else {
                    ircAdapter.disconnect(arg);
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

                    SwingUtilities.invokeLater(() -> panel.addChannel(target));
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
                    ircAdapter.sendRawLine("AWAY");
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
                }
                break;

            case "id":
                if (!arg.isEmpty()) {
                    ircAdapter.getClient().sendMessage("NickServ", "identify " + arg);
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
        if (ircAdapter == null || panel == null) return;

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
                "/part [#channel] - Leave a channel (aliased as /leave)",
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
                "/ns <message> - Talk to NickServ",
                "/id <password - Log in to NickServ without logging it"
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

    private void joinChannel(String channels, String password) {
        for (String channel : channels.split(",")) {
            if (ircAdapter == null) return;
            if (password != null && !password.isEmpty()) {
                channelPasswords.put(channel.toLowerCase(), password);
            }
            ircAdapter.joinChannel(channel, password);
        }
    }

    private void closePane(String argument) {
        if (panel == null) return;

        String currentChannel = panel.getCurrentChannel();
        if ("System".equalsIgnoreCase(currentChannel) && (argument.isEmpty() || !argument.split(" ", 2)[0].startsWith("#"))) {
            return;
        }

        String[] parts = argument.split(" ", 2);

        String target = parts[0];
        String reason = parts.length > 1 ? parts[1] : null;

        if (argument.isEmpty()) {
            if (currentChannel.startsWith("#")) {
                leaveChannel(currentChannel);
            } else {
                SwingUtilities.invokeLater(() -> panel.removeChannel(currentChannel));
            }
        } else if (target.startsWith("#")) {
            if (reason != null) {
                leaveChannel(target, reason);
            } else {
                leaveChannel(target);
            }
        } else if (panel.isPane(target)) {
            SwingUtilities.invokeLater(() -> panel.removeChannel(target));
        } else {
            if (currentChannel.startsWith("#")) {
                leaveChannel(currentChannel, argument);
            }
        }
    }

    private void leaveChannel(String channel) {
        if (ircAdapter == null) return;

        if (channel.startsWith("#")) {
            ircAdapter.leaveChannel(channel);
            channelPasswords.remove(channel.toLowerCase());
            panel.removeChannel(channel);
        }

        if (panel != null) {
            SwingUtilities.invokeLater(() -> panel.removeChannel(channel));
        }
    }

    private void leaveChannel(String channel, String reason) {
        if (ircAdapter == null) return;

        if (channel.startsWith("#")) {
            ircAdapter.leaveChannel(channel, reason);
            channelPasswords.remove(channel.toLowerCase());
            panel.removeChannel(channel);
        }

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

    private void handleReconnect(Boolean ignored) {
        if (ircAdapter == null || panel == null) return;
        ircAdapter.disconnect("Reloading, brb");
        connectToIrc();
        for (String channel : panel.getChannelNames()) {
            if (channel.startsWith("#")) {
                String password = channelPasswords.getOrDefault(channel.toLowerCase(), "");
                handleChannelJoin(channel, password);
            }
        }
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

        if (panel != null) {
            if (!panel.getChannelNames().contains(message.getChannel())) {
                for (String channel : panel.getChannelNames()) {
                    if (channel.equalsIgnoreCase(message.getChannel())) {
                        panel.renameChannel(channel, message.getChannel());
                    }
                }
            }
        }

        if (client.getGameState() == GameState.LOGGED_IN) {
            boolean activeChannelCondition = panel == null || panel.getCurrentChannel().equals(message.getChannel());
            boolean isSystemEvent = message.getChannel().equals("System") && Arrays.binarySearch(chatBoxEvents, message.getType()) > -1;

            if (!config.activeChannelOnly() || (config.activeChannelOnly() && (activeChannelCondition || isSystemEvent))) {
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

        if ("sidePanel".equals(configChanged.getKey())) {
            if (panel != null) {
                clientToolbar.removeNavigation(panel.getNavigationButton());
                if (config.sidePanel()) {
                    clientToolbar.addNavigation(panel.getNavigationButton());
                }
            }
        } else if ("panelPriority".equals(configChanged.getKey())) {
            if (panel != null) {
                clientToolbar.removeNavigation(panel.getNavigationButton());
                if (config.sidePanel()) {
                    clientToolbar.addNavigation(panel.generateNavigationButton());
                }
            }
        } else if ("overlayEnabled".equals(configChanged.getKey())) {
            if (overlay != null) {
                overlay.setEnabled(config.overlayEnabled());
            }
        } else if ("overlayDynamic".equals(configChanged.getKey())) {
            if (overlay != null) {
                overlayManager.remove(overlay);
                overlay = new IrcOverlay(client, panel, config);
                overlayManager.add(overlay);
                overlay.subscribeEvents();
            }
        }
    }

    @Subscribe
    public void onScriptCallbackEvent(ScriptCallbackEvent event) {
        if (!"chatDefaultReturn".equals(event.getEventName()) || ircAdapter == null) {
            return;
        }

        String message = client.getVarcStrValue(VarClientID.CHATINPUT);
        Matcher matcher = VALID_WINKS.matcher(message);

        if (message.startsWith(config.prefix()) && !matcher.matches()) {
            final int[] intStack = client.getIntStack();
            int intStackCount = client.getIntStackSize();
            intStack[intStackCount - 3] = 1;

            String currentChannel = panel != null ? panel.getCurrentChannel() : this.config.channel();
            handleMessageSend(currentChannel, message.substring(1));
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