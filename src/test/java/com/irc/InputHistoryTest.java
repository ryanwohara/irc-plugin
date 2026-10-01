package com.irc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Readline-style input history for the side-panel input box. Up walks back through recently
 * sent lines (stashing the unsent draft on the first press), Down walks forward and restores
 * the draft past the newest entry. Capacity-bounded; consecutive duplicates are collapsed.
 */
public class InputHistoryTest {

    @Test
    public void emptyHistoryReturnsNull() {
        InputHistory history = new InputHistory(20);
        assertNull(history.previous(""));
        assertNull(history.next());
    }

    @Test
    public void upFromLiveReturnsNewestEntry() {
        InputHistory history = new InputHistory(20);
        history.add("first");
        history.add("second");
        assertEquals("second", history.previous("draft"));
    }

    @Test
    public void repeatedUpWalksBackAndClampsAtOldest() {
        InputHistory history = new InputHistory(20);
        history.add("a");
        history.add("b");
        history.add("c");
        assertEquals("c", history.previous(""));
        assertEquals("b", history.previous(""));
        assertEquals("a", history.previous(""));
        assertEquals("a", history.previous("")); // clamped at oldest
    }

    @Test
    public void downWalksForwardAndRestoresStashedDraft() {
        InputHistory history = new InputHistory(20);
        history.add("a");
        history.add("b");
        assertEquals("b", history.previous("my draft"));
        assertEquals("a", history.previous("my draft"));
        assertEquals("b", history.next());
        assertEquals("my draft", history.next()); // past newest -> stashed draft restored
        assertNull(history.next());                // now live again
    }

    @Test
    public void downWithoutBrowsingReturnsNull() {
        InputHistory history = new InputHistory(20);
        history.add("a");
        assertNull(history.next());
    }

    @Test
    public void capacityDropsOldest() {
        InputHistory history = new InputHistory(2);
        history.add("a");
        history.add("b");
        history.add("c"); // "a" evicted
        assertEquals("c", history.previous(""));
        assertEquals("b", history.previous(""));
        assertEquals("b", history.previous("")); // "a" gone -> clamp at "b"
    }

    @Test
    public void skipsConsecutiveDuplicate() {
        InputHistory history = new InputHistory(20);
        history.add("same");
        history.add("same"); // collapsed
        assertEquals("same", history.previous(""));
        assertEquals("same", history.previous("")); // only one entry
    }

    @Test
    public void keepsNonConsecutiveDuplicate() {
        InputHistory history = new InputHistory(20);
        history.add("a");
        history.add("b");
        history.add("a"); // not consecutive -> kept
        assertEquals("a", history.previous(""));
        assertEquals("b", history.previous(""));
        assertEquals("a", history.previous(""));
    }

    @Test
    public void addResetsBrowsing() {
        InputHistory history = new InputHistory(20);
        history.add("a");
        history.add("b");
        assertEquals("b", history.previous("draft"));
        history.add("c");                       // sending resets browse state
        assertNull(history.next());             // no longer browsing
        assertEquals("c", history.previous("")); // fresh browse from newest
    }

    @Test
    public void ignoresBlankAndNullLines() {
        InputHistory history = new InputHistory(20);
        history.add("");
        history.add(null);
        assertNull(history.previous(""));
    }
}
