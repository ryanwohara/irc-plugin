package com.irc;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The channel browser's data layer: the full LIST result, the filtered view of it, and the
 * mapping from a table row back to its entry.
 *
 * This is deliberately separate from {@link ChannelListDialog} so it can be unit-tested without
 * constructing a Window, which throws HeadlessException on a headless machine.
 *
 * Topics are stripped of IRC formatting codes once, up front - when the entries are set, not when
 * a cell is painted or a filter is applied. The filter then matches the same stripped text the
 * user is looking at, rather than raw codes they cannot see, without re-running the stripper per
 * row per keystroke: at the 20,000-row cap that would be 20,000 regex passes for every character
 * typed into the filter box, all of it on the EDT.
 */
public class ChannelListTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {"Channel", "Users", "Topic"};

    private List<Row> allRows = Collections.emptyList();
    private List<Row> shownRows = Collections.emptyList();
    private String filter = "";

    /**
     * An entry plus everything derived from it that the table and the filter would otherwise
     * recompute on every repaint and every keystroke.
     *
     * The precomputation lives here rather than on {@link ChannelListEntry} because that class is
     * immutable and shared with the client's LIST snapshot, which has no interest in display
     * concerns.
     */
    private static final class Row {
        private final ChannelListEntry entry;
        private final String topic;
        private final String nameLower;
        private final String topicLower;

        private Row(ChannelListEntry entry) {
            this.entry = entry;
            this.topic = displayTopic(entry);
            String name = entry.getName() != null ? entry.getName() : "";
            this.nameLower = name.toLowerCase(Locale.ROOT);
            this.topicLower = this.topic.toLowerCase(Locale.ROOT);
        }

        /** {@code needle} must already be trimmed, lower-cased and non-empty. */
        private boolean matches(String needle) {
            return nameLower.contains(needle) || topicLower.contains(needle);
        }
    }

    /** Replaces the backing data and reapplies the active filter. */
    public void setEntries(List<ChannelListEntry> entries) {
        List<Row> rows = new ArrayList<>(entries != null ? entries.size() : 0);
        if (entries != null) {
            for (ChannelListEntry entry : entries) {
                rows.add(new Row(entry));
            }
        }
        allRows = rows;
        applyFilter();
    }

    /** Narrows the view. A null or blank filter shows everything. */
    public void setFilter(String filter) {
        this.filter = filter != null ? filter : "";
        applyFilter();
    }

    private void applyFilter() {
        String needle = needle(filter);
        if (needle.isEmpty()) {
            shownRows = allRows;
        } else {
            List<Row> matched = new ArrayList<>();
            for (Row row : allRows) {
                if (row.matches(needle)) {
                    matched.add(row);
                }
            }
            shownRows = matched;
        }
        fireTableDataChanged();
    }

    /** The comparable form of a filter string. Empty means "match everything". */
    private static String needle(String filter) {
        return filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Case-insensitive substring match against the channel name and the stripped topic.
     *
     * Off the hot path - the model filters against precomputed rows - but kept as the single
     * definition of the rule, and testable without a table.
     */
    public static boolean matches(ChannelListEntry entry, String filter) {
        String needle = needle(filter);
        return needle.isEmpty() || new Row(entry).matches(needle);
    }

    /** The topic as the user sees it: formatting codes removed, never null. */
    static String displayTopic(ChannelListEntry entry) {
        String topic = entry.getTopic();
        if (topic == null) {
            return "";
        }
        String stripped = IrcFormatting.stripCodes(topic);
        return stripped != null ? stripped : "";
    }

    /** The entry behind a model row. Callers must convert view rows first. */
    public ChannelListEntry getEntryAt(int rowIndex) {
        return shownRows.get(rowIndex).entry;
    }

    public int getTotalCount() {
        return allRows.size();
    }

    public int getShownCount() {
        return shownRows.size();
    }

    @Override
    public int getRowCount() {
        return shownRows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    /**
     * Users is Integer so TableRowSorter sorts it numerically. As String, 9 would outrank 412.
     */
    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return columnIndex == 1 ? Integer.class : String.class;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        Row row = shownRows.get(rowIndex);
        switch (columnIndex) {
            case 0:
                return row.entry.getName();
            case 1:
                return row.entry.getUserCount();
            case 2:
                return row.topic;
            default:
                return "";
        }
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }
}
