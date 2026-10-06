package com.irc;

import java.awt.Rectangle;
import java.util.List;

/**
 * Where the pop-out was and how big, saved in the config so it reopens the same after a restart.
 * {@link #bounds} are the window's normal (unmaximized) bounds, so un-maximizing a window that
 * reopened maximized still lands somewhere sensible.
 */
final class PopOutGeometry {
    static final String CONFIG_KEY = "popOutBounds";

    final Rectangle bounds;
    final boolean maximized;

    PopOutGeometry(Rectangle bounds, boolean maximized) {
        this.bounds = new Rectangle(bounds);
        this.maximized = maximized;
    }

    /** "x,y,width,height" with ",maximized" on the end when it was. */
    String serialize() {
        return bounds.x + "," + bounds.y + "," + bounds.width + "," + bounds.height
                + (maximized ? ",maximized" : "");
    }

    /** Null for anything unreadable, so a bad value falls back to the default placement. */
    static PopOutGeometry parse(String value) {
        if (value == null) return null;
        String[] parts = value.trim().split(",");
        if (parts.length != 4 && !(parts.length == 5 && "maximized".equals(parts[4]))) return null;
        try {
            Rectangle bounds = new Rectangle(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim()));
            if (bounds.width <= 0 || bounds.height <= 0) return null;
            return new PopOutGeometry(bounds, parts.length == 5);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whether the middle of the title bar is on one of {@code screens}, so the window can be dragged. */
    boolean titleBarVisible(List<Rectangle> screens) {
        for (Rectangle screen : screens) {
            if (screen.contains(bounds.x + bounds.width / 2, bounds.y + 10)) return true;
        }
        return false;
    }
}
