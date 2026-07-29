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
 * Topics are stripped of IRC formatting codes once, up front. The filter then matches the same
 * stripped text the user is looking at, rather than raw codes they cannot see.
 */
public class ChannelListTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {"Channel", "Users", "Topic"};

    private List<ChannelListEntry> allEntries = Collections.emptyList();
    private List<ChannelListEntry> shownEntries = Collections.emptyList();
    private String filter = "";

    /** Replaces the backing data and reapplies the active filter. */
    public void setEntries(List<ChannelListEntry> entries) {
        allEntries = entries != null ? entries : Collections.<ChannelListEntry>emptyList();
        applyFilter();
    }

    /** Narrows the view. A null or blank filter shows everything. */
    public void setFilter(String filter) {
        this.filter = filter != null ? filter : "";
        applyFilter();
    }

    private void applyFilter() {
        List<ChannelListEntry> matched = new ArrayList<>();
        for (ChannelListEntry entry : allEntries) {
            if (matches(entry, filter)) {
                matched.add(entry);
            }
        }
        shownEntries = matched;
        fireTableDataChanged();
    }

    /** Case-insensitive substring match against the channel name and the stripped topic. */
    public static boolean matches(ChannelListEntry entry, String filter) {
        if (filter == null || filter.trim().isEmpty()) {
            return true;
        }
        String needle = filter.trim().toLowerCase(Locale.ROOT);
        String name = entry.getName() != null ? entry.getName() : "";
        if (name.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        return displayTopic(entry).toLowerCase(Locale.ROOT).contains(needle);
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
        return shownEntries.get(rowIndex);
    }

    public int getTotalCount() {
        return allEntries.size();
    }

    public int getShownCount() {
        return shownEntries.size();
    }

    @Override
    public int getRowCount() {
        return shownEntries.size();
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
        ChannelListEntry entry = shownEntries.get(rowIndex);
        switch (columnIndex) {
            case 0:
                return entry.getName();
            case 1:
                return entry.getUserCount();
            case 2:
                return displayTopic(entry);
            default:
                return "";
        }
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }
}
