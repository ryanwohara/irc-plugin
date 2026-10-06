package com.irc;

import org.junit.Before;
import org.junit.Test;

import javax.swing.*;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ChannelNumberKeysTest {
    private final JPanel root = new JPanel();
    private final JTextField input = new JTextField();
    private final JTextField outside = new JTextField();
    private final List<Integer> jumps = new ArrayList<>();
    private int markAllReads;
    private final List<String> navigations = new ArrayList<>();
    private ChannelNumberKeys keys;

    @Before
    public void setUp() {
        root.add(input);
        keys = new ChannelNumberKeys(root, jumps::add, () -> markAllReads++,
                () -> navigations.add("last"), () -> navigations.add("back"), () -> navigations.add("forward"),
                delta -> navigations.add("step " + delta));
        keys.install();
    }

    @Test
    public void altDigitJumpsAndSwallowsItsTypedCharacter() {
        assertTrue(press(input, KeyEvent.VK_3, KeyEvent.ALT_DOWN_MASK));
        assertTrue(type(input, '3', KeyEvent.ALT_DOWN_MASK));
        assertEquals(Collections.singletonList(3), jumps);

        assertFalse(press(input, KeyEvent.VK_A, 0));
        assertFalse(type(input, 'a', 0));
    }

    @Test
    public void altZeroJumpsToTheTenth() {
        assertTrue(press(input, KeyEvent.VK_0, KeyEvent.ALT_DOWN_MASK));
        assertTrue(type(input, '0', KeyEvent.ALT_DOWN_MASK));
        assertEquals(Collections.singletonList(10), jumps);
    }

    @Test
    public void altJThenTwoDigitsJumpsToThatNumber() {
        assertTrue(press(input, KeyEvent.VK_J, KeyEvent.ALT_DOWN_MASK));
        assertTrue(type(input, 'j', KeyEvent.ALT_DOWN_MASK));
        assertFalse(press(input, KeyEvent.VK_ALT, 0));
        assertTrue(press(input, KeyEvent.VK_1, 0));
        assertTrue(type(input, '1', 0));
        assertTrue(jumps.isEmpty());
        assertTrue(press(input, KeyEvent.VK_NUMPAD0, 0));
        assertTrue(type(input, '0', 0));
        assertEquals(Collections.singletonList(10), jumps);

        // The jump is over, so digits type normally again.
        assertFalse(press(input, KeyEvent.VK_5, 0));
        assertFalse(type(input, '5', 0));
    }

    @Test
    public void digitsHeldWithAltCountTowardsAPendingJump() {
        press(input, KeyEvent.VK_J, KeyEvent.ALT_DOWN_MASK);
        press(input, KeyEvent.VK_1, KeyEvent.ALT_DOWN_MASK);
        press(input, KeyEvent.VK_2, KeyEvent.ALT_DOWN_MASK);
        assertEquals(Collections.singletonList(12), jumps);
    }

    @Test
    public void otherKeysCancelAPendingJump() {
        press(input, KeyEvent.VK_J, KeyEvent.ALT_DOWN_MASK);
        assertTrue(press(input, KeyEvent.VK_ESCAPE, 0));
        assertFalse(press(input, KeyEvent.VK_1, 0));

        press(input, KeyEvent.VK_J, KeyEvent.ALT_DOWN_MASK);
        press(input, KeyEvent.VK_1, 0);
        assertFalse(press(input, KeyEvent.VK_X, 0));
        assertFalse(type(input, 'x', 0));
        assertFalse(press(input, KeyEvent.VK_2, 0));
        assertTrue(jumps.isEmpty());
    }

    @Test
    public void ignoresKeysOutsideThePanelAndAltGr() {
        assertFalse(press(outside, KeyEvent.VK_1, KeyEvent.ALT_DOWN_MASK));
        assertFalse(press(input, KeyEvent.VK_1, KeyEvent.ALT_DOWN_MASK | KeyEvent.CTRL_DOWN_MASK));
        assertFalse(press(input, KeyEvent.VK_1, KeyEvent.ALT_GRAPH_DOWN_MASK));
        assertTrue(jumps.isEmpty());
    }

    @Test
    public void losingFocusCancelsAPendingJump() {
        press(input, KeyEvent.VK_J, KeyEvent.ALT_DOWN_MASK);
        for (java.awt.event.FocusListener listener : input.getFocusListeners()) {
            listener.focusLost(new FocusEvent(input, FocusEvent.FOCUS_LOST, false, outside));
        }
        assertFalse(press(input, KeyEvent.VK_1, 0));
        assertFalse(press(input, KeyEvent.VK_2, 0));
        assertTrue(jumps.isEmpty());
    }

    @Test
    public void followsComponentsAddedAndRemovedAfterInstall() {
        JPanel tab = new JPanel();
        JTextField later = new JTextField();
        tab.add(later);
        root.add(tab);
        assertTrue(press(later, KeyEvent.VK_2, KeyEvent.ALT_DOWN_MASK));
        assertEquals(Collections.singletonList(2), jumps);

        root.remove(tab);
        assertFalse(press(later, KeyEvent.VK_2, KeyEvent.ALT_DOWN_MASK));
        root.add(tab);
        assertEquals(1, later.getKeyListeners().length);

        keys.uninstall();
        assertFalse(press(input, KeyEvent.VK_3, KeyEvent.ALT_DOWN_MASK));
        assertEquals(Collections.singletonList(2), jumps);
    }

    @Test
    public void altHMarksEverythingReadAndSwallowsItsTypedCharacter() {
        assertTrue(press(input, KeyEvent.VK_H, KeyEvent.ALT_DOWN_MASK));
        assertTrue(type(input, 'h', KeyEvent.ALT_DOWN_MASK));
        assertEquals(1, markAllReads);
        assertTrue(jumps.isEmpty());
    }

    @Test
    public void plainHAndAltGrHStillType() {
        assertFalse(press(input, KeyEvent.VK_H, 0));
        assertFalse(type(input, 'h', 0));
        assertFalse(press(input, KeyEvent.VK_H, KeyEvent.ALT_DOWN_MASK | KeyEvent.CTRL_DOWN_MASK));
        assertFalse(press(input, KeyEvent.VK_H, KeyEvent.ALT_GRAPH_DOWN_MASK));
        assertEquals(0, markAllReads);
    }

    @Test
    public void altHCancelsAPendingJump() {
        press(input, KeyEvent.VK_J, KeyEvent.ALT_DOWN_MASK);
        assertTrue(press(input, KeyEvent.VK_H, KeyEvent.ALT_DOWN_MASK));
        assertEquals(1, markAllReads);
        assertFalse("the jump is over, so digits type again", press(input, KeyEvent.VK_1, 0));
        assertTrue(jumps.isEmpty());
    }

    @Test
    public void altSlashGoesToTheLastBufferAndSwallowsItsTypedCharacter() {
        assertTrue(press(input, KeyEvent.VK_SLASH, KeyEvent.ALT_DOWN_MASK));
        assertTrue(type(input, '/', KeyEvent.ALT_DOWN_MASK));
        assertTrue(press(input, KeyEvent.VK_DIVIDE, KeyEvent.ALT_DOWN_MASK));
        assertEquals(java.util.Arrays.asList("last", "last"), navigations);

        assertFalse("a plain slash still starts a command", press(input, KeyEvent.VK_SLASH, 0));
        assertFalse(type(input, '/', 0));
    }

    @Test
    public void altLessAndGreaterWalkTheBufferHistoryAndSwallowTheirCharacters() {
        int altShift = KeyEvent.ALT_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK;
        assertTrue("US: Shift+comma", press(input, KeyEvent.VK_COMMA, altShift));
        assertTrue(type(input, '<', altShift));
        assertTrue("US: Shift+period", press(input, KeyEvent.VK_PERIOD, altShift));
        assertTrue(type(input, '>', altShift));
        assertTrue("European: the < key", press(input, KeyEvent.VK_LESS, KeyEvent.ALT_DOWN_MASK));
        assertTrue("European: Shift+<", press(input, KeyEvent.VK_LESS, altShift));
        assertTrue("going by the char", press(input, KeyEvent.VK_UNDEFINED, '<', KeyEvent.ALT_DOWN_MASK));
        assertEquals(java.util.Arrays.asList("back", "forward", "back", "forward", "back"), navigations);

        assertTrue("Alt+, aliases Alt+<", press(input, KeyEvent.VK_COMMA, KeyEvent.ALT_DOWN_MASK));
        assertTrue(type(input, ',', KeyEvent.ALT_DOWN_MASK));
        assertTrue("Alt+. aliases Alt+>", press(input, KeyEvent.VK_PERIOD, KeyEvent.ALT_DOWN_MASK));
        assertTrue(type(input, '.', KeyEvent.ALT_DOWN_MASK));
        assertEquals(7, navigations.size());
        assertEquals("back", navigations.get(5));
        assertEquals("forward", navigations.get(6));

        assertFalse("a plain comma still types", press(input, KeyEvent.VK_COMMA, 0));
        assertFalse("Alt+arrows are left to the text field", press(input, KeyEvent.VK_LEFT, KeyEvent.ALT_DOWN_MASK));
        assertFalse("a plain < still types", press(input, KeyEvent.VK_COMMA, KeyEvent.SHIFT_DOWN_MASK));
        assertFalse(type(input, '<', KeyEvent.SHIFT_DOWN_MASK));
        assertEquals(7, navigations.size());
    }

    @Test
    public void altUpAndDownStepThroughTheBuffers() {
        assertTrue(press(input, KeyEvent.VK_UP, KeyEvent.ALT_DOWN_MASK));
        assertTrue(press(input, KeyEvent.VK_DOWN, KeyEvent.ALT_DOWN_MASK));
        assertTrue(press(input, KeyEvent.VK_KP_DOWN, KeyEvent.ALT_DOWN_MASK));
        assertEquals(java.util.Arrays.asList("step -1", "step 1", "step 1"), navigations);

        assertFalse("plain Up still recalls input history", press(input, KeyEvent.VK_UP, 0));
        assertFalse(press(input, KeyEvent.VK_DOWN, KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK));
        assertFalse("nothing to swallow after an arrow", type(input, 'x', 0));
        assertEquals(3, navigations.size());
    }

    private boolean press(JComponent source, int code, int mods) {
        return press(source, code, KeyEvent.CHAR_UNDEFINED, mods);
    }

    private boolean press(JComponent source, int code, char c, int mods) {
        return fire(source, new KeyEvent(source, KeyEvent.KEY_PRESSED, 0, mods, code, c));
    }

    private boolean type(JComponent source, char c, int mods) {
        return fire(source, new KeyEvent(source, KeyEvent.KEY_TYPED, 0, mods, KeyEvent.VK_UNDEFINED, c));
    }

    /** Delivers the event to the component's listeners, as Swing would; true when one consumed it. */
    private static boolean fire(JComponent source, KeyEvent e) {
        for (KeyListener listener : source.getKeyListeners()) {
            if (e.getID() == KeyEvent.KEY_PRESSED) listener.keyPressed(e);
            else if (e.getID() == KeyEvent.KEY_TYPED) listener.keyTyped(e);
        }
        return e.isConsumed();
    }
}
