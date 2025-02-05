package com.irc;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import net.runelite.client.util.ImageUtil;

import java.awt.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.runelite.client.util.ColorUtil;

public class IrcPanel extends PluginPanel {

    private JTabbedPane tabbedPane;
    private JTextField inputField;
    private Map<String, ChannelPane> channelPanes;
    private NavigationButton navigationButton;

    private BiConsumer<String, String> onMessageSend;
    private Consumer<String> onChannelJoin;
    private Consumer<String> onChannelLeave;

    public void initializeGui() {
        setLayout(new BorderLayout());

        tabbedPane = new JTabbedPane();
        tabbedPane.setPreferredSize(new Dimension(300, 425));
        inputField = new JTextField();
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

    public void init(BiConsumer<String, String> messageSendCallback,
                     Consumer<String> channelJoinCallback,
                     Consumer<String> channelLeaveCallback) {
        this.onMessageSend = messageSendCallback;
        this.onChannelJoin = channelJoinCallback;
        this.onChannelLeave = channelLeaveCallback;
    }

    public NavigationButton getNavigationButton() {
        return navigationButton;
    }

    public String getCurrentChannel() {
        int index = tabbedPane.getSelectedIndex();
        return index != -1 ? tabbedPane.getTitleAt(index) : "System";
    }

    public void addChannel(String channel) {
        if (!channelPanes.containsKey(channel)) {
            ChannelPane pane = new ChannelPane();
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
        if (channel != null && !channel.trim().isEmpty()) {
            if (!channel.startsWith("#")) {
                channel = "#" + channel;
            }
            if (onChannelJoin != null) {
                onChannelJoin.accept(channel);
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

        ChannelPane() {
            setContentType("text/html");
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
            String formattedMessage = formatMessage(message);
            messageLog.append(formattedMessage);

            SwingUtilities.invokeLater(() -> {
                setText("<html><body style='"
                        + "color: " + ColorUtil.toHexColor(ColorScheme.TEXT_COLOR) + ";"
                        + "'>" + messageLog + "</body></html>");
                setCaretPosition(getDocument().getLength());
            });
        }

        private String formatMessage(IrcMessage message) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(ZoneId.systemDefault());
            String timeStamp = "[" + formatter.format(message.getTimestamp()) + "] ";

            String color;
            switch (message.getType()) {
                case SYSTEM:
                    color = ColorUtil.toHexColor(ColorScheme.MEDIUM_GRAY_COLOR);
                    break;
                case JOIN:
                    color = ColorUtil.toHexColor(ColorScheme.PROGRESS_INPROGRESS_COLOR);
                    break;
                case PART:
                case QUIT:
                    color = ColorUtil.toHexColor(ColorScheme.PROGRESS_ERROR_COLOR);
                    break;
                default:
                    color = ColorUtil.toHexColor(ColorScheme.LIGHT_GRAY_COLOR);
            }

            return String.format("<div style='color: %s'>%s%s: %s</div>",
                    color,
                    timeStamp,
                    escapeHtml(message.getSender()),
                    convertToHtml(message.getContent())
            );
        }

        private String convertToHtml(String text) {
            return escapeHtml(text)
                    .replaceAll("\\b(https?://\\S+)\\b", "<a href='$1'>$1</a>")
                    .replaceAll("\u0002([^\u0002]+)\u0002", "<b>$1</b>")
                    .replaceAll("\u001F([^\u001F]+)\u001F", "<u>$1</u>")
                    .replaceAll("\u001D([^\u001D]+)\u001D", "<i>$1</i>");
        }

        private String escapeHtml(String text) {
            return text.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
        }
    }
}