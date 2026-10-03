package com.irc;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lists the built-in SwiftIRC network and the user's extra networks in the user's order, edits
 * the extras and reorders all of them.
 */
final class NetworksDialog extends JDialog {
    interface Callbacks {
        /** Persists the extra networks (SwiftIRC excluded), in display order. */
        void save(List<NetworkConfig> extras);

        /** Persists the order of every network, SwiftIRC included, as ids. */
        void saveOrder(List<String> ids);

        boolean isConnected(String networkId);

        void setConnected(String networkId, boolean connected);
    }

    private NetworkConfig builtIn;
    private final List<NetworkConfig> extras = new ArrayList<>();
    /** The saved network order; see {@link OrderStore#applyNetworkOrder}. */
    private final List<String> order = new ArrayList<>();
    private final Callbacks callbacks;
    private final DefaultListModel<NetworkConfig> model = new DefaultListModel<>();
    private final JList<NetworkConfig> list = new JList<>(model);
    private final JButton edit = button("Edit…", "ircNetworkEdit", this::editSelected);
    private final JButton duplicate = button("Duplicate", "ircNetworkDuplicate", this::duplicateSelected);
    private final JButton remove = button("Remove", "ircNetworkRemove", this::removeSelected);
    private final JButton connect = button("Connect", "ircNetworkConnect", this::toggleConnection);
    private final JButton up = button("Move up", "ircNetworkUp", () -> moveSelected(-1));
    private final JButton down = button("Move down", "ircNetworkDown", () -> moveSelected(1));

