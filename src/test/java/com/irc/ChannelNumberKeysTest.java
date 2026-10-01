package com.irc;

import org.junit.Before;
import org.junit.Test;

import javax.swing.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ChannelNumberKeysTest {
    private final JPanel root = new JPanel();
    private final JTextField input = new JTextField();
    private final JTextField outside = new JTextField();
    private final List<Integer> jumps = new ArrayList<>();
    private ChannelNumberKeys keys;

    @Before
    public void setUp() {
        root.add(input);
        keys = new ChannelNumberKeys(root, jumps::add);
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

        press(input, KeyEvent.VK_J, KeyEvent.ALT_DOWN_MASK);
        assertFalse(press(outside, KeyEvent.VK_1, 0));
        assertFalse(press(input, KeyEvent.VK_2, 0));
        assertTrue(jumps.isEmpty());
    }

    private boolean press(JComponent source, int code, int mods) {
        return keys.dispatchKeyEvent(new KeyEvent(source, KeyEvent.KEY_PRESSED, 0, mods, code, KeyEvent.CHAR_UNDEFINED));
    }

    private boolean type(JComponent source, char c, int mods) {
        return keys.dispatchKeyEvent(new KeyEvent(source, KeyEvent.KEY_TYPED, 0, mods, KeyEvent.VK_UNDEFINED, c));
    }
}
