package com.irc;

import java.util.ArrayList;
import java.util.List;

/**
 * Readline-style history for the side-panel input box, shared across channels.
 *
 * Entries are ordered oldest-first. {@code cursor == entries.size()} means "live" (not
 * browsing); {@code 0 <= cursor < size} points at the entry currently shown. When browsing
 * begins, the unsent text is stashed as {@link #draft} so stepping past the newest entry can
 * restore it.
 */
class InputHistory {

    private final int capacity;
    private final List<String> entries = new ArrayList<>();
    private int cursor;
    private String draft = "";

    InputHistory(int capacity) {
        this.capacity = capacity;
    }

    /** Records a sent line and resets browsing. Ignores blank lines and consecutive duplicates. */
    void add(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        if (entries.isEmpty() || !entries.get(entries.size() - 1).equals(line)) {
            entries.add(line);
            if (entries.size() > capacity) {
                entries.remove(0);
            }
        }
        cursor = entries.size();
        draft = "";
    }

    /**
     * Up: returns the previous (older) entry. On the first press from "live" it stashes
     * {@code currentText} as the draft and returns the newest entry; further presses walk
     * older and clamp at the oldest. Returns {@code null} when there is no history.
     */
    String previous(String currentText) {
        if (entries.isEmpty()) {
            return null;
        }
        if (cursor == entries.size()) {
            draft = currentText == null ? "" : currentText;
            cursor = entries.size() - 1;
        } else if (cursor > 0) {
            cursor--;
        }
        return entries.get(cursor);
    }

    /**
     * Down: returns the next (newer) entry. Stepping past the newest exits browsing and returns
     * the stashed draft. Returns {@code null} when not currently browsing.
     */
    String next() {
        if (cursor >= entries.size()) {
            return null;
        }
        if (cursor < entries.size() - 1) {
            cursor++;
            return entries.get(cursor);
        }
        cursor = entries.size();
        return draft;
    }
}
