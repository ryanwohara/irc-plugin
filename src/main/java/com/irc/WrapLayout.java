package com.irc;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * A FlowLayout whose preferred size wraps onto as many rows as the available width needs.
 * FlowLayout itself lays components out in rows but always asks for a single row's height, so the
 * rows past the first are cut off.
 *
 * The width comes from the parent where there is one: a BorderLayout sizes its parent before
 * asking for this container's preferred size, so the container's own width is still the old one
 * while the window is being resized.
 */
final class WrapLayout extends FlowLayout {
    WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        return layoutSize(target, false);
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int available = availableWidth(target);
            Insets insets = target.getInsets();
            int maxRow = available - insets.left - insets.right - getHgap() * 2;
            int width = 0;
            int height = 0;
            int rowWidth = 0;
            int rowHeight = 0;
            for (Component component : target.getComponents()) {
                if (!component.isVisible()) continue;
                Dimension size = preferred ? component.getPreferredSize() : component.getMinimumSize();
                if (rowWidth > 0 && rowWidth + getHgap() + size.width > maxRow) {
                    width = Math.max(width, rowWidth);
                    height += rowHeight + getVgap();
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth > 0) rowWidth += getHgap();
                rowWidth += size.width;
                rowHeight = Math.max(rowHeight, size.height);
            }
            width = Math.max(width, rowWidth);
            height += rowHeight;
            return new Dimension(width + insets.left + insets.right + getHgap() * 2,
                    height + insets.top + insets.bottom + getVgap() * 2);
        }
    }

    /** The width to wrap at; unbounded until something has been given a size. */
    private static int availableWidth(Container target) {
        Container parent = target.getParent();
        if (parent != null && parent.getWidth() > 0) {
            Insets insets = parent.getInsets();
            return parent.getWidth() - insets.left - insets.right;
        }
        return target.getWidth() > 0 ? target.getWidth() : Integer.MAX_VALUE;
    }
}
