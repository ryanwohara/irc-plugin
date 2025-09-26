package com.irc;

import com.google.inject.Provides;
import com.irc.emoji.EmojiParser;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.ui.ClientUI;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.HyperlinkEvent;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.concurrent.TimeUnit;

import static org.apache.commons.text.StringEscapeUtils.escapeHtml4;

@Slf4j
public class IrcPanel extends PluginPanel {
    @Inject
    private IrcConfig config;
    @Inject
    private ConfigManager configManager;
    @Inject
    private ClientUI clientUI;

    private JTabbedPane tabbedPane;
    public JTextField inputField;
    @Getter
    private Map<String, ChannelPane> channelPanes;
    @Getter
    private NavigationButton navigationButton;

    private BiConsumer<String, String> onMessageSend;
    private BiConsumer<String, String> onChannelJoin;
    private Consumer<String> onChannelLeave;
    private Consumer<Boolean> onReconnect;
    private Font font;

    public final Map<String, Boolean> unreadMessages = new LinkedHashMap<>();
    private String focusedChannel;
    private static final String SYSTEM_TAB = "System";

    private final JComboBox<String> bufferDropdown = getBufferComboBox();
    private final JTextPane displayPane = new JTextPane();

    public ArrayList<String> getChannelNames() {
        return new ArrayList<>(channelPanes.keySet());
    }

    public static final Pattern VALID_LINK = Pattern.compile("(https?://([\\w-]+\\.)+[\\w-]+([\\w-;:,./?%&=]*))");


