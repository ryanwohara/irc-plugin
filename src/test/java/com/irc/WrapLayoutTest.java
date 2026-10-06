package com.irc;

import org.junit.Test;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;

import static org.junit.Assert.assertEquals;

public class WrapLayoutTest {
    /** Four 50x10 items, 4px gaps: one row needs 4*50 + 3*4 + 2*4 = 220 wide. */
    private static JPanel items() {
        JPanel panel = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 4));
        for (int i = 0; i < 4; i++) {
            JPanel item = new JPanel();
            item.setPreferredSize(new Dimension(50, 10));
            panel.add(item);
        }
        return panel;
    }

    /** Lays {@code panel} out along the bottom of a parent {@code width} wide, as the pop-out does. */
    private static JPanel inParent(JPanel panel, int width) {
        JPanel parent = new JPanel(new BorderLayout());
        parent.add(panel, BorderLayout.SOUTH);
        parent.setSize(width, 300);
        parent.doLayout();
        return parent;
    }

    @Test
    public void staysOnOneRowWhenThereIsRoom() {
        JPanel panel = items();
        assertEquals(new Dimension(220, 18), panel.getPreferredSize());
        inParent(panel, 400);
        assertEquals(18, panel.getPreferredSize().height);
    }

    @Test
    public void wrapsOntoMoreRowsAsTheParentNarrows() {
        JPanel panel = items();
        inParent(panel, 200);
        assertEquals("two rows of three and one", 32, panel.getPreferredSize().height);
        inParent(panel, 100);
        assertEquals("one item a row", 60, panel.getPreferredSize().height);
    }

    @Test
    public void theWrappedRowsAreLaidOutBelowEachOther() {
        JPanel panel = items();
        JPanel parent = inParent(panel, 120);
        panel.doLayout();
        Component first = panel.getComponent(0);
        Component third = panel.getComponent(2);
        assertEquals(parent.getHeight() - panel.getHeight(), panel.getY());
        assertEquals(first.getX(), third.getX());
        assertEquals(first.getY() + 14, third.getY());
    }
}
