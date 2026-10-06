package com.irc;

import org.junit.Test;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class PopOutGeometryTest {
    @Test
    public void roundTrips() {
        PopOutGeometry normal = PopOutGeometry.parse(new PopOutGeometry(new Rectangle(-1200, 40, 900, 600), false).serialize());
        assertEquals(new Rectangle(-1200, 40, 900, 600), normal.bounds);
        assertFalse(normal.maximized);

        PopOutGeometry maximized = PopOutGeometry.parse(new PopOutGeometry(new Rectangle(10, 20, 700, 500), true).serialize());
        assertEquals(new Rectangle(10, 20, 700, 500), maximized.bounds);
        assertTrue(maximized.maximized);
    }

    @Test
    public void unreadableValuesAreNull() {
        for (String value : Arrays.asList(null, "", "1,2,3", "1,2,3,4,5", "1,2,3,4,max", "a,2,3,4", "1,2,0,4", "1,2,3,-4")) {
            assertNull(value, PopOutGeometry.parse(value));
        }
    }

    @Test
    public void titleBarMustBeOnAScreen() {
        List<Rectangle> screens = Arrays.asList(new Rectangle(0, 0, 1920, 1080), new Rectangle(1920, 0, 1280, 1024));
        assertTrue(geometry(100, 100).titleBarVisible(screens));
        assertTrue("on the second monitor", geometry(2200, 100).titleBarVisible(screens));
        assertFalse("its monitor was unplugged", geometry(3400, 100).titleBarVisible(screens));
        assertFalse("title bar above the top", geometry(100, -50).titleBarVisible(screens));
        assertFalse(geometry(100, 100).titleBarVisible(Collections.emptyList()));
    }

    private static PopOutGeometry geometry(int x, int y) {
        return new PopOutGeometry(new Rectangle(x, y, 800, 600), false);
    }
}
