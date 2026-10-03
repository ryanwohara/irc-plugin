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
import okhttp3.OkHttpClient;

import javax.inject.Inject;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.HyperlinkEvent;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.*;
import java.awt.event.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.commons.text.StringEscapeUtils.escapeHtml4;

@Slf4j
public class IrcPanel extends PluginPanel {
    @Inject
    private IrcConfig config;
    @Inject
    private ConfigManager configManager;
    @Inject
    private OkHttpClient okHttpClient;

    @Getter
    private final JPanel chatContent = new JPanel(new BorderLayout());
    private IrcPanelWindow panelWindow;
    private JPanel controlPanel;
    private IrcDesktopLayout desktopLayout;
    private boolean detachedLayout;
    private Timer flashTimer;
    private JTabbedPane tabbedPane;
    public JTextField inputField;
    @Getter
    private final Map<BufferKey, ChannelPane> channelPanes = Collections.synchronizedMap(new LinkedHashMap<>());
    @Getter
    private NavigationButton navigationButton;


    /** Joins a channel on a given network. */
    interface ChannelJoin {
        void join(String networkId, String channel, String password);
    }

    private BiConsumer<BufferKey, String> onMessageSend;
    private ChannelJoin onChannelJoin;
    private Consumer<BufferKey> onChannelLeave;
    /** Receives the network id to reconnect. */
    private Consumer<String> onReconnect;
    /** Receives (network id, query). */
    private BiConsumer<String, String> onChannelListRequest;
    private Runnable onChannelListTimeout;
    /** The network the channel browser is showing; joins from it go there. */
    private volatile String channelListNetworkId = NetworkConfig.SWIFTIRC_ID;
    private ChannelListDialog channelListDialog;
    private ChannelNumberKeys channelNumberKeys;
    private Timer channelListTimeout;
    private static final int CHANNEL_LIST_TIMEOUT_MS = 30000;
    private Font font;

    public final Map<BufferKey, Boolean> unreadMessages = new LinkedHashMap<>();
    private BufferKey focusedChannel;
    /** Network display names and connection state by id, in the order networks were announced. */
    private final Map<String, String> networkNames = new LinkedHashMap<>();
    private final Map<String, Boolean> networkConnected = new LinkedHashMap<>();
    /** Each network's saved channel order, by network id; new channels open in their place. */
    private final Map<String, List<String>> channelOrders = new HashMap<>();
    /** Set while tabs are moved or inserted: the tab pane's interim selection is meaningless. */
    private boolean reordering;

    /** Told when the user reorders networks or channels, so the order can be saved. */
    interface OrderListener {
        void networkOrderChanged(List<String> networkIds);

        void channelOrderChanged(String networkId, List<String> channelNames);
    }

    private OrderListener orderListener;

    {
        networkNames.put(NetworkConfig.SWIFTIRC_ID, NetworkConfig.SWIFTIRC_NAME);
    }
    private static final String SYSTEM_TAB = "System";

    // One per layout (side panel and pop-out); a Swing component can only have one parent.
    private final List<JComboBox<String>> fontSelectors = new ArrayList<>();
    private JComboBox<Integer> fontSizeSelector;
    private final JComboBox<String> bufferDropdown = getBufferComboBox();
    private final InputHistory inputHistory = new InputHistory(20);
    private final TabCompleter tabCompleter = new TabCompleter();

    private static final String USERS_HEADER_PREFIX = "Users (";
    /**
     * Rosters by buffer, keyed by {@link BufferKey#folded()}: IRC channel names are case-insensitive, and a
     * server that canonicalises casing differently between its JOIN echo and its 366 numeric would
     * otherwise file the roster under a key this panel never looks up - the dropdown would simply
     * never populate. Callers marshal to the EDT before calling {@link #setChannelUsers} - it
     * mutates a Swing model synchronously - so the synchronized map is cheap defensive depth, not
     * the primary thread-safety mechanism.
     */
    private final Map<BufferKey, List<ChannelUserList.Entry>> channelUserSnapshots =
            Collections.synchronizedMap(new HashMap<>());
    private List<ChannelUserList.Entry> displayedEntries = Collections.emptyList();
    /** Each buffer's current topic, keyed by {@link BufferKey#folded()}. Only touched on the EDT. */
    private final Map<BufferKey, String> channelTopics = new HashMap<>();
    private final JComboBox<String> nickDropdown = getNickComboBox();

    /** Every buffer, in tab order. Safe from any thread. */
    public List<BufferKey> getBuffers() {
        synchronized (channelPanes) {
            return new ArrayList<>(channelPanes.keySet());
        }
    }

    /** Buffer names in tab order, for callers that only deal with SwiftIRC. */
    public ArrayList<String> getChannelNames() {
        ArrayList<String> names = new ArrayList<>();
        for (BufferKey key : getBuffers()) names.add(key.getName());
        return names;
    }

    /** The tab/dropdown title: the bare name on SwiftIRC, "name (Network)" elsewhere. */
    String titleOf(BufferKey key) {
        if (NetworkConfig.SWIFTIRC_ID.equals(key.getNetworkId())) return key.getName();
        return key.getName() + " (" + networkNames.getOrDefault(key.getNetworkId(), key.getNetworkId()) + ")";
    }

    /** Test/legacy helper: the SwiftIRC pane with this name. */
    ChannelPane getPane(String name) {
        return channelPanes.get(BufferKey.swiftIrc(name));
    }

    /** Test/legacy helper: whether the SwiftIRC buffer with this name has unread lines. */
    boolean isUnread(String name) {
        return unreadMessages.getOrDefault(BufferKey.swiftIrc(name), false);
    }

    /**
     * Shared by the side panel and the game chatbox, so the trailing character class decides where
     * a link ends in both. A character missing from it does not reject the URL, it truncates it -
     * the link still renders and still clicks, it just goes somewhere else.
     *
     * '+' and '#' are kept last: placed mid-class either would form a range with the character
     * after it ('+' to ';' silently sweeps in digits and punctuation). Neither can start a link,
     * so a channel name like #osrs is still not matched.
     */
    public static final Pattern VALID_LINK = Pattern.compile("(https?://([\\w-]+\\.)+[\\w-]+([\\w-;:,./?%&=+#]*))");

