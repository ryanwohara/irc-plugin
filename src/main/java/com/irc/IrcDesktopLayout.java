package com.irc;

import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

import javax.swing.*;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.AttributeSet;
import javax.swing.text.Element;
import javax.swing.text.Position;
import javax.swing.text.View;
import javax.swing.text.html.HTML;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.apache.commons.text.StringEscapeUtils.escapeHtml4;

/** Expanded navigation around the existing chat components, with no separate IRC state. */
final class IrcDesktopLayout extends JPanel {
    /** One network as the tree shows it. */
    static final class NetworkNode {
        final String id;
        final String name;
        final boolean connected;
        final List<String> buffers;

        NetworkNode(String id, String name, boolean connected, List<String> buffers) {
            this.id = id;
            this.name = name;
            this.connected = connected;
            this.buffers = Collections.unmodifiableList(new ArrayList<>(buffers));
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof NetworkNode)) return false;
            NetworkNode other = (NetworkNode) o;
            return id.equals(other.id) && name.equals(other.name) && connected == other.connected
                    && buffers.equals(other.buffers);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(id, name, connected, buffers);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** What the pop-out can ask of a network. {@code edit(null)} opens the network list. */
    interface NetworkActions {
        void reconnect(String networkId);
        void setConnected(String networkId, boolean connected);
        void edit(String networkId);

        NetworkActions NONE = new NetworkActions() {
            @Override public void reconnect(String networkId) { }
            @Override public void setConnected(String networkId, boolean connected) { }
            @Override public void edit(String networkId) { }
        };
    }

    /** Reorders asked for by dragging in the tree. Indexes are final positions after the move. */
    interface Moves {
        void moveNetwork(String networkId, int newIndex);

        /** {@code newIndex} counts within the buffer's group: its network's channels or private chats. */
        void moveBuffer(BufferKey key, int newIndex);

        Moves NONE = new Moves() {
            @Override public void moveNetwork(String networkId, int newIndex) { }
            @Override public void moveBuffer(BufferKey key, int newIndex) { }
        };
    }

