package com.irc;

import com.google.inject.Provides;
import com.irc.emoji.EmojiParser;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.PluginPanel;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.Element;
import javax.swing.text.Position;

import net.runelite.client.util.ImageUtil;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.LinkBrowser;

import static org.apache.commons.text.StringEscapeUtils.escapeHtml4;

@Slf4j
public class IrcPanel extends PluginPanel {
    @Inject
    private IrcConfig config;
    @Inject
    private ConfigManager configManager;

    private JTabbedPane tabbedPane;
    private JTextField inputField;
    @Getter
    private Map<String, ChannelPane> channelPanes;
    @Getter
    private NavigationButton navigationButton;

    private BiConsumer<String, String> onMessageSend;
    private BiConsumer<String, String> onChannelJoin;
    private Consumer<String> onChannelLeave;
    private Font font;

    private Map<String, Boolean> unreadMessages = new LinkedHashMap<>();
    private Timer flashTimer;
    private String focusedChannel;
    private static final String SYSTEM_TAB = "System";

    private void initializeFlashTimer() {
        flashTimer = new Timer(500, e -> {
            String currentTab = getCurrentChannel();
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                String tabTitle = tabbedPane.getTitleAt(i);
                if (!SYSTEM_TAB.equals(tabTitle) &&
                    unreadMessages.getOrDefault(tabTitle, false) &&
                    !tabTitle.equals(currentTab)) {
                        tabbedPane.setForegroundAt(i, new Color(135, 206, 250)); // Change color for different flash
                } else if (!SYSTEM_TAB.equals(tabTitle) &&
                        !unreadMessages.getOrDefault(tabTitle, false)) {
                    tabbedPane.setForegroundAt(i, Color.white);
                }
            }
        });
        flashTimer.start();
    }

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

        setupShortcuts();

        inputField.addActionListener(e -> {
            String message = inputField.getText();
            if (!message.isEmpty() && onMessageSend != null) {
                onMessageSend.accept(getCurrentChannel(), message);
                inputField.setText("");
            }
        });

        add(controlPanel, BorderLayout.NORTH);
        add(tabbedPane, BorderLayout.CENTER);
        add(inputField, BorderLayout.SOUTH);

        navigationButton = generateNavigationButton();

        SwingUtilities.invokeLater(() -> addChannel("System"));

        tabbedPane.addChangeListener(e -> {
            String newChannel = getCurrentChannel();
            if (unreadMessages.containsKey(newChannel)) {
                unreadMessages.put(newChannel, false);
                tabbedPane.setBackgroundAt(tabbedPane.getSelectedIndex(), null);
                tabbedPane.setForegroundAt(tabbedPane.getSelectedIndex(), null);
            }
            this.setFocusedChannel(newChannel);
        });

        initializeFlashTimer();
    }

    public NavigationButton generateNavigationButton() {
        navigationButton = NavigationButton.builder()
                .tooltip("IRC")
                .icon(ImageUtil.loadImageResource(getClass(), "icon.png"))
                .priority(config.getPanelPriority())
                .panel(this)
                .build();

        return navigationButton;
    }

    public void setFocusedChannel(String channel) {
        this.focusedChannel = channel;

        tabbedPane.setSelectedIndex(tabbedPane.indexOfTab(channel));
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

    public void clearCurrentPane() {
        int index = tabbedPane.getSelectedIndex();
        String channel = index != -1 ? tabbedPane.getTitleAt(index) : "System";
        ChannelPane pane = channelPanes.get(channel);
        if (pane != null) {
            pane.clear();
        }
    }

    public boolean isPane(String name) {
        return tabbedPane.indexOfTab(name) != -1;
    }

    public void addChannel(String channel) {
        if (!channelPanes.containsKey(channel)) {
            ChannelPane pane = new ChannelPane(font, config);
            channelPanes.put(channel, pane);
            unreadMessages.put(channel, false);
            tabbedPane.addTab(channel, new JScrollPane(pane));
            if (config.autofocusOnNewTab() || channel.equals(config.channel())) {
                tabbedPane.setSelectedIndex(tabbedPane.getTabCount() - 1);
            }
        }
    }

    public void removeChannel(String channel) {
        if (channelPanes.containsKey(channel) && !channel.equals("System")) {
            int index = tabbedPane.indexOfTab(channel);
            if (index != -1) {
                tabbedPane.removeTabAt(index);
                channelPanes.remove(channel);
                unreadMessages.remove(channel);
            }
        }
    }

    public void addMessage(IrcMessage message) {
        ChannelPane pane = channelPanes.get(message.getChannel());
        if (pane == null) {
            addChannel(message.getChannel());
            pane = channelPanes.get(message.getChannel());
        }
        if (!message.getChannel().equals(focusedChannel)) {
            unreadMessages.put(message.getChannel(), true);
        }
        pane.appendMessage(message, config);
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
                    "Close " + channel + "?",
                    "Confirm",
                    JOptionPane.YES_NO_OPTION
            );
            if (result == JOptionPane.YES_OPTION && onChannelLeave != null) {
                onChannelLeave.accept(channel);
            }
        }
    }

    private static class ChannelPane extends JTextPane {
        private ArrayList<String> messageLog;
        private static final Pattern IMAGE_URL_PATTERN = Pattern.compile("\\.(png|jpe?g|gif|bmp|webp)(\\?.*)?$", Pattern.CASE_INSENSITIVE);
        private static final int MAX_PREVIEW_WIDTH = 500;
        private static final int MAX_PREVIEW_HEIGHT = 500;
        private Popup currentImagePreview;

        ChannelPane(Font font, IrcConfig config) {
            setContentType("text/html");
            setFont(font);
            setEditable(false);
            messageLog = new ArrayList<>();

            addHyperlinkListener(e -> {
                if (e.getURL() != null) {
                    String url = e.getURL().toString();

                    if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                        try {
                            LinkBrowser.browse(e.getURL().toURI().toString());
                        } catch (Exception ignored) {
                        }
                    } else if (e.getEventType() == HyperlinkEvent.EventType.ENTERED) {
                        if (config.hoverPreviewImages() && isImageUrl(url)) {
                            showImagePreview(e.getSourceElement(), url);
                        }
                    } else if (e.getEventType() == HyperlinkEvent.EventType.EXITED) {
                        hideImagePreview();
                    }
                }
            });
        }

        private boolean isImageUrl(String url) {
            boolean matches = IMAGE_URL_PATTERN.matcher(url).find();
            return matches;
        }

        private void showImagePreview(Object source, String imageUrl) {
            hideImagePreview();

            CompletableFuture.runAsync(() -> {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL(imageUrl);
                    connection = (HttpURLConnection) url.openConnection();

                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                    connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
                    connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
                    connection.setRequestProperty("Sec-Fetch-Dest", "image");
                    connection.setRequestProperty("Sec-Fetch-Mode", "no-cors");
                    connection.setRequestProperty("Sec-Fetch-Site", "cross-site");

                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000);

                    // Follow redirects
                    connection.setInstanceFollowRedirects(true);

                    BufferedImage originalImage = ImageIO.read(connection.getInputStream());
                    if (originalImage == null) {
                        return;
                    }

                    // Scale image while maintaining aspect ratio
                    double scale = Math.min(
                            (double) MAX_PREVIEW_WIDTH / originalImage.getWidth(),
                            (double) MAX_PREVIEW_HEIGHT / originalImage.getHeight()
                    );

                    int scaledWidth = (int) (originalImage.getWidth() * scale);
                    int scaledHeight = (int) (originalImage.getHeight() * scale);

                    BufferedImage scaledImage = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g2d = scaledImage.createGraphics();
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2d.drawImage(originalImage, 0, 0, scaledWidth, scaledHeight, null);
                    g2d.dispose();

                    SwingUtilities.invokeLater(() -> {
                        try {
                            if (source instanceof Element) {
                                hideImagePreview();

                                Rectangle bounds = getElementBounds((Element) source);

                                Point location = new Point(
                                        bounds.x,
                                        bounds.y + bounds.height
                                );
                                SwingUtilities.convertPointToScreen(location, ChannelPane.this);

                                JLabel imageLabel = new JLabel(new ImageIcon(scaledImage));
                                imageLabel.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
                                imageLabel.setBackground(new Color(32, 32, 32));
                                imageLabel.setOpaque(true);

                                currentImagePreview = PopupFactory.getSharedInstance().getPopup(
                                        ChannelPane.this,
                                        imageLabel,
                                        location.x,
                                        location.y
                                );
                                currentImagePreview.show();
                            }
                        } catch (Exception ignored) {
                        }
                    });
                } catch (Exception ignored) {
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            });
        }

        private Rectangle getElementBounds(Element element) {
            try {
                Rectangle result = getUI().modelToView2D(this, element. getStartOffset(), Position.Bias.Forward).getBounds();
                Rectangle endRect = getUI().modelToView2D(this, element.getEndOffset(), Position.Bias.Backward).getBounds();
                result.add(endRect);
                return result;
            } catch (Exception ex) {
                return new Rectangle(0, 0, 0, 0);
            }
        }

        private void hideImagePreview() {
            if (currentImagePreview != null) {
                currentImagePreview.hide();
                currentImagePreview = null;
            }
        }

        void appendMessage(IrcMessage message, IrcConfig config) {
            String formattedMessage = formatPanelMessage(message, config);
            messageLog.add(formattedMessage);

            if (messageLog.size() > config.getMaxScrollback()) {
                messageLog.remove(0);
            }

            SwingUtilities.invokeLater(() -> {
                setText("<html><body style='color:" + ColorUtil.toHexColor(ColorScheme.TEXT_COLOR) + ";'>"
                        + String.join("\n", messageLog) + "</body></html>");
                setCaretPosition(getDocument().getLength());
            });
        }

        private String formatPanelMessage(IrcMessage message, IrcConfig config) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(ZoneId.systemDefault());
            String timeStamp = "";
            if (config.timestamp()) {
                timeStamp = "[" + formatter.format(message.getTimestamp()) + "] ";
            }

            String color;
            switch (message.getType()) {
                case SYSTEM:
                case NICK_CHANGE:
                case KICK:
                case MODE:
                    color = ColorUtil.toHexColor(ColorScheme.BRAND_ORANGE);
                    break;
                case JOIN:
                    color = ColorUtil.toHexColor(ColorScheme.PROGRESS_INPROGRESS_COLOR);
                    break;
                case PART:
                case QUIT:
                    color = ColorUtil.toHexColor(ColorScheme.PROGRESS_ERROR_COLOR);
                    break;
                case TOPIC:
                    color = ColorUtil.toHexColor(ColorScheme.TEXT_COLOR);
                    break;
                default:
                    color = ColorUtil.toHexColor(ColorScheme.LIGHT_GRAY_COLOR);
            }

            String sender = escapeHtml4(message.getSender());

            if (config.colorizedNicks()) {
                String[] viableColorIds = new String[]{"02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13", "14", "15"};
                String colorId = viableColorIds[asciiSum(sender) % viableColorIds.length];
                String senderColor = htmlColorById(colorId);

                sender = String.format("<font style=\"color:%s\">%s</font>", senderColor, sender);
            }

            return String.format("<div style='color: %s'>%s%s:&nbsp;%s</div>",
                    color,
                    timeStamp,
                    sender,
                    formatMessage(message.getContent())
            );
        }

        private int asciiSum(String input) {
            int sum = 0;

            for (int i = 0; i < input.length(); i++) {
                sum += input.charAt(i);
            }

            return sum;
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

            Pattern p = Pattern.compile("(?:\u0003\\d\\d?(?:,\\d\\d?)?\\s*)?\u000F?\u0003(\\d\\d?)(?:,\\d\\d?)?([^\u0003\u000F]+)\u000F?");
            Matcher m = p.matcher(message);

            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, "<font color=\"" + htmlColorById(m.group(1)) + "\">" + Matcher.quoteReplacement(m.group(2)) + "</font>");
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

        public void clear() {
            this.setText("");
            messageLog = new ArrayList<>();
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

    private enum IrcShortcut {
        COLOR(KeyStroke.getKeyStroke(KeyEvent.VK_K, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                "\u0003",
                "insertColorCode",
                "Insert color code"),

        BOLD(KeyStroke.getKeyStroke(KeyEvent.VK_B, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                "\u0002",
                "insertBold",
                "Insert bold formatting"),

        ITALIC(KeyStroke.getKeyStroke(KeyEvent.VK_I, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                "\u001D",
                "insertItalic",
                "Insert italic formatting"),

        UNDERLINE(KeyStroke.getKeyStroke(KeyEvent.VK_U, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()),
                "\u001F",
                "insertUnderline",
                "Insert underline formatting");

        private final KeyStroke keyStroke;
        private final String insertText;
        private final String actionKey;
        private final String description;

        IrcShortcut(KeyStroke keyStroke, String insertText, String actionKey, String description) {
            this.keyStroke = keyStroke;
            this.insertText = insertText;
            this.actionKey = actionKey;
            this.description = description;
        }
    }

    private class TextInsertAction extends AbstractAction {
        private final String textToInsert;

        TextInsertAction(String textToInsert) {
            this.textToInsert = textToInsert;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            String currentText = inputField.getText();
            int caretPosition = inputField.getCaretPosition();

            String newText;
            int newCaretPosition;

            if (inputField.getSelectedText() != null) {
                int selStart = inputField.getSelectionStart();
                int selEnd = inputField.getSelectionEnd();
                String selectedText = inputField.getSelectedText();

                newText = currentText.substring(0, selStart)
                        + textToInsert
                        + selectedText
                        + textToInsert
                        + currentText.substring(selEnd);

                newCaretPosition = selEnd + (textToInsert.length() * 2);
            } else {
                newText = currentText.substring(0, caretPosition)
                        + textToInsert
                        + currentText.substring(caretPosition);

                newCaretPosition = caretPosition + textToInsert.length();
            }

            inputField.setText(newText);
            inputField.setCaretPosition(newCaretPosition);
        }
    }

    private void setupShortcuts() {
        InputMap inputMap = inputField.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = inputField.getActionMap();

        for (IrcShortcut shortcut : IrcShortcut.values()) {
            inputMap.put(shortcut.keyStroke, shortcut.actionKey);
            actionMap.put(shortcut.actionKey, new TextInsertAction(shortcut.insertText));
        }
    }
}