    NetworksDialog(Window owner, NetworkConfig builtIn, List<NetworkConfig> extras, List<String> order,
                   Callbacks callbacks) {
        super(owner, "IRC networks", ModalityType.MODELESS);
        this.callbacks = callbacks;
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        list.setName("ircNetworkList");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                          boolean selected, boolean focused) {
                super.getListCellRendererComponent(l, value, index, selected, focused);
                NetworkConfig network = (NetworkConfig) value;
                setText(describe(network, callbacks.isConnected(network.getId())));
                return this;
            }
        });
        list.addListSelectionListener(e -> updateButtons());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(button("Add…", "ircNetworkAdd", this::addNetwork));
        buttons.add(edit);
        buttons.add(duplicate);
        buttons.add(remove);
        buttons.add(connect);
        buttons.add(up);
        buttons.add(down);
        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.NORTH);
        JLabel note = new JLabel("SwiftIRC is set up in RuneLite's Global Chat (IRC) settings.");
        note.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        south.add(note, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout());
        content.add(new JScrollPane(list), BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);

        setNetworks(builtIn, extras, order);
        setSize(640, 320);
        setLocationRelativeTo(owner);
    }

    /** Replaces what the list shows, keeping the selection where it can. */
    void setNetworks(NetworkConfig builtIn, List<NetworkConfig> extras, List<String> order) {
        this.builtIn = builtIn;
        this.extras.clear();
        this.extras.addAll(extras);
        this.order.clear();
        this.order.addAll(order);
        reload();
    }

    void selectNetwork(String id) {
        if (id == null) return;
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).getId().equals(id)) {
                list.setSelectedIndex(i);
                return;
            }
        }
    }

    static String describe(NetworkConfig network, boolean connected) {
        return (connected ? "● " : "○ ") + network.getName() + "   " + network.getHost() + ":" + network.getPort()
                + (network.isEnabled() ? "" : "  (disabled)");
    }

    /** Why {@code candidate} can't be saved, or null when it can. {@code others} may include it. */
    static String validate(NetworkConfig candidate, List<NetworkConfig> others) {
        String name = candidate.getName().trim();
        if (name.isEmpty()) return "Give the network a name.";
        if (candidate.getHost().trim().isEmpty()) return "Enter the server's host name.";
        if (candidate.getPort() < 1 || candidate.getPort() > 65535) return "The port must be between 1 and 65535.";
        if (name.equalsIgnoreCase(NetworkConfig.SWIFTIRC_NAME)) {
            return "The name SwiftIRC is reserved for the built-in network.";
        }
        for (NetworkConfig other : others) {
            if (!other.getId().equals(candidate.getId()) && other.getName().trim().equalsIgnoreCase(name)) {
                return "Another network is already called " + other.getName() + ".";
            }
        }
        return null;
    }

    static NetworkConfig duplicateOf(NetworkConfig source) {
        return source.toBuilder().id(UUID.randomUUID().toString()).name(source.getName() + " (copy)").build();
    }

    private NetworkConfig selected() {
        return list.getSelectedValue();
    }

    private void updateButtons() {
        NetworkConfig selected = selected();
        boolean extra = selected != null && !selected.isBuiltIn();
        edit.setEnabled(extra);
        duplicate.setEnabled(extra);
        remove.setEnabled(extra);
        connect.setEnabled(selected != null && selected.isEnabled());
        connect.setText(selected != null && callbacks.isConnected(selected.getId()) ? "Disconnect" : "Connect");
        int index = list.getSelectedIndex();
        up.setEnabled(index > 0);
        down.setEnabled(index >= 0 && index < model.size() - 1);
    }

    private void reload() {
        String selectedId = selected() != null ? selected().getId() : null;
        model.clear();
        for (NetworkConfig network : OrderStore.applyNetworkOrder(all(), order)) model.addElement(network);
        selectNetwork(selectedId);
        updateButtons();
    }

    /** SwiftIRC, then the extras in their saved list order. */
    private List<NetworkConfig> all() {
        List<NetworkConfig> all = new ArrayList<>();
        all.add(builtIn);
        all.addAll(extras);
        return all;
    }

    /**
     * Swaps the selected network with its neighbour and saves both the full order and the extras
     * in the same relative order, so the stored list reads the way the dialog shows it.
     */
    private void moveSelected(int step) {
        int from = list.getSelectedIndex();
        int to = from + step;
        if (from < 0 || to < 0 || to >= model.size()) return;
        List<NetworkConfig> shown = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) shown.add(model.get(i));
        NetworkConfig moved = shown.remove(from);
        shown.add(to, moved);
        order.clear();
        extras.clear();
        for (NetworkConfig network : shown) {
            order.add(network.getId());
            if (!network.isBuiltIn()) extras.add(network);
        }
        callbacks.save(new ArrayList<>(extras));
        callbacks.saveOrder(new ArrayList<>(order));
        reload();
        selectNetwork(moved.getId());
    }

    private int indexOf(String id) {
        for (int i = 0; i < extras.size(); i++) {
            if (extras.get(i).getId().equals(id)) return i;
        }
        return -1;
    }

    private void commit(String selectId) {
        callbacks.save(new ArrayList<>(extras));
        reload();
        selectNetwork(selectId);
    }

    private void addNetwork() {
        NetworkConfig fresh = NetworkConfig.builder().id(UUID.randomUUID().toString()).name("").host("").build();
        NetworkConfig edited = editNetwork(fresh, "Add network");
        if (edited != null) {
            extras.add(edited);
            commit(edited.getId());
        }
    }

    private void editSelected() {
        NetworkConfig selected = selected();
        if (selected == null || selected.isBuiltIn()) return;
        NetworkConfig edited = editNetwork(selected, "Edit " + selected.getName());
        if (edited != null) {
            extras.set(indexOf(selected.getId()), edited);
            commit(edited.getId());
        }
    }

    private void duplicateSelected() {
        NetworkConfig selected = selected();
        if (selected == null || selected.isBuiltIn()) return;
        NetworkConfig edited = editNetwork(duplicateOf(selected), "Duplicate " + selected.getName());
        if (edited != null) {
            extras.add(indexOf(selected.getId()) + 1, edited);
            commit(edited.getId());
        }
    }

    private void removeSelected() {
        NetworkConfig selected = selected();
        if (selected == null || selected.isBuiltIn()) return;
        int choice = JOptionPane.showConfirmDialog(this,
                "Remove " + selected.getName() + "? Its buffers will close.", "Remove network",
                JOptionPane.YES_NO_OPTION);
        if (choice != JOptionPane.YES_OPTION) return;
        extras.remove(indexOf(selected.getId()));
        commit(null);
    }

    private void toggleConnection() {
        NetworkConfig selected = selected();
        if (selected == null) return;
        callbacks.setConnected(selected.getId(), !callbacks.isConnected(selected.getId()));
        list.repaint();
        updateButtons();
    }

    /** Shows the form until it validates or is cancelled; null when cancelled. */
    private NetworkConfig editNetwork(NetworkConfig initial, String title) {
        NetworkForm form = new NetworkForm(initial);
        while (true) {
            int choice = JOptionPane.showConfirmDialog(this, form, title,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return null;
            NetworkConfig candidate = form.toConfig();
            List<NetworkConfig> others = new ArrayList<>(extras);
            others.add(0, builtIn);
            String error = validate(candidate, others);
            if (error == null) return candidate;
            JOptionPane.showMessageDialog(this, error, "Network not saved", JOptionPane.WARNING_MESSAGE);
        }
    }

    private static JButton button(String text, String name, Runnable action) {
        JButton button = new JButton(text);
        button.setName(name);
        button.addActionListener(e -> action.run());
        return button;
    }

    /** The add/edit form. Package-private fields so tests can fill it in. */
    static final class NetworkForm extends JPanel {
        private final String id;
        final JTextField name = new JTextField(20);
        final JTextField host = new JTextField(20);
        final JTextField port = new JTextField(6);
        final JCheckBox tls = new JCheckBox("Use TLS");
        final JCheckBox verifyTls = new JCheckBox("Verify TLS certificate");
        final JLabel tlsWarning = new JLabel(
                "Not verified: someone between you and the server could read or change this connection.");
        final JTextField nick = new JTextField(20);
        final JPasswordField serverPassword = new JPasswordField(20);
        final JTextField saslAccount = new JTextField(20);
        final JPasswordField saslPassword = new JPasswordField(20);
        final JTextField autojoin = new JTextField(20);
        final JCheckBox enabled = new JCheckBox("Connect automatically");
        final JLabel zncHint = new JLabel("ZNC: Server password is username/network:password;"
                + " uncheck Verify TLS if your ZNC uses a self-signed certificate");

        NetworkForm(NetworkConfig initial) {
            super(new GridBagLayout());
            id = initial.getId();
            name.setText(initial.getName());
            host.setText(initial.getHost());
            port.setText(String.valueOf(initial.getPort()));
            tls.setSelected(initial.isTls());
            verifyTls.setSelected(initial.isVerifyTls());
            tlsWarning.setForeground(new Color(230, 140, 60));
            tls.addItemListener(e -> updateTlsControls());
            verifyTls.addItemListener(e -> updateTlsControls());
            updateTlsControls();
            nick.setText(initial.getNick());
            nick.setToolTipText("Leave blank to use your Username setting");
            serverPassword.setText(initial.getServerPassword());
            saslAccount.setText(initial.getSaslAccount());
            saslPassword.setText(initial.getSaslPassword());
            autojoin.setText(initial.getAutojoin());
            autojoin.setToolTipText("Comma-separated, e.g. #foo,#bar");
            enabled.setSelected(initial.isEnabled());
            zncHint.setVisible(false);
            JButton zncPreset = new JButton("ZNC preset");
            zncPreset.addActionListener(e -> applyZncPreset());

            int row = 0;
            row = addRow("Name", name, row);
            row = addRow("Host", host, row);
            row = addRow("Port", port, row);
            row = addRow("", tls, row);
            row = addRow("", verifyTls, row);
            row = addRow("", tlsWarning, row);
            row = addRow("Nick", nick, row);
            row = addRow("Server password", serverPassword, row);
            row = addRow("", zncHint, row);
            row = addRow("SASL account", saslAccount, row);
            row = addRow("SASL password", saslPassword, row);
            row = addRow("Autojoin", autojoin, row);
            row = addRow("", enabled, row);
            addRow("", zncPreset, row);
        }

        /** Verification only means something with TLS on; warn while it is switched off. */
        private void updateTlsControls() {
            verifyTls.setEnabled(tls.isSelected());
            tlsWarning.setVisible(tls.isSelected() && !verifyTls.isSelected());
            refit();
        }

        /**
         * Resizes the dialog showing this form. It is sized once when it opens, so a row revealed
         * afterwards would otherwise push its OK/Cancel buttons out of view.
         */
        private void refit() {
            revalidate();
            Window window = SwingUtilities.getWindowAncestor(this);
            if (window != null) window.pack();
        }

        void applyZncPreset() {
            tls.setSelected(true);
            zncHint.setVisible(true);
            refit();
        }

        NetworkConfig toConfig() {
            int parsedPort;
            try {
                parsedPort = Integer.parseInt(port.getText().trim());
            } catch (NumberFormatException e) {
                parsedPort = -1;
            }
            return NetworkConfig.builder()
                    .id(id)
                    .name(name.getText().trim())
                    .host(host.getText().trim())
                    .port(parsedPort)
                    .tls(tls.isSelected())
                    .verifyTls(verifyTls.isSelected())
                    .nick(nick.getText().trim())
                    .serverPassword(new String(serverPassword.getPassword()))
                    .saslAccount(saslAccount.getText().trim())
                    .saslPassword(new String(saslPassword.getPassword()))
                    .autojoin(autojoin.getText().trim())
                    .enabled(enabled.isSelected())
                    .build();
        }

        private int addRow(String label, JComponent field, int row) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = row;
            c.insets = new Insets(2, 4, 2, 4);
            c.anchor = GridBagConstraints.WEST;
            add(new JLabel(label), c);
            c.gridx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            add(field, c);
            return row + 1;
        }
    }
}