    private static final Color BACKGROUND = new Color(30, 33, 38);
    private static final Color HEADER = new Color(40, 44, 50);
    private static final Color TEXT = new Color(218, 222, 229);
    private static final Color MUTED = new Color(155, 163, 176);
    private static final Color ACCENT = new Color(135, 206, 250);
    private final DefaultMutableTreeNode root;
    private final DefaultTreeModel treeModel;
    private final JTree channels;
    private final DefaultListModel<ChannelUserList.Entry> userModel = new DefaultListModel<>();
    private final JList<ChannelUserList.Entry> users = new JList<>(userModel);
    private final JLabel usersHeading = heading("USERS");
    private final JLabel channelHeading = heading("System");
    private final JLabel topic = heading("");
    /** Holds the topic at its full width; a long one scrolls sideways instead of being cut off. */
    private final JScrollPane topicScroll = new JScrollPane(topic,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    private final JPanel conversation = new JPanel(new BorderLayout());
    private final JPanel composer = new JPanel(new BorderLayout());
    private final Consumer<String> query;
    private final Consumer<String> whois;
    private List<NetworkNode> networks = Collections.emptyList();
    /** Buffers as the tree shows them; the numbers Alt+digit and Alt+J jump to. */
    private List<BufferKey> channelOrder = Collections.emptyList();
    private BufferKey selectedKey = BufferKey.swiftIrc("System");
    private final NetworkActions networkActions;
    private final JToggleButton channelsToggle = new JToggleButton("Channel list", true);
    private final JToggleButton usersToggle = new JToggleButton("User list", true);
    private JTextField input;
    private boolean synchronizing;
    private Moves moves = Moves.NONE;

    IrcDesktopLayout(Predicate<BufferKey> unread, Consumer<BufferKey> select,
                     Consumer<String> query, Consumer<String> whois, Runnable join,
                     Runnable leave, Runnable browse, NetworkActions networkActions, Runnable dock,
                     JComboBox<String> fontSelector, JComboBox<Integer> fontSizeSelector,
                     Function<String, Color> nickColor) {
        super(new BorderLayout(0, 1));
        channelHeading.setName("ircChannelHeading");
        this.networkActions = networkActions;
        this.query = query;
        this.whois = whois;
        setBackground(HEADER);
        root = new DefaultMutableTreeNode("Networks");
        treeModel = new DefaultTreeModel(root);
        channels = new JTree(treeModel);
        channels.setName("ircChannels");
        channels.setRootVisible(false);
        channels.setShowsRootHandles(true);
        channels.setRowHeight(27);
        channels.setBackground(BACKGROUND);
        channels.setForeground(TEXT);
        channels.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
        channels.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        channels.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                    boolean expanded, boolean leaf, int row, boolean focused) {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focused);
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
                Object user = node.getUserObject();
                setIcon(null);
                setBackgroundNonSelectionColor(BACKGROUND);
                setBackgroundSelectionColor(new Color(49, 68, 86));
                if (user instanceof NetworkNode) {
                    NetworkNode network = (NetworkNode) user;
                    setText((network.connected ? "\u25cf " : "\u25cb ") + network.name + (network.connected ? "" : "  (disconnected)"));
                    setForeground(network.connected ? TEXT : MUTED);
                    setFont(tree.getFont().deriveFont(Font.BOLD));
                    return this;
                }
                if (user instanceof BufferKey) {
                    BufferKey key = (BufferKey) user;
                    boolean hasUnread = unread.test(key);
                    boolean online = isConnected(key.getNetworkId());
                    setForeground(!online ? MUTED : hasUnread ? ACCENT : TEXT);
                    setFont(tree.getFont().deriveFont(hasUnread ? Font.BOLD : Font.PLAIN));
                    setText((channelOrder.indexOf(key) + 1) + ". " + key.getName() + (hasUnread ? "  \u2022" : ""));
                    return this;
                }
                setForeground(TEXT);
                setFont(tree.getFont().deriveFont(Font.PLAIN));
                return this;
            }
        });
        channels.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) channels.getLastSelectedPathComponent();
            if (!synchronizing && node != null && node.getUserObject() instanceof BufferKey) {
                select.accept((BufferKey) node.getUserObject());
            }
        });
        // Clicking a channel moves focus to the input so the user can type straight away;
        // keyboard selection leaves focus on the tree so arrowing through channels still works.
        channels.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { showNetworkMenu(e); }
            @Override public void mouseReleased(MouseEvent e) {
                if (showNetworkMenu(e)) return;
                if (!SwingUtilities.isLeftMouseButton(e) || input == null) return;
                int row = channels.getClosestRowForLocation(e.getX(), e.getY());
                Rectangle bounds = channels.getRowBounds(row);
                if (bounds == null || e.getY() < bounds.y || e.getY() >= bounds.y + bounds.height) return;
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) channels.getPathForRow(row).getLastPathComponent();
                if (node.getUserObject() instanceof BufferKey) input.requestFocusInWindow();
            }
        });

        channels.setDragEnabled(true);
        channels.setDropMode(DropMode.INSERT);
        channels.setTransferHandler(new ReorderHandler());

        users.setName("ircUsers");
        users.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        users.setFixedCellHeight(26);
        users.setBackground(BACKGROUND);
        users.setForeground(TEXT);
        users.setSelectionBackground(new Color(49, 68, 86));
        users.setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 4));
        users.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                ChannelUserList.Entry entry = (ChannelUserList.Entry) value;
                setText(entry.getPrefix() + entry.getNick());
                // Match the nick's colour in chat; null when colourised nicks are turned off.
                Color color = nickColor.apply(entry.getNick());
                setForeground(color != null ? color : entry.getPrefix().isEmpty() ? TEXT : ACCENT);
                return this;
            }
        });
        users.setToolTipText("Double-click to message. Right-click for user actions.");
        users.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { showUserMenu(e); }
            @Override public void mouseReleased(MouseEvent e) { showUserMenu(e); }
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && selectUserAt(e)) {
                    query.accept(users.getSelectedValue().getNick());
                }
            }
        });
        users.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "query");
        users.getActionMap().put("query", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (users.getSelectedValue() != null) query.accept(users.getSelectedValue().getNick());
            }
        });

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        toolbar.setBackground(HEADER);
        JButton reconnectButton = button("", "Reconnect the selected network", () -> networkActions.reconnect(selectedKey.getNetworkId()));
        reconnectButton.setName("ircReconnect");
        try {
            reconnectButton.setIcon(new ImageIcon(ImageUtil.loadImageResource(IrcDesktopLayout.class, "reload.png")));
        } catch (Exception ignored) {
            reconnectButton.setText("Reconnect");
        }
        toolbar.add(reconnectButton);
        toolbar.add(button("Join…", "Join a channel", join));
        toolbar.add(button("Leave", "Close the selected conversation", leave));
        toolbar.add(button("Channels…", "Browse the server's channel list", browse));
        JButton networksButton = button("Networks…", "Add, edit or remove IRC networks", () -> networkActions.edit(null));
        networksButton.setName("ircNetworks");
        toolbar.add(networksButton);
        fontSelector.setToolTipText("Chat font");
        fontSelector.setFocusable(false);
        toolbar.add(fontSelector);
        fontSizeSelector.setToolTipText("Chat font size");
        fontSizeSelector.setFocusable(false);
        toolbar.add(fontSizeSelector);
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(HEADER);
        top.add(toolbar, BorderLayout.WEST);
        JButton dockButton = button("Dock ↗", "Return to the RuneLite sidebar", dock);
        JPanel dockArea = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        dockArea.setBackground(HEADER);
        dockArea.add(channelsToggle);
        dockArea.add(usersToggle);
        dockArea.add(dockButton);
        top.add(dockArea, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        conversation.setMinimumSize(new Dimension(220, 100));
        composer.setBackground(HEADER);
        composer.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        conversation.add(composer, BorderLayout.SOUTH);
        JPanel chat = new JPanel(new BorderLayout());
        JPanel chatHeader = new JPanel(new BorderLayout());
        chatHeader.setBackground(HEADER);
        chatHeader.add(channelHeading, BorderLayout.WEST);
        // Fills the rest of the row. A long topic scrolls sideways (the wheel scrolls it too, as there
        // is no vertical bar) and is shown whole on hover.
        topic.setName("ircTopic");
        topic.setForeground(MUTED);
        topic.setBorder(BorderFactory.createEmptyBorder(9, 0, 9, 12));
        topicScroll.setName("ircTopicScroll");
        topicScroll.setBorder(BorderFactory.createEmptyBorder());
        topicScroll.setViewportBorder(null);
        topicScroll.getViewport().setBackground(HEADER);
        topicScroll.setBackground(HEADER);
        topicScroll.setMinimumSize(new Dimension(0, 0));
        JScrollBar topicBar = topicScroll.getHorizontalScrollBar();
        topicBar.setPreferredSize(new Dimension(0, 6));
        topicBar.setUnitIncrement(16);
        // There is nothing to scroll vertically here, so the wheel always scrolls sideways. Done
        // explicitly: the default handler only goes sideways once the vertical bar is hidden.
        topicScroll.setWheelScrollingEnabled(false);
        topicScroll.addMouseWheelListener(e ->
                topicBar.setValue(topicBar.getValue() + e.getUnitsToScroll() * topicBar.getUnitIncrement()));
        // Topics are set by other users: show any markup as text rather than rendering it. A topic
        // with links is shown as HTML we build ourselves from the escaped text (see topicHtml).
        topic.putClientProperty("html.disable", Boolean.TRUE);
        topic.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                topic.setCursor(topicLinkAt(e.getPoint()) != null
                        ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        });
        topic.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                String url = topicLinkAt(e.getPoint());
                if (url != null) LinkBrowser.browse(url);
            }
        });
        chatHeader.add(topicScroll, BorderLayout.CENTER);
        chat.add(chatHeader, BorderLayout.NORTH);
        chat.add(conversation, BorderLayout.CENTER);
        JPanel left = section(heading("NETWORK"), channels);
        JPanel right = section(usersHeading, users);
        left.setMinimumSize(new Dimension(100, 100));
        left.setPreferredSize(new Dimension(175, 400));
        right.setMinimumSize(new Dimension(100, 100));
        right.setPreferredSize(new Dimension(150, 400));
        JSplitPane chatAndUsers = split(chat, right, 1.0);
        JSplitPane all = split(left, chatAndUsers, 0.0);
        add(all, BorderLayout.CENTER);
        configureToggle(channelsToggle, "ircToggleChannels", "Show or hide the channel list", all, left);
        configureToggle(usersToggle, "ircToggleUsers", "Show or hide the user list", chatAndUsers, right);
        add(hints("Enter to send", "↑ / ↓ input history", "Alt+1–0 or Alt+J ## switch channel",
                "Alt+H mark all read", "Alt+↑ / ↓ next channel", "Alt+/ last channel",
                "Alt+< / > channel history", "Double-click a nick to message"), BorderLayout.SOUTH);
    }

    /** The key tips along the bottom, wrapping between tips when the window is too narrow. */
    private static JPanel hints(String... tips) {
        // The flow's gaps make up the rest of the 9/12 padding the headings have.
        JPanel panel = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 2));
        panel.setName("ircHints");
        panel.setBackground(HEADER);
        panel.setBorder(BorderFactory.createEmptyBorder(7, 6, 7, 6));
        for (int i = 0; i < tips.length; i++) {
            JLabel tip = new JLabel(i < tips.length - 1 ? tips[i] + "  ·" : tips[i]);
            tip.setFont(tip.getFont().deriveFont(11f));
            tip.setForeground(MUTED);
            panel.add(tip);
        }
        return panel;
    }

    void attachChat(JTabbedPane chat, JTextField input) {
        conversation.add(chat, BorderLayout.CENTER);
        this.input = input;
        composer.add(input, BorderLayout.CENTER);
    }

    void setMoves(Moves moves) {
        this.moves = moves != null ? moves : Moves.NONE;
    }

    /** Networks and buffers other than System can be dragged; the group headings cannot. */
    private static boolean isDraggable(TreePath path) {
        if (path == null) return false;
        Object user = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        return user instanceof NetworkNode
                || user instanceof BufferKey && !"System".equals(((BufferKey) user).getName());
    }

    /**
     * Whether {@code dragged} may be inserted at {@code childIndex} under {@code parent}: a network
     * among the networks, a buffer only within the group it is already in. A buffer never moves to
     * another network or between channels and private chats.
     */
    boolean canDrop(TreePath dragged, TreePath parent, int childIndex) {
        if (!isDraggable(dragged) || parent == null || childIndex < 0) return false;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) dragged.getLastPathComponent();
        DefaultMutableTreeNode target = (DefaultMutableTreeNode) parent.getLastPathComponent();
        if (node.getUserObject() instanceof NetworkNode) return target == root;
        return node.getParent() == target;
    }

    /** Applies a legal drop through {@link Moves}; a drop that leaves the node in place does nothing. */
    void drop(TreePath dragged, TreePath parent, int childIndex) {
        if (!canDrop(dragged, parent, childIndex)) return;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) dragged.getLastPathComponent();
        DefaultMutableTreeNode target = (DefaultMutableTreeNode) parent.getLastPathComponent();
        int from = target.getIndex(node);
        if (from < 0) return;
        // The insert index counts the dragged node itself, so a move down lands one earlier.
        int to = Math.min(childIndex > from ? childIndex - 1 : childIndex, target.getChildCount() - 1);
        if (to == from) return;
        Object user = node.getUserObject();
        if (user instanceof NetworkNode) moves.moveNetwork(((NetworkNode) user).id, to);
        else moves.moveBuffer((BufferKey) user, to);
    }

    /** Drag and drop within the tree only; the transferable carries nothing, the path is kept here. */
    private final class ReorderHandler extends TransferHandler {
        private TreePath dragged;

        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        protected java.awt.datatransfer.Transferable createTransferable(JComponent c) {
            TreePath path = channels.getSelectionPath();
            if (!isDraggable(path)) return null;
            dragged = path;
            return new java.awt.datatransfer.StringSelection(String.valueOf(
                    ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject()));
        }

        @Override
        protected void exportDone(JComponent source, java.awt.datatransfer.Transferable data, int action) {
            dragged = null;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            if (!support.isDrop() || dragged == null) return false;
            JTree.DropLocation location = (JTree.DropLocation) support.getDropLocation();
            return canDrop(dragged, location.getPath(), location.getChildIndex());
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            JTree.DropLocation location = (JTree.DropLocation) support.getDropLocation();
            TreePath path = dragged;
            // After the drag finishes: the move rebuilds the tree it is dropping into.
            SwingUtilities.invokeLater(() -> drop(path, location.getPath(), location.getChildIndex()));
            return true;
        }
    }

    List<BufferKey> channelOrder() {
        return new ArrayList<>(channelOrder);
    }

    void updateChannels(List<NetworkNode> nets, BufferKey selected) {
        synchronizing = true;
        try {
            if (!networks.equals(nets)) {
                networks = new ArrayList<>(nets);
                root.removeAllChildren();
                for (NetworkNode network : nets) {
                    DefaultMutableTreeNode networkNode = new DefaultMutableTreeNode(network);
                    DefaultMutableTreeNode rooms = new DefaultMutableTreeNode("Channels");
                    DefaultMutableTreeNode privateChats = new DefaultMutableTreeNode("Private chats");
                    for (String name : network.buffers) {
                        BufferKey key = BufferKey.of(network.id, name);
                        DefaultMutableTreeNode node = new DefaultMutableTreeNode(key, false);
                        if ("System".equals(name)) networkNode.add(node);
                        else if (key.isChannel()) rooms.add(node);
                        else privateChats.add(node);
                    }
                    networkNode.add(rooms);
                    networkNode.add(privateChats);
                    root.add(networkNode);
                }
                List<BufferKey> order = new ArrayList<>();
                java.util.Enumeration<?> nodes = root.preorderEnumeration();
                while (nodes.hasMoreElements()) {
                    Object user = ((DefaultMutableTreeNode) nodes.nextElement()).getUserObject();
                    if (user instanceof BufferKey) order.add((BufferKey) user);
                }
                channelOrder = order;
                treeModel.reload();
                for (int row = 0; row < channels.getRowCount(); row++) channels.expandRow(row);
            }
            selectedKey = selected;
            java.util.Enumeration<?> nodes = root.depthFirstEnumeration();
            while (nodes.hasMoreElements()) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
                if (selected.equals(node.getUserObject())) {
                    TreePath path = new TreePath(node.getPath());
                    if (!path.equals(channels.getSelectionPath())) {
                        channels.setSelectionPath(path);
                        scrollRowIntoView(path);
                    }
                    break;
                }
            }
            channelHeading.setText(headerText(selected));
            // nodeChanged, not repaint: unread rows render wider (bold + marker), and the tree
            // caches row widths, so a plain repaint clips them.
            java.util.Enumeration<?> leaves = root.depthFirstEnumeration();
            while (leaves.hasMoreElements()) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) leaves.nextElement();
                if (node.getUserObject() instanceof BufferKey) treeModel.nodeChanged(node);
            }
        } finally {
            synchronizing = false;
        }
    }

    /**
     * Scrolls the selected row into view vertically only. scrollPathToVisible would also chase
     * the row sideways, and a wide root label ("\u25cb Name  (disconnected)") pulls the whole tree
     * to the right whenever it is rebuilt.
     */
    private void scrollRowIntoView(TreePath path) {
        Rectangle row = channels.getPathBounds(path);
        if (row == null) return;
        Rectangle visible = channels.getVisibleRect();
        channels.scrollRectToVisible(new Rectangle(visible.x, row.y, visible.width, row.height));
    }

    /** "#foo", or "#foo \u00b7 Rizon" once two or more networks are connected. */
    private String headerText(BufferKey selected) {
        long connected = networks.stream().filter(n -> n.connected).count();
        if (connected < 2) return selected.getName();
        for (NetworkNode network : networks) {
            if (network.id.equals(selected.getNetworkId())) return selected.getName() + " \u00b7 " + network.name;
        }
        return selected.getName();
    }

    private boolean isConnected(String networkId) {
        for (NetworkNode network : networks) {
            if (network.id.equals(networkId)) return network.connected;
        }
        return false;
    }

    private boolean showNetworkMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) return false;
        TreePath path = channels.getPathForLocation(e.getX(), e.getY());
        if (path == null) return false;
        Object user = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        if (!(user instanceof NetworkNode)) return false;
        networkMenu((NetworkNode) user).show(channels, e.getX(), e.getY());
        return true;
    }

    JPopupMenu networkMenu(NetworkNode network) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem reconnect = new JMenuItem("Reconnect");
        reconnect.addActionListener(e -> networkActions.reconnect(network.id));
        menu.add(reconnect);
        JMenuItem toggle = new JMenuItem(network.connected ? "Disconnect" : "Connect");
        toggle.addActionListener(e -> networkActions.setConnected(network.id, !network.connected));
        menu.add(toggle);
        JMenuItem edit = new JMenuItem("Edit\u2026");
        edit.addActionListener(e -> networkActions.edit(network.id));
        menu.add(edit);
        return menu;
    }

    /** Shows the selected channel's topic beside its name; "" hides it. */
    void showTopic(String text) {
        String html = topicHtml(text);
        topic.putClientProperty("html.disable", html == null ? Boolean.TRUE : null);
        topic.setText(html != null ? html : text.isEmpty() ? "" : "—  " + text);
        topic.setCursor(Cursor.getDefaultCursor());
        topicScroll.revalidate();
        topicScroll.getViewport().setViewPosition(new Point(0, 0));
        // A tooltip is HTML when it starts with <html>; the leading space keeps it plain text.
        topic.setToolTipText(text.isEmpty() ? null
                : javax.swing.plaf.basic.BasicHTML.isHTMLString(text) ? " " + text : text);
    }

    /**
     * The topic as label HTML with its links clickable, or null when it has no links (it is then
     * shown as plain text). Everything but the links is escaped, so markup in a topic still shows
     * as text. Never wraps: an overlong topic scrolls sideways.
     */
    static String topicHtml(String text) {
        java.util.regex.Matcher links = IrcPanel.VALID_LINK.matcher(text);
        if (!links.find()) return null;
        StringBuilder html = new StringBuilder("<html><body style='white-space:nowrap'>—&nbsp;&nbsp;");
        int last = 0;
        do {
            String url = links.group();
            html.append(escapeHtml4(text.substring(last, links.start())))
                    .append("<a href=\"").append(escapeHtml4(url)).append("\" style='color:")
                    .append(String.format("#%06x", ACCENT.getRGB() & 0xFFFFFF)).append("'>")
                    .append(escapeHtml4(url)).append("</a>");
            last = links.end();
        } while (links.find());
        return html.append(escapeHtml4(text.substring(last))).append("</body></html>").toString();
    }

    /** Where the topic label paints its text, as BasicLabelUI lays it out. */
    Rectangle topicTextBounds() {
        Insets insets = topic.getInsets();
        Rectangle view = new Rectangle(insets.left, insets.top,
                topic.getWidth() - insets.left - insets.right, topic.getHeight() - insets.top - insets.bottom);
        Rectangle icon = new Rectangle();
        Rectangle text = new Rectangle();
        SwingUtilities.layoutCompoundLabel(topic, topic.getFontMetrics(topic.getFont()), topic.getText(), null,
                topic.getVerticalAlignment(), topic.getHorizontalAlignment(),
                topic.getVerticalTextPosition(), topic.getHorizontalTextPosition(),
                view, icon, text, topic.getIconTextGap());
        return text;
    }

    /** The URL of the topic link under {@code point}, or null. */
    String topicLinkAt(Point point) {
        View view = (View) topic.getClientProperty(BasicHTML.propertyKey);
        if (view == null) return null;
        Rectangle text = topicTextBounds();
        if (!text.contains(point)) return null;
        Position.Bias[] bias = new Position.Bias[1];
        int offset = view.viewToModel(point.x, point.y, text, bias);
        Element element = ((javax.swing.text.StyledDocument) view.getDocument()).getCharacterElement(offset);
        Object anchor = element.getAttributes().getAttribute(HTML.Tag.A);
        if (!(anchor instanceof AttributeSet)) return null;
        Object href = ((AttributeSet) anchor).getAttribute(HTML.Attribute.HREF);
        return href != null ? href.toString() : null;
    }

    void updateUsers(String channel, List<ChannelUserList.Entry> entries) {
        ChannelUserList.Entry selected = users.getSelectedValue();
        userModel.clear();
        for (ChannelUserList.Entry entry : entries) {
            userModel.addElement(entry);
            if (selected != null && entry.getNick().equalsIgnoreCase(selected.getNick())) {
                users.setSelectedIndex(userModel.size() - 1);
            }
        }
        usersHeading.setText(channel.startsWith("#") || channel.startsWith("&")
                ? "USERS (" + entries.size() + ")" : "USERS —");
    }

    private boolean selectUserAt(MouseEvent e) {
        int index = users.locationToIndex(e.getPoint());
        if (index < 0 || !users.getCellBounds(index, index).contains(e.getPoint())) return false;
        users.setSelectedIndex(index);
        return true;
    }

    private void showUserMenu(MouseEvent e) {
        if (!e.isPopupTrigger() || !selectUserAt(e)) return;
        String nick = users.getSelectedValue().getNick();
        JPopupMenu menu = new JPopupMenu();
        JMenuItem message = new JMenuItem("Message " + nick);
        message.addActionListener(event -> query.accept(nick));
        menu.add(message);
        JMenuItem info = new JMenuItem("WHOIS " + nick);
        info.addActionListener(event -> whois.accept(nick));
        menu.add(info);
        menu.show(users, e.getX(), e.getY());
    }

    /**
     * Wires a toggle that hides one side of a split pane. A hidden side collapses the divider
     * and gives its room to the chat; showing it again restores the divider where it was.
     */
    private static void configureToggle(JToggleButton toggle, String name, String tooltip,
                                        JSplitPane split, JComponent side) {
        toggle.setName(name);
        toggle.setToolTipText(tooltip);
        toggle.setFocusable(false);
        int[] divider = {-1};
        toggle.addItemListener(e -> {
            boolean show = toggle.isSelected();
            if (show == side.isVisible()) return;
            if (!show) divider[0] = split.getDividerLocation();
            side.setVisible(show);
            if (show && divider[0] >= 0) split.setDividerLocation(divider[0]);
            split.revalidate();
            split.repaint();
        });
    }

    private static JSplitPane split(Component left, Component right, double weight) {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(weight);
        split.setContinuousLayout(true);
        split.setDividerSize(5);
        split.setBorder(BorderFactory.createEmptyBorder());
        return split;
    }

    private static JPanel section(JLabel title, Component content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(title, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setOpaque(true);
        label.setBackground(HEADER);
        label.setForeground(TEXT);
        label.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
        return label;
    }

    private static JButton button(String text, String tooltip, Runnable action) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.addActionListener(e -> action.run());
        return button;
    }
}