    private void initializeFlashTimer() {
        // Change color for different flash
        flashTimer = new Timer(500, e -> {
            BufferKey current = getCurrentBuffer();
            List<BufferKey> buffers = getBuffers();
            for (int i = 0; i < tabbedPane.getTabCount() && i < buffers.size(); i++) {
                BufferKey key = buffers.get(i);
                boolean unread = unreadMessages.getOrDefault(key, false);
                if (SYSTEM_TAB.equals(key.getName())) continue;
                if (unread && !key.equals(current)) {
                    tabbedPane.setForegroundAt(i, new Color(135, 206, 250)); // Change color for different flash
                } else if (!unread) {
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

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentHidden(ComponentEvent e) {
                hideAllPreviews();
            }
        });

        controlPanel = new JPanel();
        controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));
        // Buttons at their fixed size on the left; the font dropdown takes whatever width is left.
        // A single FlowLayout wrapped overflow onto a second line that the row's height hid.
        JPanel row1 = new JPanel(new BorderLayout());
        JPanel row1Buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JPanel fontCell = new JPanel(new GridBagLayout());
        // Two equal columns rather than FlowLayout: the panel is only ~225px wide, and a long
        // channel name made the buffer dropdown wide enough to push the pair onto a second row.
        // A grid splits the width evenly and clips inside a cell instead of wrapping. Fill order
        // is still left-to-right, so the nick dropdown stays left of the buffer dropdown.
        JPanel row2 = new JPanel(new GridLayout(1, 2, 2, 0));

        JButton addButton = new JButton("+");
        JButton removeButton = new JButton("-");
        JButton reloadButton = new JButton();
        JButton popOutButton = new JButton("^");
        popOutButton.setToolTipText("Pop out window");
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
        popOutButton.setPreferredSize(standard);
        final JComboBox<String> fontComboBox = getFontComboBox();


        bufferDropdown.addActionListener(this::actionPerformed);

        addButton.addActionListener(e -> promptAddChannel());
        removeButton.addActionListener(e -> promptRemoveChannel());
        reloadButton.addActionListener(e -> onReconnect.accept(getCurrentBuffer().getNetworkId()));
        popOutButton.addActionListener(e -> configManager.setConfiguration("irc", "popOut", true));
        row1Buttons.add(reloadButton);
        row1Buttons.add(addButton);
        row1Buttons.add(removeButton);
        row1Buttons.add(popOutButton);
        fontComboBox.setMinimumSize(new Dimension(0, fontComboBox.getPreferredSize().height));
        GridBagConstraints fontFill = new GridBagConstraints();
        fontFill.fill = GridBagConstraints.HORIZONTAL;
        fontFill.weightx = 1;
        fontFill.insets = new Insets(0, 0, 0, 5);
        fontCell.add(fontComboBox, fontFill);
        row1.add(row1Buttons, BorderLayout.WEST);
        row1.add(fontCell, BorderLayout.CENTER);
        row2.add(nickDropdown);
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
        channelNumberKeys = new ChannelNumberKeys(chatContent, this::jumpToChannel);
        channelNumberKeys.install();
        inputField.addActionListener(e -> {
            String message = inputField.getText();
            if (!message.isEmpty() && onMessageSend != null) {
                onMessageSend.accept(getCurrentBuffer(), message);
                inputHistory.add(message);
                inputField.setText("");
            }
        });
        chatContent.add(controlPanel, BorderLayout.NORTH);
        chatContent.add(tabbedPane, BorderLayout.CENTER);
        chatContent.add(inputField, BorderLayout.SOUTH);
        add(chatContent, BorderLayout.CENTER);
        panelWindow = new IrcPanelWindow(this, chatContent, this::prepareForHostChange,
                this::hideAllPreviews, this::requestDock);
        navigationButton = generateNavigationButton();
        SwingUtilities.invokeLater(() -> addChannel("System"));
        tabbedPane.addChangeListener(e -> onFocusedBufferChanged());
        initializeFlashTimer();
    }

    /** Runs when the focused buffer changes. Package-private so tests can drive it without the
     *  full initializeGui() Swing setup, which cannot run headless. */
    void onFocusedBufferChanged() {
        if (reordering) return;
        BufferKey current = getCurrentBuffer();
        focusedChannel = current;
        if (unreadMessages.containsKey(current)) {
            unreadMessages.put(current, false);
            int selectedIndex = tabbedPane.getSelectedIndex();
            if (selectedIndex != -1) {
                tabbedPane.setForegroundAt(selectedIndex, Color.WHITE);
            }
        }
        repopulateNickDropdown();
        refreshDesktopChannels();
    }

    public void cycleChannel() {
        List<BufferKey> buffers = getBuffers();
        if (buffers.isEmpty()) return;
        int index = (buffers.indexOf(getCurrentBuffer()) + 1) % buffers.size();
        setFocusedChannel(buffers.get(index));
    }

    public void cycleChannelBackwards() {
        List<BufferKey> buffers = getBuffers();
        if (buffers.isEmpty()) return;
        int index = buffers.indexOf(getCurrentBuffer()) - 1;
        setFocusedChannel(buffers.get(index < 0 ? buffers.size() - 1 : index));
    }

