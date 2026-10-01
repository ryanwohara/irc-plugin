package com.irc;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.function.IntConsumer;

/**
 * WeeChat-style buffer number keys, active while focus is inside {@code root}: Alt+1 to Alt+9 jump
 * straight to that buffer and Alt+0 to the tenth, and Alt+J followed by two digits jumps to any
 * buffer (Alt+J 1 1 for the eleventh).
 *
 * A dispatcher rather than key bindings, because the KEY_TYPED that follows a handled key press
 * must be swallowed too: otherwise the digits after Alt+J land in the input box, as does the
 * character macOS produces for Option+digit.
 */
final class ChannelNumberKeys implements KeyEventDispatcher {
    private final Component root;
    private final IntConsumer jump;
    /** Digits typed since Alt+J; null when no jump is pending. */
    private String digits;
    private boolean swallowTyped;

    ChannelNumberKeys(Component root, IntConsumer jump) {
        this.root = root;
        this.jump = jump;
    }

    void install() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this);
    }

    void uninstall() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getComponent() == null || !SwingUtilities.isDescendingFrom(e.getComponent(), root)) {
            digits = null;
            swallowTyped = false;
            return false;
        }
        if (e.getID() == KeyEvent.KEY_TYPED) return swallowTyped;
        if (e.getID() != KeyEvent.KEY_PRESSED) return false;

        swallowTyped = false;
        int code = e.getKeyCode();
        int digit = digitOf(code);
        if (digits != null) {
            if (isModifier(code)) return false;
            if (digit >= 0) {
                digits += digit;
                swallowTyped = true;
                if (digits.length() == 2) {
                    int number = Integer.parseInt(digits);
                    digits = null;
                    jump.accept(number);
                }
                return true;
            }
            digits = null;
            if (code == KeyEvent.VK_ESCAPE) return true;
        }
        if (!isPlainAlt(e)) return false;
        if (digit >= 0) {
            swallowTyped = true;
            jump.accept(digit == 0 ? 10 : digit);
            return true;
        }
        if (code == KeyEvent.VK_J) {
            digits = "";
            swallowTyped = true;
            return true;
        }
        return false;
    }

    private static int digitOf(int code) {
        if (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) return code - KeyEvent.VK_0;
        if (code >= KeyEvent.VK_NUMPAD0 && code <= KeyEvent.VK_NUMPAD9) return code - KeyEvent.VK_NUMPAD0;
        return -1;
    }

    private static boolean isModifier(int code) {
        return code == KeyEvent.VK_ALT || code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL
                || code == KeyEvent.VK_META || code == KeyEvent.VK_ALT_GRAPH;
    }

    /** Alt alone: AltGr arrives as Ctrl+Alt on Windows and types characters, so it must not match. */
    private static boolean isPlainAlt(KeyEvent e) {
        int mods = e.getModifiersEx();
        int others = KeyEvent.CTRL_DOWN_MASK | KeyEvent.META_DOWN_MASK | KeyEvent.ALT_GRAPH_DOWN_MASK;
        return (mods & KeyEvent.ALT_DOWN_MASK) != 0 && (mods & others) == 0;
    }
}