    private void initializeFlashTimer() {
        // Change color for different flash
        Timer flashTimer = new Timer(500, e -> {
            String currentTab = getCurrentChannel();
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                String tabTitle = tabbedPane.getTitleAt(i);
                if (!SYSTEM_TAB.equals(tabTitle) && unreadMessages.getOrDefault(tabTitle, false) && !tabTitle.equals(currentTab)) {
                    tabbedPane.setForegroundAt(i, new Color(135, 206, 250)); // Change color for different flash
                } else if (!SYSTEM_TAB.equals(tabTitle) && !unreadMessages.getOrDefault(tabTitle, false)) {
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
        tabbedPane.setPreferredSize(new Dimension(300, 400));
        tabbedPane.setUI(new BasicTabbedPaneUI() {
            @Override
            protected int calculateTabAreaHeight(int tabPlacement, int runCount, int maxTabHeight) {
                return 0;
            }
        });
        inputField = new JTextField();
        inputField.setFont(font);
        channelPanes = new LinkedHashMap<>();

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentHidden(ComponentEvent e) {
                hideAllPreviews();
            }
        });

        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton addButton = new JButton("+");
        JButton removeButton = new JButton("-");
        JButton reloadButton = new JButton();
        try {
            Image img = ImageUtil.loadImageResource(getClass(), "reload.png");
            reloadButton.setIcon(new ImageIcon(img));
        } catch (Exception ignored) {
            reloadButton.setText("R");
        }
        Dimension standard = new Dimension(25, 25);
        addButton.setPreferredSize(standard);
        removeButton.setPreferredSize(standard);
        reloadButton.setPreferredSize(standard);
        final JComboBox<String> fontComboBox = getFontComboBox();


        JFrame frame = new JFrame("Buffers");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(500, 300);

        displayPane.setEditable(false);

        int index = 0;
        for (Map.Entry<String, ChannelPane> channel : channelPanes.entrySet()) {
            if (index == 0) {
                displayPane.setDocument(channel.getValue().getStyledDocument()); // show first one
            }
            index++;
        }
        bufferDropdown.addActionListener(this::actionPerformed);

        frame.setLayout(new BorderLayout());
        frame.add(bufferDropdown, BorderLayout.NORTH);
        frame.add(new JScrollPane(displayPane), BorderLayout.CENTER);

        addButton.addActionListener(e -> promptAddChannel());
        removeButton.addActionListener(e -> promptRemoveChannel());
        reloadButton.addActionListener(e -> onReconnect.accept(true));
        row1.add(reloadButton);
        row1.add(addButton);
        row1.add(removeButton);
        row1.add(fontComboBox);
        row2.add(bufferDropdown);
        controlPanel.add(row1);
        controlPanel.add(row2);
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
            if (newChannel != null && unreadMessages.containsKey(newChannel)) {
                unreadMessages.put(newChannel, false);
                int selectedIndex = tabbedPane.getSelectedIndex();
                if (selectedIndex != -1) {
                    tabbedPane.setForegroundAt(selectedIndex, Color.WHITE);
                }
            }
        });
        initializeFlashTimer();
    }

    public void cycleChannel() {
        List<String> channels = this.getChannelNames();
        if (channels.isEmpty()) return;

        String current = this.getCurrentChannel();
        int index = channels.indexOf(current);
        index = (index + 1) % channels.size();
        this.setFocusedChannel(channels.get(index));
    }

    public void cycleChannelBackwards() {
        List<String> channels = this.getChannelNames();
        if (channels.isEmpty()) return;

        String current = this.getCurrentChannel();
        int index = channels.indexOf(current);
        index = (index - 1 < 0 ? channels.size() - 1 : (index - 1) % channels.size());
        this.setFocusedChannel(channels.get(index));
    }

    private JComboBox<String> getFontComboBox() {
        final JComboBox<String> fontComboBox = getStringFontComboBox();
        fontComboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                Font font = new Font(value.toString(), Font.PLAIN, config.fontSize());
                label.setFont(font);
                return label;
            }
        });

        bufferDropdown.setBackground(Color.DARK_GRAY);
        bufferDropdown.setForeground(Color.WHITE);

        return fontComboBox;
    }

    private JComboBox<String> getBufferComboBox() {
        final JComboBox<String> bufferComboBox = new JComboBox<>();

        bufferComboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

                if (unreadMessages.getOrDefault(value.toString(), false)) {
                    label.setForeground(new Color(135, 206, 250)); // Change color for different flash
                } else {
                    label.setForeground(Color.white);
                }

                return label;
            }
        });

        bufferComboBox.addMouseWheelListener(e -> {
            if (e.getScrollType() == MouseWheelEvent.WHEEL_UNIT_SCROLL) {
                int direction = e.getWheelRotation(); // +1 down, -1 up
                int index = bufferComboBox.getSelectedIndex();

                if (direction > 0 && index < bufferComboBox.getItemCount() - 1) {
                    this.cycleChannel();
                } else if (direction < 0 && index > 0) {
                    this.cycleChannelBackwards();
                }
            }
        });

        return bufferComboBox;
    }

    public void hideAllPreviews() {
        if (channelPanes != null) {
            for (ChannelPane pane : channelPanes.values()) {
                pane.cancelPreviewManager();
            }
        }
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
        if (channel != null && unreadMessages.containsKey(channel)) {
            int i = 0;
            int index = 0;
            for (Map.Entry<String, ChannelPane> entry : channelPanes.entrySet()) {
                if (entry.getKey().equals(channel)) {
                    index = i;
                    break;
                }
                i++;
            }

            unreadMessages.put(channel, false);
            tabbedPane.setForegroundAt(index, Color.WHITE);
            tabbedPane.setSelectedIndex(index);
            bufferDropdown.setSelectedIndex(index);

            this.focusedChannel = channel;
        }
    }

    private JComboBox<String> getStringFontComboBox() {
        String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        final JComboBox<String> fontComboBox = new JComboBox<>(fonts);
        int selectedIndex = Arrays.asList(fonts).indexOf(config.fontFamily());
        if (selectedIndex < 0) {
            selectedIndex = 0;
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

    public void init(BiConsumer<String, String> messageSendCallback, BiConsumer<String, String> channelJoinCallback, Consumer<String> channelLeaveCallback, Consumer<Boolean> onReconnect) {
        this.onMessageSend = messageSendCallback;
        this.onChannelJoin = channelJoinCallback;
        this.onChannelLeave = channelLeaveCallback;
        this.onReconnect = onReconnect;
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
        if (channelPanes.containsKey(channel)) return;
        ChannelPane pane = new ChannelPane(font, config);
        bufferDropdown.addItem(channel);

        JScrollPane scrollPane = new JScrollPane(pane);
        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> pane.cancelPreviewManager());

        channelPanes.put(channel, pane);
        unreadMessages.put(channel, false);
        tabbedPane.addTab(channel, new JScrollPane(pane));
        if (config.autofocusOnNewTab() || channel.equals(config.channel()) || channelPanes.size() == 2) {
            tabbedPane.setSelectedIndex(tabbedPane.getTabCount() - 1);
            this.setFocusedChannel(channel);
        }
    }

    public void removeChannel(String channel) {
        if (!channelPanes.containsKey(channel) || channel.equals("System")) return;
        int index = tabbedPane.indexOfTab(channel);
        if (index == -1) return;
        tabbedPane.removeTabAt(index);
        channelPanes.remove(channel);
        unreadMessages.remove(channel);
        bufferDropdown.removeItem(channel);
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
        if (channel == null || channel.trim().isEmpty()) return;
        String password = JOptionPane.showInputDialog(this, "Enter channel password (optional):");
        if (!channel.startsWith("#")) {
            channel = "#" + channel;
        }
        if (onChannelJoin != null) {
            onChannelJoin.accept(channel, password);
        }
    }

    public void renameChannel(String oldName, String newName) {
        if (!channelPanes.containsKey(oldName) || channelPanes.containsKey(newName)) {
            return;
        }
        int index = tabbedPane.indexOfTab(oldName);
        if (index == -1) {
            return;
        }
        ChannelPane pane = channelPanes.remove(oldName);
        Boolean unread = unreadMessages.remove(oldName);
        channelPanes.put(newName, pane);
        unreadMessages.put(newName, unread != null && unread);
        tabbedPane.setTitleAt(index, newName);
        if (oldName.equals(focusedChannel)) {
            focusedChannel = newName;
        }
    }

    private void promptRemoveChannel() {
        String channel = getCurrentChannel();
        if (!channel.equals("System")) {
            int result = JOptionPane.showConfirmDialog(this, "Close " + channel + "?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (result == JOptionPane.YES_OPTION && onChannelLeave != null) {
                onChannelLeave.accept(channel);
            }
        }
    }

    private void actionPerformed(ActionEvent e) {
        int idx = bufferDropdown.getSelectedIndex();
        int i = 0;
        for (Map.Entry<String, ChannelPane> channel : channelPanes.entrySet()) {
            if (i == idx) {
                displayPane.setDocument(channel.getValue().getStyledDocument());
                this.setFocusedChannel(channel.getKey());
                break;
            }

            i++;
        }
        hideAllPreviews();
    }


    public static class ChannelPane extends JTextPane {
        private final IrcConfig config;
        private ArrayList<String> messageLog;
        private static final Pattern IMAGE_URL_PATTERN = Pattern.compile("\\.(png|jpe?g|bmp)(\\?.*)?$", Pattern.CASE_INSENSITIVE);
        private static final Pattern UNDERLINE = Pattern.compile("\u001F([^\u001F\u000F]+)[\u001F\u000F]?");
        private static final Pattern ITALIC = Pattern.compile("\u001D([^\u001D\u000F]+)[\u001D\u000F]?");
        private static final Pattern BOLD = Pattern.compile("\u0002([^\u0002\u000F]+)[\u0002\u000F]?");
        private static final Pattern COLORS = Pattern.compile("(?:\u0003\\d\\d?(?:,\\d\\d?)?\\s*)?\u000F?\u0003(\\d\\d?)(?:,\\d\\d?)?([^\u0003\u000F]+)\u000F?");
        private static final Pattern STRIP_CODES = Pattern.compile("\u0002|\u0003(\\d\\d?(?:,\\d\\d)?)?|\u001D|\u0015|\u000F");
        private static final int MAX_PREVIEW_WIDTH = 500;
        private static final int MAX_PREVIEW_HEIGHT = 500;
        private Popup currentImagePreview;
        private CompletableFuture<?> imagePreviewFuture;
        private final Cache<String, byte[]> imageCache;
        private final PreviewManager previewManager;

        ChannelPane(Font font, IrcConfig config) {
            this.config = config;
            this.previewManager = new PreviewManager(this);
            setContentType("text/html");
            setFont(font);
            setEditable(false);
            messageLog = new ArrayList<>();

            this.imageCache = CacheBuilder.newBuilder()
                    .maximumSize(50)
                    .expireAfterWrite(5, TimeUnit.MINUTES)
                    .build();

            addHyperlinkListener(e -> {
                if (e.getURL() != null) {
                    String url = e.getURL().toString();
                    if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                        try {
                            LinkBrowser.browse(e.getURL().toURI().toString());
                        } catch (Exception ignored) {
                        }
                    } else if (e.getEventType() == HyperlinkEvent.EventType.ENTERED) {
                        if (this.config.hoverPreviewImages() && isImageUrl(url)) {
                            MouseEvent mouseEvent = (e.getInputEvent() instanceof MouseEvent)
                                    ? (MouseEvent) e.getInputEvent()
                                    : null;

                            if (mouseEvent != null) {
                                previewManager.requestShow(mouseEvent.getPoint(), url);
                            }
                        }
                    } else if (e.getEventType() == HyperlinkEvent.EventType.EXITED) {
                        previewManager.cancelPreview();
                    }
                }
            });
        }

        private boolean isImageUrl(String url) {
            return IMAGE_URL_PATTERN.matcher(url).find();
        }

        public void showImagePreview(Point mousePoint, String imageUrl) {
            cancelPreviewManager();
            if (imagePreviewFuture != null && !imagePreviewFuture.isDone()) {
                imagePreviewFuture.cancel(true);
            }

            imagePreviewFuture = CompletableFuture.runAsync(() -> {
                try {
                    handleStaticImagePreview(mousePoint, imageUrl);
                } catch (Exception e) {
                    log.warn("Failed to create image preview for {}", imageUrl, e);
                }
            });
        }

        private void handleStaticImagePreview(Point mousePoint, String imageUrl) throws IOException {
            byte[] imageBytes = imageCache.getIfPresent(imageUrl);

            if (imageBytes == null || imageBytes.length == 0) {
                log.debug("Cache miss for {}, fetching from network.", imageUrl);

                String encodedUrl = URLEncoder.encode(imageUrl, StandardCharsets.UTF_8);
                HttpURLConnection conn = getHttpURLConnection(imageUrl, encodedUrl);

                int status = conn.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK) {
                    log.warn("Failed to fetch image: {} returned status {}", imageUrl, status);
                    return;
                }

                String contentType = conn.getContentType();
                if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
                    log.warn("Invalid content-type for {}: {}", imageUrl, contentType);
                    return;
                }

                try (InputStream in = conn.getInputStream();
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        baos.write(buffer, 0, n);
                    }
                    imageBytes = baos.toByteArray();
                    imageCache.put(imageUrl, imageBytes);
                } finally {
                    conn.disconnect();
                }
            } else {
                log.debug("Cache hit for {}", imageUrl);
            }

            if (imageBytes.length > 0) {
                BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(imageBytes));
                if (originalImage == null) {
                    log.warn("Could not decode image from URL: {}", imageUrl);
                    imageCache.invalidate(imageUrl);
                    return;
                }

                ImageIcon imageIcon = new ImageIcon(scaleImage(originalImage));

                JLabel preview = new JLabel(imageIcon);
                preview.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseExited(MouseEvent e) {
                        cancelPreviewManager();
                    }
                });
                SwingUtilities.invokeLater(() -> displayPopup(mousePoint, preview));
            }
        }

        private static HttpURLConnection getHttpURLConnection(String imageUrl, String encodedUrl) throws IOException {
            // CloudFlare worker to protect users from IP grabbers
            URL url = new URL("https://image-proxy.cold-pine-9570.workers.dev/?url=" + encodedUrl);
            // Discord doesn't support CloudFlare workers :(
            if (imageUrl.startsWith("https://cdn.discordapp.com/")) {
                url = new URL(imageUrl);
            }
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Accept", "image/png,image/apng,image/*,*/*;q=0.8");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            return conn;
        }

        private BufferedImage scaleImage(BufferedImage originalImage) {
            double scale = Math.min(1.0, Math.min(
                    (double) MAX_PREVIEW_WIDTH / originalImage.getWidth(),
                    (double) MAX_PREVIEW_HEIGHT / originalImage.getHeight()
            ));

            int scaledWidth = (int) (originalImage.getWidth() * scale);
            int scaledHeight = (int) (originalImage.getHeight() * scale);

            BufferedImage scaledImage = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = scaledImage.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(originalImage, 0, 0, scaledWidth, scaledHeight, null);
            g2d.dispose();
            return scaledImage;
        }

        private void displayPopup(Point location, JComponent content) {
            try {
                cancelPreviewManager();
                if (!isShowing()) {
                    return;
                }

                Window topLevelWindow = SwingUtilities.getWindowAncestor(this);
                if (topLevelWindow == null) return;

                SwingUtilities.convertPointToScreen(location, this);

                Dimension contentSize = content.getPreferredSize();
                Rectangle screenBounds = topLevelWindow.getGraphicsConfiguration().getBounds();

                // Adjust Y coordinate
                if (location.y + contentSize.height > screenBounds.y + screenBounds.height) {
                    Point componentOnScreen = this.getLocationOnScreen();
                    int yAbove = componentOnScreen.y + location.y - contentSize.height;

                    if (yAbove >= screenBounds.y) {
                        location.y = yAbove;
                    } else {
                        location.y = screenBounds.y + screenBounds.height - contentSize.height;
                    }
                }

                if (location.x + contentSize.width > screenBounds.x + screenBounds.width) {
                    location.x = screenBounds.x + screenBounds.width - contentSize.width;
                }

                if (location.y < screenBounds.y) {
                    location.y = screenBounds.y;
                }
                if (location.x < screenBounds.x) {
                    location.x = screenBounds.x;
                }

                content.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
                content.setOpaque(true);

                currentImagePreview = PopupFactory.getSharedInstance().getPopup(
                        this, content, location.x, location.y);
                currentImagePreview.show();
            } catch (Exception e) {
                log.warn("Could not display popup", e);
            }
        }

        void hideImagePreview() {
            if (imagePreviewFuture != null && !imagePreviewFuture.isDone()) {
                imagePreviewFuture.cancel(true);
                imagePreviewFuture = null;
            }
            if (currentImagePreview != null) {
                currentImagePreview.hide();
                currentImagePreview = null;
            }
        }

        void cancelPreviewManager() {
            if (previewManager != null) {
                previewManager.cancelPreview();
            } else {
                hideImagePreview();
            }
        }

        void appendMessage(IrcMessage message, IrcConfig config) {
            String formattedMessage = formatPanelMessage(message, config);
            messageLog.add(formattedMessage);
            if (messageLog.size() > config.getMaxScrollback()) {
                messageLog.remove(0);
            }
            SwingUtilities.invokeLater(() -> {
                setText("<html><body style='color:" + ColorUtil.toHexColor(ColorScheme.TEXT_COLOR) + ";'>" + String.join("", messageLog) + "</body></html>");
                setCaretPosition(getDocument().getLength());
            });
        }

        private String formatPanelMessage(IrcMessage message, IrcConfig config) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
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
                String[] viableColorIds = new String[]{"02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13"};
                String colorId = viableColorIds[Math.abs(sender.hashCode()) % viableColorIds.length];
                String senderColor = htmlColorById(colorId);
                sender = String.format("<font style=\"color:%s\">%s</font>", senderColor, sender);
            }
            return String.format("<div style='color: %s'>%s%s: %s</div>", color, timeStamp, sender, formatMessage(message.getContent()));
        }

        private String formatMessage(String message) {
            String msg = formatColorCodes(escapeHtml4(message));
            Matcher matcher = VALID_LINK.matcher(msg);
            return convertModernEmojis(matcher.replaceAll("<a href=\"$1\">$1</a>"));
        }

        private String formatColorCodes(String message) {
            Matcher underline_matcher = UNDERLINE.matcher(message);
            message = underline_matcher.replaceAll("<u>$1</u>");
            Matcher italic_matcher = ITALIC.matcher(message);
            message = italic_matcher.replaceAll("<i>$1</i>");
            Matcher bold_matcher = BOLD.matcher(message);
            message = bold_matcher.replaceAll("<b>$1</b>");
            Matcher color_matcher = COLORS.matcher(message);
            StringBuffer sb = new StringBuffer();
            while (color_matcher.find()) {
                color_matcher.appendReplacement(sb, "<font color=\"" + htmlColorById(color_matcher.group(1)) + "\">" + Matcher.quoteReplacement(color_matcher.group(2)) + "</font>");
            }
            color_matcher.appendTail(sb);
            return STRIP_CODES.matcher(sb.toString()).replaceAll("");
        }

        private String htmlColorById(String id) {
            switch (id) {
                case "00":
                case "0":
                    return "white";
                case "01":
                case "1":
                    return "black";
                case "02":
                case "2":
                    return "#000080";
                case "03":
                case "3":
                    return "#008000";
                case "04":
                case "4":
                    return "#FF0000";
                case "05":
                case "5":
                    return "#800000";
                case "06":
                case "6":
                    return "#800080";
                case "07":
                case "7":
                    return "#FFA500";
                case "08":
                case "8":
                    return "#FFFF00";
                case "09":
                case "9":
                    return "#00FF00";
                case "10":
                    return "#008080";
                case "11":
                    return "#00FFFF";
                case "12":
                    return "#0000FF";
                case "13":
                    return "#FF00FF";
                case "14":
                    return "#808080";
                case "15":
                    return "#C0C0C0";
                default:
                    return "black";
            }
        }

        public void clear() {
            this.setText("");
            messageLog = new ArrayList<>();
        }
    }

    private static final Pattern MODERN_EMOJI_PATTERN = Pattern.compile("[" + "\uD83E\uDD70-\uD83E\uDDFF" +
            "\uD83E\uDE00-\uD83E\uDEFF" +
            "\uD83E\uDF00-\uD83E\uDFFF" +
            "\uD83E\uDD00-\uD83E\uDD6F" +
            "\uD83E\uDEC0-\uD83E\uDECF" + "\uD83E\uDED0-\uD83E\uDEFF" +
            "\uD83E\uDF00-\uD83E\uDF2F" +
            "\uD83E\uDF30-\uD83E\uDF5F" +
            "\uD83E\uDF60-\uD83E\uDF8F" +
            "\uFE0F" +
            "]" + "|\uD83C[\uDFFB-\uDFFF]" +
            "|\uD83E[\uDDB0-\uDDBF]"
    );

    public static String convertModernEmojis(String text) {
        if (text == null) return "";
        Matcher matcher = MODERN_EMOJI_PATTERN.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        matcher.reset();
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String modernEmoji = matcher.group();
            String replacement = EmojiParser.parseToAliases(modernEmoji, EmojiParser.FitzpatrickAction.PARSE);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private enum IrcShortcut {
        COLOR(KeyStroke.getKeyStroke(KeyEvent.VK_K, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "\u0003", "insertColorCode"),
        BOLD(KeyStroke.getKeyStroke(KeyEvent.VK_B, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "\u0002", "insertBold"),
        ITALIC(KeyStroke.getKeyStroke(KeyEvent.VK_I, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "\u001D", "insertItalic"),
        UNDERLINE(KeyStroke.getKeyStroke(KeyEvent.VK_U, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "\u001F", "insertUnderline");

        private final KeyStroke keyStroke;
        private final String insertText;
        private final String actionKey;

        IrcShortcut(KeyStroke keyStroke, String insertText, String actionKey) {
            this.keyStroke = keyStroke;
            this.insertText = insertText;
            this.actionKey = actionKey;
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
                newText = currentText.substring(0, selStart) + textToInsert + selectedText + textToInsert + currentText.substring(selEnd);
                newCaretPosition = selEnd + (textToInsert.length() * 2);
            } else {
                newText = currentText.substring(0, caretPosition) + textToInsert + currentText.substring(caretPosition);
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