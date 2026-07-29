package com.irc;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The channel browser: the server's LIST result as a sortable, filterable table.
 *
 * Non-modal and reused across requests, so a repeated /list refreshes this window instead of
 * stacking new ones. It stays open after a join - browsing and joining are usually iterative.
 *
 * All data logic lives in {@link ChannelListTableModel}; this class is wiring only.
 */
public class ChannelListDialog extends JDialog {
    private final ChannelListTableModel model = new ChannelListTableModel();
    private final JTable table = new JTable(model);
    private final JTextField filterField = new JTextField();
    private final JLabel statusLabel = new JLabel();
    private final BiConsumer<String, String> onJoin;

    /** The query behind the current contents, replayed by Refresh. */
    private String query = "";
    private boolean truncated = false;

    public ChannelListDialog(Window owner, BiConsumer<String, String> onJoin, Consumer<String> onRefresh) {
        super(owner, "Channel List");
        this.onJoin = onJoin;
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(false);
        table.setFillsViewportHeight(true);

        TableRowSorter<ChannelListTableModel> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
        sorter.setSortKeys(Collections.singletonList(
                new RowSorter.SortKey(1, SortOrder.DESCENDING)));

        table.getColumnModel().getColumn(0).setPreferredWidth(180);
        table.getColumnModel().getColumn(1).setPreferredWidth(60);
        table.getColumnModel().getColumn(2).setPreferredWidth(440);
        table.getColumnModel().getColumn(2).setCellRenderer(new TopicRenderer());

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    joinSelected();
                }
            }
        });
        table.getInputMap(JTable.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "joinSelected");
        table.getActionMap().put("joinSelected", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                joinSelected();
            }
        });

        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refilter();
            }
        });

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        top.add(new JLabel("Filter:"), BorderLayout.WEST);
        top.add(filterField, BorderLayout.CENTER);

        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> {
            if (onRefresh != null) {
                onRefresh.accept(query);
            }
        });
        JButton joinButton = new JButton("Join");
        joinButton.addActionListener(e -> joinSelected());

        JPanel buttons = new JPanel();
        buttons.add(joinButton);
        buttons.add(refreshButton);

        statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(statusLabel, BorderLayout.CENTER);
        bottom.add(buttons, BorderLayout.EAST);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(700, 450));
        pack();
        setLocationRelativeTo(owner);
    }

    /** Replaces the contents. {@code query} is what Refresh will replay. */
    public void setEntries(List<ChannelListEntry> entries, String query, boolean truncated) {
        this.query = query != null ? query : "";
        this.truncated = truncated;
        model.setEntries(entries);
        updateStatus();
    }

    public void showDialog() {
        setVisible(true);
        toFront();
        filterField.requestFocusInWindow();
    }

    private void refilter() {
        model.setFilter(filterField.getText());
        updateStatus();
    }

    private void updateStatus() {
        StringBuilder status = new StringBuilder();
        status.append(model.getTotalCount()).append(" channels");
        if (model.getShownCount() != model.getTotalCount()) {
            status.append(" · ").append(model.getShownCount()).append(" shown");
        }
        if (truncated) {
            status.append(" · truncated");
        }
        statusLabel.setText(status.toString());
    }

    /** Joins the selected channel with no key; a keyed channel falls into the 475 prompt. */
    private void joinSelected() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0 || onJoin == null) {
            return;
        }
        ChannelListEntry entry = model.getEntryAt(table.convertRowIndexToModel(viewRow));
        onJoin.accept(entry.getName(), "");
    }

    /**
     * Puts the full topic in a tooltip. Most real topics are longer than the Topic column is wide
     * and render as "Ask about quest hel...", with no other way to read the rest.
     *
     * Package-private rather than private so the tooltip can be asserted without constructing this
     * dialog, which needs a {@link Window} and so throws on a headless machine.
     */
    static class TopicRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component rendered = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            String text = value != null ? value.toString() : "";
            // Null, not "": an empty tooltip still pops an empty box open on hover.
            setToolTipText(text.isEmpty() ? null : text);
            return rendered;
        }
    }
}