    /**
     * Focuses the buffer at a 1-based position in the order the user sees: the pop-out's channel
     * tree groups buffers, so its order can differ from the side panel's tabs.
     */
    void jumpToChannel(int number) {
        List<BufferKey> buffers = detachedLayout ? desktopLayout.channelOrder() : getBuffers();
        if (number < 1 || number > buffers.size()) return;
        setFocusedChannel(buffers.get(number - 1));
        inputField.requestFocusInWindow();
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
        final JComboBox<String> bufferComboBox = getStringJComboBox();

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

    private JComboBox<String> getNickComboBox() {
        final JComboBox<String> combo = new JComboBox<>();
        combo.setBackground(Color.DARK_GRAY);
        combo.setForeground(Color.WHITE);
        // row2's GridLayout decides the width (~105px per column), so this mainly fixes the height.
        // "Users (999)" measures ~89px including the combo's chrome, so the column has headroom;
        // 90px was on the clip boundary, which is why the header read "Users (...".
        combo.setPreferredSize(new Dimension(110, 25));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                String text = value == null ? "" : value.toString();
                if (text.startsWith(USERS_HEADER_PREFIX)) {
                    label.setForeground(Color.GRAY);
                } else {
                    label.setForeground(nickColorAt(index, text));
                }
                return label;
            }
        });
        combo.addActionListener(e -> openQueryFromNickDropdown());
        return combo;
    }

    /**
     * Colors a dropdown label the same way the chat pane colors that nick. The label carries a
     * prefix character, so the raw nick is taken from the backing entry rather than the text.
     */
    private Color nickColor(String label) {
        for (ChannelUserList.Entry entry : displayedEntries) {
            if (label.equals(entry.getPrefix() + entry.getNick())) {
                return nickColorFor(entry.getNick());
            }
        }
        return Color.WHITE;
    }

    /** Colours a dropdown row. Prefers the row index the renderer already has; the label scan is
     *  only for index -1, the collapsed button, which renders one item. */
    private Color nickColorAt(int index, String label) {
        List<ChannelUserList.Entry> entries = displayedEntries;
        if (index >= 1 && index <= entries.size()) {
            return nickColorFor(entries.get(index - 1).getNick());
        }
        return nickColor(label);
    }

    private Color nickColorFor(String nick) {
        try {
            return Color.decode(ChannelPane.htmlColorById(ChannelPane.nickColorId(nick)));
        } catch (NumberFormatException ignored) {
            return Color.WHITE;
        }
    }

    /** Opens (or focuses) a PM buffer for the selected nick, then returns to the header. */
    private void openQueryFromNickDropdown() {
        List<ChannelUserList.Entry> entries = displayedEntries;
        int index = nickDropdown.getSelectedIndex();
        if (index < 1 || index > entries.size()) {
            return;
        }
        BufferKey query = BufferKey.of(getCurrentBuffer().getNetworkId(), entries.get(index - 1).getNick());
        nickDropdown.setSelectedIndex(0);
        addChannel(query);
        setFocusedChannel(query);
    }

    /** Pushes a fresh roster in. Only redraws when it is for the buffer currently on screen. */
    public void setChannelUsers(BufferKey channel, List<ChannelUserList.Entry> entries) {
        channelUserSnapshots.put(channel.folded(), entries);
        // Folded, not exact: the name here comes from a server numeric and the tab title from a
        // JOIN echo, which need not agree on casing.
        if (tabbedPane != null && channel.folded().equals(getCurrentBuffer().folded())) {
            repopulateNickDropdown();
        }
    }

    public void setChannelUsers(String channel, List<ChannelUserList.Entry> entries) {
        setChannelUsers(BufferKey.swiftIrc(channel), entries);
    }

    /**
     * Rebuilds the dropdown for the focused buffer. Action listeners are detached for the
     * duration: this runs from inside the dropdown's own listener whenever selecting a nick
     * changes the focused tab.
     */
    private void repopulateNickDropdown() {
        if (tabbedPane == null) {
            return;
        }
        BufferKey current = getCurrentBuffer();
        String channel = current.getName();
        List<ChannelUserList.Entry> entries =
                channelUserSnapshots.getOrDefault(current.folded(), Collections.emptyList());
        displayedEntries = entries;
        if (desktopLayout != null) desktopLayout.updateUsers(channel, entries);

        ActionListener[] listeners = nickDropdown.getActionListeners();
        for (ActionListener listener : listeners) {
            nickDropdown.removeActionListener(listener);
        }
        try {
            nickDropdown.removeAllItems();
            nickDropdown.addItem(USERS_HEADER_PREFIX + entries.size() + ")");
            for (ChannelUserList.Entry entry : entries) {
                nickDropdown.addItem(entry.getPrefix() + entry.getNick());
            }
            nickDropdown.setSelectedIndex(0);
            nickDropdown.setEnabled(channel != null && channel.startsWith("#"));
        } finally {
            for (ActionListener listener : listeners) {
                nickDropdown.addActionListener(listener);
            }
        }
    }

    private JComboBox<String> getStringJComboBox() {
        final JComboBox<String> bufferComboBox = new JComboBox<>();

        bufferComboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

                List<BufferKey> buffers = getBuffers();
                int row = index >= 0 ? index : bufferComboBox.getSelectedIndex();
                boolean unread = row >= 0 && row < buffers.size()
                        && unreadMessages.getOrDefault(buffers.get(row), false);
                if (unread) {
                    label.setForeground(new Color(135, 206, 250)); // Change color for different flash
                } else {
                    label.setForeground(Color.white);
                }

                return label;
            }
        });
        return bufferComboBox;
    }

    public void hideAllPreviews() {
        if (channelPanes != null) {
            synchronized (channelPanes) {
                for (ChannelPane pane : channelPanes.values()) {
                    pane.cancelPreviewManager();
                }
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

    public void setFocusedChannel(BufferKey channel) {
        if (channel == null || !unreadMessages.containsKey(channel)) return;
        int index = getBuffers().indexOf(channel);
        if (index < 0) return;
        unreadMessages.put(channel, false);
        tabbedPane.setForegroundAt(index, Color.WHITE);
        tabbedPane.setSelectedIndex(index);
        bufferDropdown.setSelectedIndex(index);
        this.focusedChannel = channel;
        refreshDesktopChannels();
    }

    public void setFocusedChannel(String channel) {
        if (channel != null) setFocusedChannel(BufferKey.swiftIrc(channel));
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
                // Syncing the other selectors fires this listener too; skip when nothing changed.
                if (selected.equals(config.fontFamily())) return;
                configManager.setConfiguration("irc", "fontFamily", selected);
                updateFont();
            }
        });
        fontSelectors.add(fontComboBox);
        return fontComboBox;
    }

    /** Sizes match the Font Size config's range (8–32). */
    private JComboBox<Integer> getFontSizeComboBox() {
        Integer[] sizes = new Integer[32 - 8 + 1];
        for (int i = 0; i < sizes.length; i++) sizes[i] = 8 + i;
        final JComboBox<Integer> sizeComboBox = new JComboBox<>(sizes);
        sizeComboBox.setName("ircFontSize");
        sizeComboBox.setSelectedItem(config.fontSize());
        sizeComboBox.addActionListener(e -> {
            Integer selected = (Integer) sizeComboBox.getSelectedItem();
            // Syncing from the config fires this listener too; skip when nothing changed.
            if (selected == null || selected == config.fontSize()) return;
            configManager.setConfiguration("irc", "fontSize", selected);
            updateFont();
        });
        fontSizeSelector = sizeComboBox;
        return sizeComboBox;
    }

    void updateFont() {
        font = new Font(config.fontFamily(), Font.PLAIN, config.fontSize());
        inputField.setFont(font);
        for (JComboBox<String> fontSelector : fontSelectors) {
            if (!config.fontFamily().equals(fontSelector.getSelectedItem())) {
                fontSelector.setSelectedItem(config.fontFamily());
            }
        }
        if (fontSizeSelector != null && !Integer.valueOf(config.fontSize()).equals(fontSizeSelector.getSelectedItem())) {
            fontSizeSelector.setSelectedItem(config.fontSize());
        }
        synchronized (channelPanes) {
            for (ChannelPane channelPane : channelPanes.values()) {
                channelPane.applyFont(font);
            }
        }
    }

    void updateColors() {
        synchronized (channelPanes) {
            for (ChannelPane channelPane : channelPanes.values()) {
                channelPane.applyColors();
            }
        }
    }

    @Provides
    IrcConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(IrcConfig.class);
    }

    public void init(BiConsumer<BufferKey, String> messageSendCallback, ChannelJoin channelJoinCallback,
                     Consumer<BufferKey> channelLeaveCallback, Consumer<String> onReconnect,
                     BiConsumer<String, String> channelListRequestCallback, Runnable channelListTimeoutCallback) {
        this.onMessageSend = messageSendCallback;
        this.onChannelJoin = channelJoinCallback;
        this.onChannelLeave = channelLeaveCallback;
        this.onReconnect = onReconnect;
        this.onChannelListRequest = channelListRequestCallback;
        this.onChannelListTimeout = channelListTimeoutCallback;
    }

    /** The selected buffer; SwiftIRC's System when nothing is selected. */
    public BufferKey getCurrentBuffer() {
        int index = tabbedPane == null ? -1 : tabbedPane.getSelectedIndex();
        List<BufferKey> buffers = getBuffers();
        return index >= 0 && index < buffers.size() ? buffers.get(index) : BufferKey.swiftIrc(SYSTEM_TAB);
    }

    public String getCurrentChannel() {
        return getCurrentBuffer().getName();
    }

    /** The selected buffer's name when it is on {@code networkId}, else that network's System. */
    public String getCurrentChannelOn(String networkId) {
        BufferKey current = getCurrentBuffer();
        return current.getNetworkId().equals(networkId) ? current.getName() : SYSTEM_TAB;
    }

    public void clearCurrentPane() {
        ChannelPane pane = channelPanes.get(getCurrentBuffer());
        if (pane != null) {
            pane.clear();
        }
    }

    public boolean isPane(BufferKey key) {
        return channelPanes.containsKey(key);
    }

    public boolean isPane(String name) {
        return isPane(BufferKey.swiftIrc(name));
    }

    /** Asks the plugin for the selected buffer's network's channel list. */
    public void requestChannelList(String query) {
        requestChannelList(getCurrentBuffer().getNetworkId(), query);
    }

    /** Asks the plugin for a channel list. {@code query} is passed to the server verbatim. */
    public void requestChannelList(String networkId, String query) {
        channelListNetworkId = networkId;
        if (onChannelListRequest != null) {
            onChannelListRequest.accept(networkId, query != null ? query : "");
        }
    }

    String getChannelListNetworkId() {
        return channelListNetworkId;
    }

    void setChannelListNetworkId(String networkId) {
        channelListNetworkId = networkId;
    }

    /**
     * Starts the window in which a LIST reply is expected. A server that never sends 323 would
     * otherwise leave the user with no feedback at all.
     *
     * <p>The expiry is reported through the plugin rather than written straight into this panel:
     * "Requesting channel list..." and an explicit 263 refusal both reach the game chatbox as well
     * as the panel, and a user watching game chat with the sidebar collapsed would otherwise see
     * the request announced and never learn it failed.
     */
    public void armChannelListTimeout() {
        cancelChannelListTimeout();
        channelListTimeout = new Timer(CHANNEL_LIST_TIMEOUT_MS, e -> {
            if (onChannelListTimeout != null) {
                onChannelListTimeout.run();
            }
        });
        channelListTimeout.setRepeats(false);
        channelListTimeout.start();
    }

    /** Stops the pending timeout. Safe to call when nothing is armed. */
    public void cancelChannelListTimeout() {
        if (channelListTimeout != null) {
            channelListTimeout.stop();
        }
    }

    /**
     * Releases what this panel owns outside its own component tree, on plugin shutdown.
     *
     * The channel browser is a top-level {@link java.awt.Window}: dropping the panel does not take
     * it with it. Without this it stays on screen after the plugin is disabled, wired to a plugin
     * that is gone - Refresh and Join both return silently, so it looks alive and does nothing.
     * Re-enabling compounds it, because the injector hands out a fresh panel with a fresh dialog
     * and the old one leaks along with its sorter and up to 20,000 entries.
     *
     * Nulling both fields makes a later {@link #showChannelList} rebuild cleanly. Safe to call
     * repeatedly, and when nothing was ever armed or shown.
     */
    public void shutdown() {
        if (channelNumberKeys != null) {
            channelNumberKeys.uninstall();
            channelNumberKeys = null;
        }
        if (panelWindow != null) {
            panelWindow.shutdown();
            panelWindow = null;
        }
        if (flashTimer != null) {
            flashTimer.stop();
        }
        hideAllPreviews();
        cancelChannelListTimeout();
        channelListTimeout = null;
        if (channelListDialog != null) {
            channelListDialog.dispose();
            channelListDialog = null;
        }
    }

    private IrcDesktopLayout.NetworkActions networkActions = IrcDesktopLayout.NetworkActions.NONE;

    /** Set by the plugin; the pop-out asks it to reconnect, pause or edit networks. */
    public void setNetworkActions(IrcDesktopLayout.NetworkActions actions) {
        this.networkActions = actions != null ? actions : IrcDesktopLayout.NetworkActions.NONE;
    }

    public void setDetached(boolean detached, boolean alwaysOnTop) {
        if (detachedLayout != detached) {
            if (detached) {
                if (desktopLayout == null) {
                    desktopLayout = new IrcDesktopLayout(
                            key -> unreadMessages.getOrDefault(key, false), this::setFocusedChannel,
                            nick -> {
                                BufferKey query = BufferKey.of(getCurrentBuffer().getNetworkId(), nick);
                                addChannel(query);
                                setFocusedChannel(query);
                                inputField.requestFocusInWindow();
                            },
                            nick -> onMessageSend.accept(getCurrentBuffer(), "/whois " + nick),
                            this::promptAddChannel, this::promptRemoveChannel,
                            () -> requestChannelList(""),
                            new IrcDesktopLayout.NetworkActions() {
                                @Override public void reconnect(String id) { networkActions.reconnect(id); }
                                @Override public void setConnected(String id, boolean c) { networkActions.setConnected(id, c); }
                                @Override public void edit(String id) { networkActions.edit(id); }
                            },
                            this::requestDock, getFontComboBox(), getFontSizeComboBox(),
                            nick -> config.colorizedNicks() ? nickColorFor(nick) : null);
                    desktopLayout.setMoves(new IrcDesktopLayout.Moves() {
                        @Override public void moveNetwork(String id, int newIndex) { userMovedNetwork(id, newIndex); }
                        @Override public void moveBuffer(BufferKey key, int newIndex) { userMovedBuffer(key, newIndex); }
                    });
                }
                chatContent.remove(controlPanel);
                desktopLayout.attachChat(tabbedPane, inputField);
                chatContent.add(desktopLayout, BorderLayout.CENTER);
                refreshDesktopChannels();
                repopulateNickDropdown();
            } else {
                chatContent.remove(desktopLayout);
                chatContent.add(controlPanel, BorderLayout.NORTH);
                chatContent.add(tabbedPane, BorderLayout.CENTER);
                chatContent.add(inputField, BorderLayout.SOUTH);
            }
            detachedLayout = detached;
            chatContent.revalidate();
            chatContent.repaint();
        }
        panelWindow.setDetached(detached, alwaysOnTop);
    }

    private void refreshDesktopChannels() {
        if (desktopLayout == null) return;
        desktopLayout.updateChannels(networkNodes(), getCurrentBuffer());
        desktopLayout.showTopic(channelTopics.getOrDefault(getCurrentBuffer().folded(), ""));
    }

    /** Networks in announcement order, each with its buffers in tab order. */
    private List<IrcDesktopLayout.NetworkNode> networkNodes() {
        Map<String, List<String>> buffersByNetwork = new LinkedHashMap<>();
        for (String id : networkNames.keySet()) buffersByNetwork.put(id, new ArrayList<>());
        for (BufferKey key : getBuffers()) {
            buffersByNetwork.computeIfAbsent(key.getNetworkId(), id -> new ArrayList<>()).add(key.getName());
        }
        List<IrcDesktopLayout.NetworkNode> nodes = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : buffersByNetwork.entrySet()) {
            String id = entry.getKey();
            nodes.add(new IrcDesktopLayout.NetworkNode(id, networkNames.getOrDefault(id, id),
                    networkConnected.getOrDefault(id, false), entry.getValue()));
        }
        return nodes;
    }

    public void bringPopOutToFront() {
        panelWindow.toFront();
    }

    private void requestDock() {
        configManager.setConfiguration("irc", "popOut", false);
    }

    private void prepareForHostChange() {
        hideAllPreviews();
        // Dialog owners cannot change. Recreate the browser against the new host on next use.
        if (channelListDialog != null) {
            channelListDialog.dispose();
            channelListDialog = null;
        }
    }

    /**
     * Shows the channel browser. Reuses one dialog so a repeated /list refreshes in place.
     * An empty result still opens - "0 channels" answers a narrow query.
     *
     * <p>Does not marshal onto the EDT itself - callers on a background thread (e.g. the IRC
     * reader thread delivering a LIST reply) must wrap the call in
     * {@link SwingUtilities#invokeLater}.
     */
    public void showChannelList(List<ChannelListEntry> entries, String query, boolean truncated) {
        showChannelList(NetworkConfig.SWIFTIRC_ID, entries, query, truncated);
    }

    public void showChannelList(String networkId, List<ChannelListEntry> entries, String query, boolean truncated) {
        channelListNetworkId = networkId;
        cancelChannelListTimeout();
        if (channelListDialog == null) {
            channelListDialog = new ChannelListDialog(
                    SwingUtilities.getWindowAncestor(chatContent),
                    (channel, password) -> {
                        if (onChannelJoin != null) {
                            onChannelJoin.join(channelListNetworkId, channel, password);
                        }
                    },
                    q -> requestChannelList(channelListNetworkId, q));
        }
        channelListDialog.setEntries(entries, query, truncated);
        channelListDialog.showDialog();
    }

    public void addChannel(BufferKey channel) {
        if (channelPanes.containsKey(channel)) return;
        ChannelPane pane = new ChannelPane(font, config, okHttpClient);
        int index = insertionIndex(channel);
        if (index < 0) {
            bufferDropdown.addItem(titleOf(channel));
        }

        JScrollPane scrollPane = new JScrollPane(pane);
        scrollPane.getVerticalScrollBar().addAdjustmentListener(e -> pane.cancelPreviewManager());

        if (index < 0) {
            channelPanes.put(channel, pane);
            unreadMessages.put(channel, false);
            tabbedPane.addTab(titleOf(channel), new JScrollPane(pane));
            index = tabbedPane.getTabCount() - 1;
        } else {
            insertBuffer(channel, pane, index);
        }
        boolean configuredChannel = NetworkConfig.SWIFTIRC_ID.equals(channel.getNetworkId())
                && channel.getName().equals(config.channel());
        if (config.autofocusOnNewTab() || configuredChannel || channelPanes.size() == 2) {
            tabbedPane.setSelectedIndex(index);
            this.setFocusedChannel(channel);
        }
        refreshDesktopChannels();
    }

    /**
     * Where a new channel goes when its network has a saved order: before the first open channel
     * of that network that the order puts later, else just after the network's last channel.
     * -1 means append, as for every buffer without a saved place.
     */
    private int insertionIndex(BufferKey channel) {
        List<String> order = channelOrders.get(channel.getNetworkId());
        if (order == null || !channel.isChannel()) return -1;
        int rank = OrderStore.indexIn(order, channel.getName());
        if (rank < 0) return -1;
        List<BufferKey> buffers = getBuffers();
        int lastChannel = -1;
        for (int i = 0; i < buffers.size(); i++) {
            BufferKey key = buffers.get(i);
            if (!key.getNetworkId().equals(channel.getNetworkId()) || !key.isChannel()) continue;
            if (OrderStore.indexIn(order, key.getName()) > rank) return i;
            lastChannel = i;
        }
        return lastChannel < 0 || lastChannel + 1 >= buffers.size() ? -1 : lastChannel + 1;
    }

    /** Adds a buffer at {@code index} rather than the end, keeping the same buffer selected. */
    private void insertBuffer(BufferKey channel, ChannelPane pane, int index) {
        BufferKey selected = getCurrentBuffer();
        List<BufferKey> order = getBuffers();
        order.add(index, channel);
        reordering = true;
        try {
            synchronized (channelPanes) {
                channelPanes.put(channel, pane);
                reorderKeys(channelPanes, order);
            }
            unreadMessages.put(channel, false);
            reorderKeys(unreadMessages, order);
            withoutDropdownListeners(() -> bufferDropdown.insertItemAt(titleOf(channel), index));
            tabbedPane.insertTab(titleOf(channel), null, new JScrollPane(pane), null, index);
            reselect(selected);
        } finally {
            reordering = false;
        }
    }

    /**
     * Moves a buffer's tab, pane, unread flag and dropdown entry to {@code newIndex} in the tab
     * order, together, so the three stay index-aligned. The same buffer stays selected. EDT only.
     */
    void moveBuffer(BufferKey key, int newIndex) {
        List<BufferKey> order = getBuffers();
        int from = order.indexOf(key);
        if (from < 0 || newIndex < 0 || newIndex >= order.size() || from == newIndex) return;
        BufferKey selected = getCurrentBuffer();
        order.remove(from);
        order.add(newIndex, key);
        reordering = true;
        try {
            String title = tabbedPane.getTitleAt(from);
            Color foreground = tabbedPane.getForegroundAt(from);
            Component content = tabbedPane.getComponentAt(from);
            tabbedPane.removeTabAt(from);
            tabbedPane.insertTab(title, null, content, null, newIndex);
            tabbedPane.setForegroundAt(newIndex, foreground);
            synchronized (channelPanes) {
                reorderKeys(channelPanes, order);
            }
            reorderKeys(unreadMessages, order);
            withoutDropdownListeners(() -> {
                String item = bufferDropdown.getItemAt(from);
                bufferDropdown.removeItemAt(from);
                bufferDropdown.insertItemAt(item, newIndex);
            });
            reselect(selected);
        } finally {
            reordering = false;
        }
        refreshDesktopChannels();
    }

    /** Selects {@code key}'s tab and dropdown entry again after the indexes shifted. */
    private void reselect(BufferKey key) {
        int index = getBuffers().indexOf(key);
        if (index < 0) return;
        tabbedPane.setSelectedIndex(index);
        withoutDropdownListeners(() -> bufferDropdown.setSelectedIndex(index));
    }

    private void withoutDropdownListeners(Runnable change) {
        ActionListener[] listeners = bufferDropdown.getActionListeners();
        for (ActionListener listener : listeners) bufferDropdown.removeActionListener(listener);
        try {
            change.run();
        } finally {
            for (ActionListener listener : listeners) bufferDropdown.addActionListener(listener);
        }
    }

    /** Rebuilds {@code map} in {@code order}; keys it lacks are skipped, keys not listed kept last. */
    private static <K, V> void reorderKeys(Map<K, V> map, List<K> order) {
        LinkedHashMap<K, V> rebuilt = new LinkedHashMap<>();
        for (K key : order) {
            if (map.containsKey(key)) rebuilt.put(key, map.get(key));
        }
        for (Map.Entry<K, V> entry : map.entrySet()) rebuilt.putIfAbsent(entry.getKey(), entry.getValue());
        map.clear();
        map.putAll(rebuilt);
    }

    /** Set by the plugin; told only about moves the user makes, never about loaded orders. */
    void setOrderListener(OrderListener listener) {
        this.orderListener = listener;
    }

    /** Network ids in the order the pop-out lists them. */
    List<String> networkOrder() {
        return new ArrayList<>(networkNames.keySet());
    }

    /**
     * Lists the given networks first, in that order; ids not given keep their current order after
     * them, and ids the panel does not know are ignored. EDT only.
     */
    public void setNetworkOrder(List<String> networkIds) {
        LinkedHashMap<String, String> reordered = new LinkedHashMap<>();
        for (String id : networkIds) {
            if (networkNames.containsKey(id)) reordered.put(id, networkNames.get(id));
        }
        for (Map.Entry<String, String> entry : networkNames.entrySet()) {
            reordered.putIfAbsent(entry.getKey(), entry.getValue());
        }
        if (new ArrayList<>(reordered.keySet()).equals(networkOrder())) return;
        networkNames.clear();
        networkNames.putAll(reordered);
        refreshDesktopChannels();
    }

    /** Remembers a network's channel order for channels opened from now on. EDT only. */
    public void setChannelOrder(String networkId, List<String> channelNames) {
        channelOrders.put(networkId, new ArrayList<>(channelNames));
    }

    /**
     * Puts a network's open channels into its saved order. The channels the order names swap
     * among their own tab positions; every other buffer stays where it is. EDT only.
     */
    public void sortChannels(String networkId) {
        List<String> order = channelOrders.get(networkId);
        if (order == null || order.isEmpty()) return;
        List<BufferKey> desired = getBuffers();
        List<Integer> slots = new ArrayList<>();
        List<BufferKey> listed = new ArrayList<>();
        for (int i = 0; i < desired.size(); i++) {
            BufferKey key = desired.get(i);
            if (key.getNetworkId().equals(networkId) && key.isChannel()
                    && OrderStore.indexIn(order, key.getName()) >= 0) {
                slots.add(i);
                listed.add(key);
            }
        }
        listed.sort(Comparator.comparingInt(key -> OrderStore.indexIn(order, key.getName())));
        for (int i = 0; i < slots.size(); i++) desired.set(slots.get(i), listed.get(i));
        for (int i = 0; i < desired.size(); i++) {
            if (!getBuffers().get(i).equals(desired.get(i))) moveBuffer(desired.get(i), i);
        }
    }

    /**
     * The user dropped a buffer at {@code indexInGroup} among its network's channels (or private
     * chats). A channel move is remembered and reported so it can be saved.
     */
    void userMovedBuffer(BufferKey key, int indexInGroup) {
        List<BufferKey> before = getBuffers();
        if (!before.contains(key) || SYSTEM_TAB.equals(key.getName())) return;
        List<BufferKey> others = new ArrayList<>();
        for (BufferKey other : before) {
            if (!other.equals(key) && sameGroup(key, other)) others.add(other);
        }
        if (others.isEmpty()) return;
        List<BufferKey> without = new ArrayList<>(before);
        without.remove(key);
        int target = indexInGroup < others.size()
                ? without.indexOf(others.get(Math.max(0, indexInGroup)))
                : without.indexOf(others.get(others.size() - 1)) + 1;
        moveBuffer(key, target);
        if (getBuffers().equals(before) || !key.isChannel()) return;
        List<String> names = new ArrayList<>();
        for (BufferKey buffer : getBuffers()) {
            if (buffer.getNetworkId().equals(key.getNetworkId()) && buffer.isChannel()) names.add(buffer.getName());
        }
        channelOrders.put(key.getNetworkId(), names);
        if (orderListener != null) orderListener.channelOrderChanged(key.getNetworkId(), new ArrayList<>(names));
    }

    /** Channels group with channels and private chats with private chats, per network. */
    private static boolean sameGroup(BufferKey a, BufferKey b) {
        return a.getNetworkId().equals(b.getNetworkId()) && a.isChannel() == b.isChannel()
                && !SYSTEM_TAB.equals(b.getName());
    }

    /** The user dropped a network at {@code newIndex} among the networks; reported so it can be saved. */
    void userMovedNetwork(String networkId, int newIndex) {
        List<String> order = networkOrder();
        if (!order.remove(networkId)) return;
        order.add(Math.max(0, Math.min(newIndex, order.size())), networkId);
        if (order.equals(networkOrder())) return;
        setNetworkOrder(order);
        if (orderListener != null) orderListener.networkOrderChanged(new ArrayList<>(order));
    }

    public void addChannel(String channel) {
        addChannel(BufferKey.swiftIrc(channel));
    }

    public void removeChannel(BufferKey channel) {
        removeBuffer(channel, false);
    }

    public void removeChannel(String channel) {
        removeChannel(BufferKey.swiftIrc(channel));
    }

    /** Closes every buffer of a network, its System buffer included. */
    public void removeNetworkBuffers(String networkId) {
        for (BufferKey key : getBuffers()) {
            if (key.getNetworkId().equals(networkId)) removeBuffer(key, true);
        }
    }

    private void removeBuffer(BufferKey channel, boolean includingSystem) {
        if (!channelPanes.containsKey(channel)) return;
        if (!includingSystem && SYSTEM_TAB.equals(channel.getName())) return;
        int index = getBuffers().indexOf(channel);
        if (index == -1 || index >= tabbedPane.getTabCount()) return;
        tabbedPane.removeTabAt(index);
        channelPanes.remove(channel);
        unreadMessages.remove(channel);
        bufferDropdown.removeItemAt(index);
        channelUserSnapshots.remove(channel.folded());
        channelTopics.remove(channel.folded());
        onFocusedBufferChanged();
    }

    public void addMessage(IrcMessage message) {
        BufferKey key = message.getBuffer();
        // A line already on its way when the network was removed must not bring its buffer back.
        if (!NetworkConfig.SWIFTIRC_ID.equals(key.getNetworkId())
                && !networkNames.containsKey(key.getNetworkId())) {
            return;
        }
        ChannelPane pane = channelPanes.get(key);
        if (pane == null) {
            addChannel(key);
            pane = channelPanes.get(key);
        }
        boolean history = message.getType() == IrcMessage.MessageType.HISTORY
                || message.getType() == IrcMessage.MessageType.HISTORY_SEPARATOR;
        if (!key.equals(focusedChannel) && !history) {
            unreadMessages.put(key, true);
        }
        if (message.getType() == IrcMessage.MessageType.TOPIC
                && IrcMessage.TOPIC_SENDER.equals(message.getSender())) {
            String topic = IrcFormatting.stripCodes(message.getContent());
            channelTopics.put(key.folded(), topic == null ? "" : topic.trim());
        }
        pane.appendMessage(message, config);
        refreshDesktopChannels();
    }

    /**
     * Gives a component the keyboard focus as soon as it is actually on screen.
     *
     * {@link JOptionPane#showOptionDialog} does not set the pane's {@code wantsInput} flag, so
     * BasicOptionPaneUI's initial-value selection focuses the default button instead of the input
     * field - unlike {@code showInputDialog}, which focuses the field. Requesting focus before the
     * dialog is shown is a no-op (the component has no window yet), hence the hierarchy listener.
     */
    private static void focusWhenShown(JComponent component) {
        component.addHierarchyListener(new HierarchyListener() {
            @Override
            public void hierarchyChanged(HierarchyEvent e) {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && component.isShowing()) {
                    component.requestFocusInWindow();
                }
            }
        });
    }

    private void promptAddChannel() {
        JTextField channelField = new JTextField();
        focusWhenShown(channelField);
        Object[] options = {"Join", "Browse…", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
                chatContent,
                new Object[]{"Enter channel name:", channelField},
                "Add channel",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                options,
                options[0]);

        if (choice == 1) {
            // Browse: let the user pick from the server's list instead of typing a name.
            requestChannelList("");
            return;
        }
        if (choice != 0) return;

        String channel = channelField.getText();
        if (channel == null || channel.trim().isEmpty()) return;

        String password = JOptionPane.showInputDialog(chatContent, "Enter channel password (optional):");
        if (!channel.startsWith("#")) {
            channel = "#" + channel;
        }
        if (onChannelJoin != null) {
            onChannelJoin.join(getCurrentBuffer().getNetworkId(), channel, password);
        }
    }

    public void renameChannel(BufferKey oldKey, String newName) {
        BufferKey newKey = BufferKey.of(oldKey.getNetworkId(), newName);
        if (!channelPanes.containsKey(oldKey) || channelPanes.containsKey(newKey)) {
            return;
        }
        int index = getBuffers().indexOf(oldKey);
        if (index == -1) {
            return;
        }
        String oldTitle = titleOf(oldKey);
        synchronized (channelPanes) {
            renameKeyInPlace(channelPanes, oldKey, newKey);
        }
        renameKeyInPlace(unreadMessages, oldKey, newKey);
        renameKeyInPlace(channelTopics, oldKey.folded(), newKey.folded());
        tabbedPane.setTitleAt(index, titleOf(newKey));
        renameBufferDropdownItem(oldTitle, titleOf(newKey));
        if (oldKey.equals(focusedChannel)) {
            focusedChannel = newKey;
        }
        refreshDesktopChannels();
    }

    public void renameChannel(String oldName, String newName) {
        renameChannel(BufferKey.swiftIrc(oldName), newName);
    }

    static <K, V> void renameKeyInPlace(Map<K, V> map, K oldKey, K newKey) {
        if (!map.containsKey(oldKey) || map.containsKey(newKey)) {
            return;
        }
        LinkedHashMap<K, V> rebuilt = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : map.entrySet()) {
            rebuilt.put(entry.getKey().equals(oldKey) ? newKey : entry.getKey(), entry.getValue());
        }
        map.clear();
        map.putAll(rebuilt);
    }

    private void renameBufferDropdownItem(String oldName, String newName) {
        int itemIndex = -1;
        for (int i = 0; i < bufferDropdown.getItemCount(); i++) {
            if (oldName.equals(bufferDropdown.getItemAt(i))) {
                itemIndex = i;
                break;
            }
        }
        if (itemIndex == -1) {
            return;
        }
        int selected = bufferDropdown.getSelectedIndex();
        ActionListener[] listeners = bufferDropdown.getActionListeners();
        for (ActionListener listener : listeners) {
            bufferDropdown.removeActionListener(listener);
        }
        try {
            bufferDropdown.removeItemAt(itemIndex);
            bufferDropdown.insertItemAt(newName, itemIndex);
            if (selected >= 0 && selected < bufferDropdown.getItemCount()) {
                bufferDropdown.setSelectedIndex(selected);
            }
        } finally {
            for (ActionListener listener : listeners) {
                bufferDropdown.addActionListener(listener);
            }
        }
    }

    private void promptRemoveChannel() {
        BufferKey channel = getCurrentBuffer();
        if (!SYSTEM_TAB.equals(channel.getName())) {
            int result = JOptionPane.showConfirmDialog(chatContent, "Close " + titleOf(channel) + "?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (result == JOptionPane.YES_OPTION && onChannelLeave != null) {
                onChannelLeave.accept(channel);
            }
        }
    }

    private void actionPerformed(ActionEvent e) {
        int idx = bufferDropdown.getSelectedIndex();
        List<BufferKey> buffers = getBuffers();
        if (idx >= 0 && idx < buffers.size()) {
            setFocusedChannel(buffers.get(idx));
        }
        hideAllPreviews();
    }

    /** Names a network; retitles its buffers when the name changes. EDT only. */
    public void setNetworkName(String networkId, String name) {
        String previous = networkNames.put(networkId, name);
        if (name.equals(previous) || tabbedPane == null) return;
        List<BufferKey> buffers = getBuffers();
        for (int i = 0; i < buffers.size(); i++) {
            BufferKey key = buffers.get(i);
            if (!key.getNetworkId().equals(networkId)) continue;
            String oldTitle = key.getName() + " (" + (previous != null ? previous : networkId) + ")";
            tabbedPane.setTitleAt(i, titleOf(key));
            renameBufferDropdownItem(oldTitle, titleOf(key));
        }
        refreshDesktopChannels();
    }

    /** Records a network's link state for the pop-out tree. EDT only. */
    public void setNetworkConnected(String networkId, boolean connected) {
        networkConnected.put(networkId, connected);
        refreshDesktopChannels();
    }

    /** Drops a removed network's name and state. Its buffers go via removeNetworkBuffers. */
    public void forgetNetwork(String networkId) {
        if (NetworkConfig.SWIFTIRC_ID.equals(networkId)) return;
        networkNames.remove(networkId);
        networkConnected.remove(networkId);
        refreshDesktopChannels();
    }


    public static class ChannelPane extends JTextPane {
        private final IrcConfig config;
        private ArrayList<String> messageLog;
        private static final Pattern UNDERLINE = Pattern.compile("\u001F([^\u001F\u000F]+)[\u001F\u000F]?");
        private static final Pattern ITALIC = Pattern.compile("\u001D([^\u001D\u000F]+)[\u001D\u000F]?");
        private static final Pattern BOLD = Pattern.compile("\u0002([^\u0002\u000F]+)[\u0002\u000F]?");
        private static final Pattern COLORS = Pattern.compile("(?:\u0003\\d\\d?(?:,\\d\\d?)?\\s*)?\u000F?\u0003(\\d\\d?)(?:,(\\d\\d?))?([^\u0003\u000F]+)\u000F?");
        private final PreviewManager previewManager;
        /** Set when a render couldn't scroll because the pane was off screen. */
        private boolean scrollPending;

        ChannelPane(Font font, IrcConfig config, OkHttpClient okHttpClient) {
            this.config = config;
            this.previewManager = new PreviewManager(this, okHttpClient);
            setContentType("text/html");
            setFont(font);
            setEditable(false);
            messageLog = new ArrayList<>();
            // A pane rendered while hidden (background tab, closed sidebar) has no layout to
            // scroll, so catch up the first time it is shown.
            addHierarchyListener(e -> {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing() && scrollPending) {
                    SwingUtilities.invokeLater(this::scrollToBottom);
                }
            });

            addHyperlinkListener(e -> {
                if (e.getURL() != null) {
                    String url = e.getURL().toString();
                    if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                        try {
                            LinkBrowser.browse(e.getURL().toURI().toString());
                        } catch (Exception ignored) {
                        }
                    } else if (e.getEventType() == HyperlinkEvent.EventType.ENTERED) {
                        if (this.config.hoverPreviewImages() && previewManager.isImageUrl(url)) {
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

        void appendMessage(IrcMessage message, IrcConfig config) {
            String formattedMessage = formatPanelMessage(message, config);
            messageLog.add(formattedMessage);
            if (messageLog.size() > config.getMaxScrollback()) {
                messageLog.remove(0);
            }
            SwingUtilities.invokeLater(this::render);
        }

        void applyFont(Font font) {
            setFont(font);
            render();
        }

        void applyColors() {
            render();
        }

        private void render() {
            Color background = config.chatBackgroundColor() != null ? config.chatBackgroundColor() : ColorScheme.DARKER_GRAY_COLOR;
            Color text = config.chatTextColor() != null ? config.chatTextColor() : ColorScheme.LIGHT_GRAY_COLOR;
            setBackground(background);
            setText("<html><body style='color:" + ColorUtil.toHexColor(text)
                    + "; background-color:" + ColorUtil.toHexColor(background) + ";"
                    + fontStyle() + "'>" + String.join("", messageLog) + "</body></html>");
            setCaretPosition(getDocument().getLength());
            scrollPending = true;
            SwingUtilities.invokeLater(this::scrollToBottom);
        }

        // The HTML document ignores the component font, so carry it into the body style.
        private String fontStyle() {
            Font font = getFont();
            if (font == null) return "";
            return " font-family:" + font.getFamily() + "; font-size:" + font.getSize() + "pt;";
        }

        /**
         * Shows the newest line at the left edge. The caret alone isn't enough: it sits at the
         * end of the last line, so a long unbreakable link would leave the view scrolled right.
         */
        private void scrollToBottom() {
            JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
            if (viewport == null || !isShowing()) return;
            scrollPending = false;
            int bottom = Math.max(0, viewport.getViewSize().height - viewport.getExtentSize().height);
            viewport.setViewPosition(new Point(0, bottom));
        }

        private String formatPanelMessage(IrcMessage message, IrcConfig config) {
            if (message.getType() == IrcMessage.MessageType.HISTORY_SEPARATOR) {
                return "<div style='color: #808080; text-align: center;'>--- Begin of chat ---</div>";
            }
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
                    // Inherit the body's configured text colour so a settings change recolours scrollback.
                    color = null;
            }
            String sender = escapeHtml4(message.getDisplaySender());
            if (config.colorizedNicks()) {
                String senderColor = htmlColorById(nickColorId(message.getSender()));
                sender = String.format("<font style=\"color:%s\">%s</font>", senderColor, sender);
            }
            String open = color == null ? "<div>" : String.format("<div style='color: %s'>", color);
            return String.format("%s%s%s: %s</div>", open, timeStamp, sender, formatMessage(message.getContent()));
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
                color_matcher.appendReplacement(sb, Matcher.quoteReplacement(
                        colorSpan(color_matcher.group(1), color_matcher.group(2), color_matcher.group(3))));
            }
            color_matcher.appendTail(sb);
            return IrcFormatting.stripCodes(sb.toString());
        }

        static String htmlColorById(String id) {
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
                    return extendedColorById(id);
            }
        }

        /**
         * Palette nicks are colored from: the classic 02-13 plus the extended palette's vivid and
         * pastel rows 64-87. Near-black rows (16-39) and dark grays (88-93) are excluded as
         * unreadable on the dark panel. Shared with the nicklist dropdown so a nick looks the
         * same in both places.
         */
        private static final String[] NICK_COLOR_IDS = {
                "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13",
                "64", "65", "66", "67", "68", "69", "70", "71", "72", "73", "74", "75",
                "76", "77", "78", "79", "80", "81", "82", "83", "84", "85", "86", "87"
        };

        /**
         * Deterministic palette code for a nick. Uses floorMod rather than abs: for a nick whose
         * hashCode is Integer.MIN_VALUE, Math.abs returns Integer.MIN_VALUE again and the index
         * goes negative.
         */
        static String nickColorId(String nick) {
            return NICK_COLOR_IDS[Math.floorMod(nick.hashCode(), NICK_COLOR_IDS.length)];
        }

        /**
         * https://modern.ircdocs.horse/formatting.html
         */
        private static final String[] EXTENDED_COLORS = {
            "#470000", // 16
            "#472100", // 17
            "#474700", // 18
            "#324700", // 19
            "#004700", // 20
            "#00472C", // 21
            "#004747", // 22
            "#002747", // 23
            "#000047", // 24
            "#2E0047", // 25
            "#470047", // 26
            "#47002A", // 27
            "#740000", // 28
            "#743A00", // 29
            "#747400", // 30
            "#517400", // 31
            "#007400", // 32
            "#007449", // 33
            "#007474", // 34
            "#004074", // 35
            "#000074", // 36
            "#4B0074", // 37
            "#740074", // 38
            "#740045", // 39
            "#B50000", // 40
            "#B56300", // 41
            "#B5B500", // 42
            "#7DB500", // 43
            "#00B500", // 44
            "#00B571", // 45
            "#00B5B5", // 46
            "#0063B5", // 47
            "#0000B5", // 48
            "#7500B5", // 49
            "#B500B5", // 50
            "#B5006B", // 51
            "#FF0000", // 52
            "#FF8C00", // 53
            "#FFFF00", // 54
            "#B2FF00", // 55
            "#00FF00", // 56
            "#00FFA0", // 57
            "#00FFFF", // 58
            "#008CFF", // 59
            "#0000FF", // 60
            "#A500FF", // 61
            "#FF00FF", // 62
            "#FF0098", // 63
            "#FF5959", // 64
            "#FFB459", // 65
            "#FFFF71", // 66
            "#CFFF60", // 67
            "#6FFF6F", // 68
            "#65FFC9", // 69
            "#6DFFFF", // 70
            "#59B4FF", // 71
            "#5959FF", // 72
            "#C459FF", // 73
            "#FF66FF", // 74
            "#FF59BC", // 75
            "#FF9C9C", // 76
            "#FFD39C", // 77
            "#FFFF9C", // 78
            "#E2FF9C", // 79
            "#9CFF9C", // 80
            "#9CFFDB", // 81
            "#9CFFFF", // 82
            "#9CD3FF", // 83
            "#9C9CFF", // 84
            "#DC9CFF", // 85
            "#FF9CFF", // 86
            "#FF94D3", // 87
            "#000000", // 88
            "#131313", // 89
            "#282828", // 90
            "#363636", // 91
            "#4D4D4D", // 92
            "#656565", // 93
            "#818181", // 94
            "#9F9F9F", // 95
            "#BCBCBC", // 96
            "#E2E2E2", // 97
            "#FFFFFF", // 98
        };

        private static String extendedColorById(String id) {
            try {
                int code = Integer.parseInt(id);
                if (code >= 16 && code <= 98) {
                    return EXTENDED_COLORS[code - 16];
                }
            } catch (NumberFormatException ignored) {
                // Not a numeric code - fall through to the default below.
            }
            return "black";
        }

        static String colorSpan(String fgId, String bgId, String text) {
            StringBuilder style = new StringBuilder("color:").append(htmlColorById(fgId));
            String content = text;
            if (hasPaletteColor(bgId)) {
                style.append(";background-color:").append(htmlColorById(bgId));
                content = text.replace(" ", "&nbsp;");
            }
            return "<span style=\"" + style + "\">" + content + "</span>";
        }

        private static boolean hasPaletteColor(String id) {
            if (id == null) {
                return false;
            }
            try {
                int code = Integer.parseInt(id);
                return code >= 0 && code <= 98;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        public void clear() {
            this.setText("");
            messageLog = new ArrayList<>();
        }

        public void cancelPreviewManager() {
            this.previewManager.cancelPreviewManager();
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

        // Tab would otherwise move focus out of the input box.
        inputField.setFocusTraversalKeysEnabled(false);
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "completeNext");
        actionMap.put("completeNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                completeInput(true);
            }
        });
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK), "completePrevious");
        actionMap.put("completePrevious", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                completeInput(false);
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "historyPrevious");
        actionMap.put("historyPrevious", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                recallHistory(inputHistory.previous(inputField.getText()));
            }
        });
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "historyNext");
        actionMap.put("historyNext", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                recallHistory(inputHistory.next());
            }
        });
    }

    private void completeInput(boolean forward) {
        BufferKey current = getCurrentBuffer();
        String channel = current.getName();
        List<String> nicks = new ArrayList<>();
        for (ChannelUserList.Entry entry : channelUserSnapshots.getOrDefault(current.folded(), Collections.emptyList())) {
            nicks.add(entry.getNick());
        }
        // A PM buffer has no roster; the one other person is the buffer itself.
        if (!channel.startsWith("#") && !SYSTEM_TAB.equals(channel)) {
            nicks.add(channel);
        }
        List<String> channels = new ArrayList<>();
        for (BufferKey key : getBuffers()) {
            if (key.getNetworkId().equals(current.getNetworkId()) && key.getName().startsWith("#")) {
                channels.add(key.getName());
            }
        }
        TabCompleter.Result result = tabCompleter.complete(
                inputField.getText(), inputField.getCaretPosition(), nicks, channels, forward);
        if (result != null) {
            inputField.setText(result.text);
            inputField.setCaretPosition(result.caret);
        }
    }

    private void recallHistory(String text) {
        if (text == null) {
            return;
        }
        inputField.setText(text);
        inputField.setCaretPosition(text.length());
    }
}
