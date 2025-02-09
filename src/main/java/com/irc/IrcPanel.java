package com.irc;

import com.google.inject.Provides;
import com.vdurmont.emoji.EmojiParser;
import lombok.Getter;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import net.runelite.client.util.ImageUtil;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.runelite.client.util.ColorUtil;

import static org.apache.commons.text.StringEscapeUtils.escapeHtml4;

public class IrcPanel extends PluginPanel {
    @Inject
    private IrcConfig config;
    @Inject
    private ConfigManager configManager;

    private JTabbedPane tabbedPane;
    private JTextField inputField;
    private Map<String, ChannelPane> channelPanes;
    @Getter
    private NavigationButton navigationButton;

    private BiConsumer<String, String> onMessageSend;
    private BiConsumer<String, String> onChannelJoin;
    private Consumer<String> onChannelLeave;
    private Font font;

    public void initializeGui() {
        setLayout(new BorderLayout());

        font = new Font(config.fontFamily(), Font.PLAIN, config.fontSize());

        tabbedPane = new JTabbedPane();
        tabbedPane.setPreferredSize(new Dimension(300, 425));
        inputField = new JTextField();
        inputField.setFont(font);
        channelPanes = new LinkedHashMap<>();

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));

        JButton addButton = new JButton("+");
        JButton removeButton = new JButton("-");
        addButton.setPreferredSize(new Dimension(45, 25));
        removeButton.setPreferredSize(new Dimension(45, 25));

        final JComboBox<String> fontComboBox = getStringJComboBox();

        addButton.addActionListener(e -> promptAddChannel());
        removeButton.addActionListener(e -> promptRemoveChannel());

        controlPanel.add(addButton);
        controlPanel.add(removeButton);
        controlPanel.add(fontComboBox);

        Action originalPasteAction = inputField.getActionMap().get("paste");
        Action customPasteAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                originalPasteAction.actionPerformed(e);
                String text = inputField.getText();
                inputField.setText(convertModernEmojis(text));
            }
        };
        inputField.getActionMap().put("paste", customPasteAction);

        Action ctrlKAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.out.println("ctrlKAction");
                inputField.setText("\u0003");
            }
        };
        KeyStroke ctrlK = KeyStroke.getKeyStroke(KeyEvent.VK_K, KeyEvent.CTRL_DOWN_MASK);
        inputField.getActionMap().put(ctrlK, ctrlKAction);

        inputField.addActionListener(e -> {
            String message = inputField.getText();
            System.out.println(message);
            if (!message.isEmpty() && onMessageSend != null) {
                onMessageSend.accept(getCurrentChannel(), message);
                inputField.setText("");
            }
        });

        add(controlPanel, BorderLayout.NORTH);
        add(tabbedPane, BorderLayout.CENTER);
        add(inputField, BorderLayout.SOUTH);

        navigationButton = NavigationButton.builder()
                .tooltip("IRC")
                .icon(ImageUtil.loadImageResource(getClass(), "icon.png"))
                .priority(10)
                .panel(this)
                .build();

        SwingUtilities.invokeLater(() -> addChannel("System"));
    }

    private JComboBox<String> getStringJComboBox() {
        String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        final JComboBox<String> fontComboBox = new JComboBox<>(fonts);

        int selectedIndex = Arrays.asList(fonts).indexOf(config.fontFamily());
        if (selectedIndex < 0) {
            ++selectedIndex;

            font = new Font(fonts[0], Font.PLAIN, config.fontSize());
        }

        fontComboBox.setSelectedIndex(selectedIndex);
        fontComboBox.setPreferredSize(new Dimension(110, 25));
        fontComboBox.addActionListener(e -> {
            if (fontComboBox.getSelectedItem() != null) {
                String selected = fontComboBox.getSelectedItem().toString();

                configManager.setConfiguration("irc", "fontFamily", selected);

                updateFont();
            }
        });

        return fontComboBox;
    }

    private void updateFont() {
        font = new Font(config.fontFamily(), Font.PLAIN, config.fontSize());

        inputField.setFont(font);

        for (ChannelPane channelPane : channelPanes.values()) {
            channelPane.setFont(font);
        }
    }

    @Provides
    IrcConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(IrcConfig.class);
    }

    public void init(BiConsumer<String, String> messageSendCallback,
                     BiConsumer<String, String> channelJoinCallback,
                     Consumer<String> channelLeaveCallback) {
        this.onMessageSend = messageSendCallback;
        this.onChannelJoin = channelJoinCallback;
        this.onChannelLeave = channelLeaveCallback;
    }

    public String getCurrentChannel() {
        int index = tabbedPane.getSelectedIndex();
        return index != -1 ? tabbedPane.getTitleAt(index) : "System";
    }

    public void addChannel(String channel) {
        if (!channelPanes.containsKey(channel)) {
            ChannelPane pane = new ChannelPane(font);
            channelPanes.put(channel, pane);
            tabbedPane.addTab(channel, new JScrollPane(pane));
            tabbedPane.setSelectedIndex(tabbedPane.getTabCount() - 1);
        }
    }

    public void removeChannel(String channel) {
        if (channelPanes.containsKey(channel) && !channel.equals("System")) {
            int index = tabbedPane.indexOfTab(channel);
            if (index != -1) {
                tabbedPane.removeTabAt(index);
                channelPanes.remove(channel);
            }
        }
    }

    public void addMessage(IrcMessage message) {
        ChannelPane pane = channelPanes.get(message.getChannel());
        if (pane == null) {
            addChannel(message.getChannel());
            pane = channelPanes.get(message.getChannel());
        }
        pane.appendMessage(message, config.timestamp());
    }

    private void promptAddChannel() {
        String channel = JOptionPane.showInputDialog(this, "Enter channel name:");
        String password = JOptionPane.showInputDialog(this, "Enter channel password (optional):");

        if (channel != null && !channel.trim().isEmpty()) {
            if (!channel.startsWith("#")) {
                channel = "#" + channel;
            }
            if (onChannelJoin != null) {
                onChannelJoin.accept(channel, password);
            }
        }
    }

    private void promptRemoveChannel() {
        String channel = getCurrentChannel();
        if (!channel.equals("System")) {
            int result = JOptionPane.showConfirmDialog(
                    this,
                    "Leave " + channel + "?",
                    "Confirm",
                    JOptionPane.YES_NO_OPTION
            );
            if (result == JOptionPane.YES_OPTION && onChannelLeave != null) {
                onChannelLeave.accept(channel);
            }
        }
    }

    private static class ChannelPane extends JTextPane {
        private final StringBuilder messageLog;

        ChannelPane(Font font) {
            setContentType("text/html");
            setFont(font);
            setEditable(false);
            messageLog = new StringBuilder();

            addHyperlinkListener(e -> {
                if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                    try {
                        Desktop.getDesktop().browse(e.getURL().toURI());
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            });
        }

        void appendMessage(IrcMessage message, boolean timestamp) {
            String formattedMessage = formatPanelMessage(message, timestamp);
            messageLog.append(formattedMessage);

            SwingUtilities.invokeLater(() -> {
                setText("<html><body style='"
                        + "color: " + ColorUtil.toHexColor(ColorScheme.TEXT_COLOR) + ";"
                        + "'>" + messageLog + "</body></html>");
                setCaretPosition(getDocument().getLength());
            });
        }

        private String formatPanelMessage(IrcMessage message, boolean timestamp) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(ZoneId.systemDefault());
            String timeStamp = "";
            if (timestamp) {
                timeStamp = "[" + formatter.format(message.getTimestamp()) + "] ";
            }

            String color;
            switch (message.getType()) {
                case SYSTEM:
                    color = ColorUtil.toHexColor(ColorScheme.BRAND_ORANGE);
                    break;
                case JOIN:
                    color = ColorUtil.toHexColor(ColorScheme.PROGRESS_INPROGRESS_COLOR);
                    break;
                case PART:
                case QUIT:
                    color = ColorUtil.toHexColor(ColorScheme.PROGRESS_ERROR_COLOR);
                    break;
                case NICK_CHANGE:
                case KICK:
                    color = ColorUtil.toHexColor(ColorScheme.BRAND_ORANGE);
                    break;
                default:
                    color = ColorUtil.toHexColor(ColorScheme.LIGHT_GRAY_COLOR);
            }

            return String.format("<div style='color: %s'>%s%s: %s</div>",
                    color,
                    timeStamp,
                    escapeHtml(message.getSender()),
                    formatMessage(message.getContent())
            );
        }

        private String formatMessage(String message) {
            return convertModernEmojis(
                    formatColorCodes(
                            escapeHtml4(message)
                    )
                    .replaceAll("\\b(https?://\\S+)\\b", "<a href='$1'>$1</a>")
            );
        }

        private String formatColorCodes(String message) {
            message = message
                    .replaceAll("\u001F([^\u001F\u000F]+)[\u001F\u000F]?", "<u>$1</u>")
                    .replaceAll("\u001D([^\u001D\u000F]+)[\u001D\u000F]?", "<i>$1</i>")
                    .replaceAll("\u0002([^\u0002\u000F]+)[\u0002\u000F]?", "<b>$1</b>");

            Pattern p = Pattern.compile("(?:\u0003\\d\\d?(?:,\\d\\d?)?\\s*)?\u0003(\\d\\d?)(?:,\\d\\d?)?([^\u0003\u000F]+)");
            Matcher m = p.matcher(message);

            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, "<font color=\"" + htmlColorById(m.group(1)) + "\">" + m.group(2) + "</font>");
            }
            m.appendTail(sb);

            return sb.toString().replaceAll("\u0002|\u0003(\\d\\d?(,\\d\\d)?)?|\u001D|\u0015|\u000F", "");
        }

        private String htmlColorById(String id) {
            String color = "black";

            switch (id) {
                case "00":
                case "0":
                    color = "white";
                    break;
                case "01":
                case "1":
                    color = "black";
                    break;
                case "02":
                case "2":
                    color = "00008B";
                    break;
                case "03":
                case "3":
                    color = "green";
                    break;
                case "04":
                case "4":
                    color = "red";
                    break;
                case "05":
                case "5":
                    color = "800000";
                    break;
                case "06":
                case "6":
                    color = "purple";
                    break;
                case "07":
                case "7":
                    color = "orange";
                    break;
                case "08":
                case "8":
                    color = "yellow";
                    break;
                case "09":
                case "9":
                    color = "DFFF00";
                    break;
                case "10":
                    color = "008b8b";
                    break;
                case "11":
                    color = "00FFFF";
                    break;
                case "12":
                    color = "blue";
                    break;
                case "13":
                    color = "FFC0CB";
                    break;
                case "14":
                    color = "gray";
                    break;
                case "15":
                    color = "d3d3d3";
                    break;
            }

            return color;
        }

        private String escapeHtml(String text) {
            return text.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;");
        }
    }

    private static final Pattern MODERN_EMOJI_PATTERN = Pattern.compile(
            "[" +

                    "\uD83E\uDD70-\uD83E\uDDFF" + // Unicode 10.0
                    "\uD83E\uDE00-\uD83E\uDEFF" + // Unicode 11.0
                    "\uD83E\uDF00-\uD83E\uDFFF" + // Unicode 12.0+
                    "\uD83E\uDD00-\uD83E\uDD6F" + // Unicode 13.0
                    "\uD83E\uDEC0-\uD83E\uDECF" +
                    "\uD83E\uDED0-\uD83E\uDEFF" + // Unicode 14.0
                    "\uD83E\uDF00-\uD83E\uDF2F" + // Unicode 15.0
                    "\uD83E\uDF30-\uD83E\uDF5F" + // Unicode 16.0
                    "\uD83E\uDF60-\uD83E\uDF8F" + // Unicode 17.0
                    "\uD83C[\uDFFB-\uDFFF]" +     // Skin tone modifiers
                    "\uD83E[\uDDB0-\uDDBF]" +     // Hair style modifiers
                    "\uFE0F" +                   // Variation selector
                "]"
    );

    public static String convertModernEmojis(String text) {
        StringBuilder result = new StringBuilder();
        Matcher matcher = MODERN_EMOJI_PATTERN.matcher(text);

        while (matcher.find()) {
            String modernEmoji = matcher.group();
            String replacement = EmojiParser.parseToAliases(modernEmoji, EmojiParser.FitzpatrickAction.PARSE);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }
}