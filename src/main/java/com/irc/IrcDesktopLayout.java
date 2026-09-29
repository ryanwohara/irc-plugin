package com.irc;

import javax.swing.*;
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
import java.util.function.Predicate;

/** Expanded navigation around the existing chat components, with no separate IRC state. */
final class IrcDesktopLayout extends JPanel {
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
    private final JPanel conversation = new JPanel(new BorderLayout());
    private final JPanel composer = new JPanel(new BorderLayout());
    private final Consumer<String> query;
    private final Consumer<String> whois;
    private List<String> channelNames = Collections.emptyList();
    private boolean synchronizing;

    IrcDesktopLayout(String server, Predicate<String> unread, Consumer<String> select,
                     Consumer<String> query, Consumer<String> whois, Runnable join,
                     Runnable leave, Runnable browse, Runnable reconnect, Runnable dock) {
        super(new BorderLayout(0, 1));
        this.query = query;
        this.whois = whois;
        setBackground(HEADER);
        root = new DefaultMutableTreeNode(server);
        treeModel = new DefaultTreeModel(root);
        channels = new JTree(treeModel);
        channels.setName("ircChannels");
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
                String name = node.getUserObject().toString();
                setIcon(null);
                setBackgroundNonSelectionColor(BACKGROUND);
                setBackgroundSelectionColor(new Color(49, 68, 86));
                boolean hasUnread = node.getAllowsChildren() == false && unread.test(name);
                setForeground(hasUnread ? ACCENT : TEXT);
                setFont(tree.getFont().deriveFont(hasUnread ? Font.BOLD : Font.PLAIN));
                if (hasUnread) setText(name + "  •");
                return this;
            }
        });
        channels.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) channels.getLastSelectedPathComponent();
            if (!synchronizing && node != null && !node.getAllowsChildren()) {
                select.accept(node.getUserObject().toString());
            }
        });

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
                setForeground(entry.getPrefix().isEmpty() ? TEXT : ACCENT);
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
        toolbar.add(button("Connect", "Reconnect to IRC", reconnect));
        toolbar.add(button("Join…", "Join a channel", join));
        toolbar.add(button("Leave", "Close the selected conversation", leave));
        toolbar.add(button("Channels…", "Browse the server's channel list", browse));
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(HEADER);
        top.add(toolbar, BorderLayout.WEST);
        JButton dockButton = button("Dock ↗", "Return to the RuneLite sidebar", dock);
        JPanel dockArea = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        dockArea.setBackground(HEADER);
        dockArea.add(dockButton);
        top.add(dockArea, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        conversation.setMinimumSize(new Dimension(220, 100));
        composer.setBackground(HEADER);
        composer.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        conversation.add(composer, BorderLayout.SOUTH);
        JPanel chat = new JPanel(new BorderLayout());
        chat.add(channelHeading, BorderLayout.NORTH);
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
        JLabel hint = heading("Enter to send  ·  ↑ / ↓ input history  ·  Double-click a nick to message");
        hint.setFont(hint.getFont().deriveFont(11f));
        hint.setForeground(MUTED);
        add(hint, BorderLayout.SOUTH);
    }

    void attachChat(JTabbedPane chat, JTextField input) {
        conversation.add(chat, BorderLayout.CENTER);
        composer.add(input, BorderLayout.CENTER);
    }

    void updateChannels(List<String> names, String selected) {
        synchronizing = true;
        try {
            if (!channelNames.equals(names)) {
                channelNames = new ArrayList<>(names);
                root.removeAllChildren();
                DefaultMutableTreeNode rooms = new DefaultMutableTreeNode("Channels");
                DefaultMutableTreeNode privateChats = new DefaultMutableTreeNode("Private chats");
                for (String name : names) {
                    DefaultMutableTreeNode node = new DefaultMutableTreeNode(name, false);
                    if ("System".equals(name)) root.add(node);
                    else if (name.startsWith("#") || name.startsWith("&")) rooms.add(node);
                    else privateChats.add(node);
                }
                root.add(rooms);
                root.add(privateChats);
                treeModel.reload();
                for (int row = 0; row < channels.getRowCount(); row++) channels.expandRow(row);
            }
            java.util.Enumeration<?> nodes = root.depthFirstEnumeration();
            while (nodes.hasMoreElements()) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
                if (!node.getAllowsChildren() && node.getUserObject().equals(selected)) {
                    TreePath path = new TreePath(node.getPath());
                    if (!path.equals(channels.getSelectionPath())) {
                        channels.setSelectionPath(path);
                        channels.scrollPathToVisible(path);
                    }
                    break;
                }
            }
            channelHeading.setText(selected);
            // nodeChanged, not repaint: unread rows render wider (bold + marker), and the tree
            // caches row widths, so a plain repaint clips them.
            java.util.Enumeration<?> leaves = root.depthFirstEnumeration();
            while (leaves.hasMoreElements()) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) leaves.nextElement();
                if (!node.getAllowsChildren()) treeModel.nodeChanged(node);
            }
        } finally {
            synchronizing = false;
        }
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
