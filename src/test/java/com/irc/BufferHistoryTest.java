package com.irc;

import org.junit.Test;

import static org.junit.Assert.*;

public class BufferHistoryTest {
    private static final BufferKey A = BufferKey.swiftIrc("#a");
    private static final BufferKey B = BufferKey.swiftIrc("#b");
    private static final BufferKey C = BufferKey.swiftIrc("#c");
    private static final BufferKey D = BufferKey.swiftIrc("#d");

    private final BufferHistory history = new BufferHistory(50);

    /** Shows what back()/forward() returned, as the panel does by focusing it. */
    private BufferKey show(BufferKey key) {
        history.visit(key);
        return key;
    }

    @Test
    public void lastIsTheBufferShownBeforeAndTogglesBetweenTwo() {
        assertNull(history.last());
        history.visit(A);
        assertNull(history.last());
        history.visit(B);
        assertEquals(A, history.last());
        history.visit(history.last());
        assertEquals(B, history.last());
        history.visit(history.last());
        assertEquals(A, history.last());
    }

    @Test
    public void revisitingTheShownBufferChangesNothing() {
        history.visit(A);
        history.visit(B);
        history.visit(B);
        assertEquals(A, history.last());
    }

    @Test
    public void backAndForwardWalkTheHistory() {
        history.visit(A);
        history.visit(B);
        history.visit(C);
        assertNull(history.forward());
        assertEquals(B, show(history.back()));
        assertEquals(A, show(history.back()));
        assertNull(history.back());
        assertEquals(B, show(history.forward()));
        assertEquals(C, show(history.forward()));
        assertNull(history.forward());
    }

    @Test
    public void walkingBackUpdatesLast() {
        history.visit(A);
        history.visit(B);
        history.visit(C);
        show(history.back());
        assertEquals(C, history.last());
    }

    @Test
    public void aBufferIsListedOnceAtItsLatestVisit() {
        history.visit(A);
        history.visit(B);
        history.visit(C);
        history.visit(A);
        assertEquals(C, show(history.back()));
        assertEquals(B, show(history.back()));
        assertNull(history.back());
    }

    @Test
    public void switchingAwayWhileWalkingKeepsTheWalkedToBufferNext() {
        history.visit(A);
        history.visit(B);
        history.visit(C);
        show(history.back());
        history.visit(D);
        assertEquals("back goes to the buffer just left", B, show(history.back()));
        assertEquals(C, show(history.back()));
        assertEquals(A, show(history.back()));
        assertNull(history.back());
    }

    @Test
    public void closedBuffersAreForgotten() {
        history.visit(A);
        history.visit(B);
        history.visit(C);
        history.remove(B);
        assertEquals(A, show(history.back()));
        assertNull(history.back());

        history.visit(C);
        history.remove(C);
        history.visit(D);
        assertEquals("closing the shown buffer leaves last alone", A, history.last());
        assertEquals(A, history.back());

        history.remove(A);
        assertNull(history.last());
    }

    @Test
    public void followsRenames() {
        BufferKey bob = BufferKey.swiftIrc("bob");
        BufferKey bob2 = BufferKey.swiftIrc("bob2");
        history.visit(bob);
        history.visit(A);
        history.rename(bob, bob2);
        assertEquals(bob2, history.last());
        assertEquals(bob2, show(history.back()));
        assertEquals(A, history.last());
    }

    @Test
    public void dropsTheOldestPastCapacity() {
        BufferHistory small = new BufferHistory(2);
        small.visit(A);
        small.visit(B);
        small.visit(C);
        assertEquals(B, small.back());
        assertNull(small.back());
    }
}
