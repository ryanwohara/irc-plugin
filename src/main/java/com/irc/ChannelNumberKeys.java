package com.irc;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.ContainerEvent;
import java.awt.event.ContainerListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.function.IntConsumer;

/**
 * WeeChat-style buffer number keys, active while focus is inside {@code root}: Alt+1 to Alt+9 jump
 * straight to that buffer and Alt+0 to the tenth, and Alt+J followed by two digits jumps to any
 * buffer (Alt+J 1 1 for the eleventh).
 *
 * A key listener on every component under {@code root} rather than key bindings, because the
 * KEY_TYPED that follows a handled key press must be swallowed too: otherwise the digits after
 * Alt+J land in the input box, as does the character macOS produces for Option+digit. Consuming
 * the event in a listener stops Swing's key bindings, text entry included.
 */
final class ChannelNumberKeys implements KeyListener, ContainerListener {
    private final Component root;
    /** A pending Alt+J doesn't survive focus moving elsewhere. */
    private final FocusListener focusListener = new FocusAdapter() {
        @Override
        public void focusLost(FocusEvent e) { reset(); }
    };
    private final IntConsumer jump;
    /** Digits typed since Alt+J; null when no jump is pending. */
    private String digits;
    private boolean swallowTyped;

    ChannelNumberKeys(Component root, IntConsumer jump) {
        this.root = root;
        this.jump = jump;
    }

    void install() {
        attach(root);
    }

    void uninstall() {
        detach(root);
        reset();
    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (handle(e)) e.consume();
    }

    @Override
    public void keyTyped(KeyEvent e) {
        if (handle(e)) e.consume();
    }

    @Override
    public void keyReleased(KeyEvent e) {}

    @Override
    public void componentAdded(ContainerEvent e) { attach(e.getChild()); }

    @Override
    public void componentRemoved(ContainerEvent e) { detach(e.getChild()); }

    private void attach(Component component) {
        // Removing first keeps a component that is re-added from getting a second listener.
        detach(component);
        component.addKeyListener(this);
        component.addFocusListener(focusListener);
        if (component instanceof Container) {
            Container container = (Container) component;
            container.addContainerListener(this);
            for (Component child : container.getComponents()) attach(child);
        }
    }

    private void detach(Component component) {
        component.removeKeyListener(this);
        component.removeFocusListener(focusListener);
        if (component instanceof Container) {
            Container container = (Container) component;
            container.removeContainerListener(this);
            for (Component child : container.getComponents()) detach(child);
        }
    }

    private void reset() {
        digits = null;
        swallowTyped = false;
    }

    /** Returns true when the event belongs to a channel jump and must not reach the component. */
    boolean handle(KeyEvent e) {
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
