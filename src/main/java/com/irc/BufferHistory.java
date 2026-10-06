package com.irc;

import java.util.ArrayList;
import java.util.List;

/**
 * The buffers the user has looked at, for Alt+/ (back to the last one) and
 * {@code Alt+<} / {@code Alt+>} (walk the history).
 *
 * Entries are ordered by when they were last shown, oldest first, each buffer once. Walking the
 * history moves {@link #cursor} without reordering; switching buffers any other way moves the
 * buffer being left and the one being opened to the end, so {@code Alt+<} always steps back to the
 * buffer shown just before.
 */
class BufferHistory {

    private final int capacity;
    private final List<BufferKey> entries = new ArrayList<>();
    /** Index of the shown buffer in {@link #entries}; -1 when it isn't there. */
    private int cursor = -1;
    private BufferKey current;
    /** The buffer shown before {@link #current}, however the user got there. */
    private BufferKey previous;

    BufferHistory(int capacity) {
        this.capacity = capacity;
    }

    /** Records that {@code key} is now shown. Showing the same buffer again changes nothing. */
    void visit(BufferKey key) {
        if (key == null || key.equals(current)) return;
        if (current != null) previous = current;
        current = key;
        if (cursor >= 0 && entries.get(cursor).equals(key)) return;
        if (cursor >= 0 && cursor < entries.size() - 1) entries.add(entries.remove(cursor));
        entries.remove(key);
        entries.add(key);
        if (entries.size() > capacity) entries.remove(0);
        cursor = entries.size() - 1;
    }

    /** The buffer shown before the current one, or null when there is none. */
    BufferKey last() {
        return previous;
    }

    /** Steps back through the history; returns the buffer to show, or null at the oldest. */
    BufferKey back() {
        if (cursor <= 0) return null;
        return entries.get(--cursor);
    }

    /** Steps forward through the history; returns the buffer to show, or null at the newest. */
    BufferKey forward() {
        if (cursor < 0 || cursor >= entries.size() - 1) return null;
        return entries.get(++cursor);
    }

    /** Forgets a closed buffer. */
    void remove(BufferKey key) {
        int index = entries.indexOf(key);
        if (index >= 0) {
            entries.remove(index);
            if (index == cursor) cursor = -1;
            else if (index < cursor) cursor--;
        }
        if (key.equals(previous)) previous = null;
        if (key.equals(current)) current = null;
    }

    /** Follows a buffer renamed in place, such as a query whose nick changed. */
    void rename(BufferKey oldKey, BufferKey newKey) {
        int index = entries.indexOf(oldKey);
        if (index >= 0) entries.set(index, newKey);
        if (oldKey.equals(previous)) previous = newKey;
        if (oldKey.equals(current)) current = newKey;
    }
}
