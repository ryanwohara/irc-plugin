package com.irc;

import com.vdurmont.emoji.EmojiParser;
import lombok.Getter;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import net.runelite.client.util.ImageUtil;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.runelite.client.util.ColorUtil;

import static org.apache.commons.text.StringEscapeUtils.escapeHtml4;

public class IrcPanel extends PluginPanel {

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
        font = loadCustomFont(12.0f);

        tabbedPane = new JTabbedPane();
        tabbedPane.setPreferredSize(new Dimension(300, 425));
        inputField = new JTextField();
        inputField.setFont(new Font("Ubuntu Sans Mono", Font.PLAIN, 12));
        channelPanes = new LinkedHashMap<>();

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));

        JButton addButton = new JButton("+");
        JButton removeButton = new JButton("-");
        addButton.setPreferredSize(new Dimension(45, 25));
        removeButton.setPreferredSize(new Dimension(45, 25));

        addButton.addActionListener(e -> promptAddChannel());
        removeButton.addActionListener(e -> promptRemoveChannel());

        controlPanel.add(addButton);
        controlPanel.add(removeButton);

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

        inputField.addActionListener(e -> {
            String message = inputField.getText().trim();
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

    private Font loadCustomFont(float size) {
        String fonts[] = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        for (int i = 0; i < fonts.length; i++) {
            System.out.println(fonts[i]);
        }
        return UIManager.getFont("Ubuntu Sans Mono");
//        try (InputStream is = getClass().getResourceAsStream("NotoSans-Regular.ttf")) {
//            assert is != null;
//            Font baseFont = Font.createFont(Font.TRUETYPE_FONT, is);
//
//            return baseFont.deriveFont(Font.PLAIN, size);
//        } catch (Exception e) {
//            e.printStackTrace();
//            System.out.println("Failed to load custom font.");
//            // Fallback to a system font if loading fails
//            return UIManager.getFont("Label.font");
//        }

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
        pane.appendMessage(message);
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
            setFont(new Font("Ubuntu Sans Mono", Font.PLAIN, 12));
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

        void appendMessage(IrcMessage message) {
            String formattedMessage = formatPanelMessage(message);
            messageLog.append(formattedMessage);

            SwingUtilities.invokeLater(() -> {
                setText("<html><body style='"
                        + "color: " + ColorUtil.toHexColor(ColorScheme.TEXT_COLOR) + ";"
                        + "'>" + messageLog + "</body></html>");
                setCaretPosition(getDocument().getLength());
            });
        }

        private String formatPanelMessage(IrcMessage message) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(ZoneId.systemDefault());
            String timeStamp = "[" + formatter.format(message.getTimestamp()) + "] ";

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
                    escapeHtml4(message)
                    .replaceAll("\\b(https?://\\S+)\\b", "<a href='$1'>$1</a>")
                    .replaceAll("\u0002([^\u0002\u000F]+)[\u0002\u000F]?", "<b>$1</b>")
                    .replaceAll("\u001F([^\u001F\u000F]+)[\u001F\u000F]?", "<u>$1</u>")
                    .replaceAll("\u001D([^\u001D\u000F]+)[\u001D\u000F]?", "<i>$1</i>")
                    .replaceAll("\u000310(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"darkcyan\">$1</font>")
                    .replaceAll("\u000311(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"cyan\">$1</font>")
                    .replaceAll("\u000312(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"blue\">$1</font>")
                    .replaceAll("\u000313(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"pink\">$1</font>")
                    .replaceAll("\u000314(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"grey\">$1</font>")
                    .replaceAll("\u000315(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"lightgrey`\">$1</font>")
                    .replaceAll("\u00030?1(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"black\">$1</font>")
                    .replaceAll("\u00030?2(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"darkblue\">$1</font>")
                    .replaceAll("\u00030?3(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"green\">$1</font>")
                    .replaceAll("\u00030?4(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"red\">$1</font>")
                    .replaceAll("\u00030?5(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"brown\">$1</font>")
                    .replaceAll("\u00030?6(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"purple\">$1</font>")
                    .replaceAll("\u00030?7(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"orange\">$1</font>")
                    .replaceAll("\u00030?8(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"yellow\">$1</font>")
                    .replaceAll("\u00030?9(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"chartreuse\">$1</font>")
                    .replaceAll("\u000300?(?:,\\d\\d?)?([^\u0003\u000F]+)", "<font color=\"white\">$1</font>")
            );
        }

        private String escapeHtml(String text) {
            return text.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
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
        StringBuffer result = new StringBuffer();
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