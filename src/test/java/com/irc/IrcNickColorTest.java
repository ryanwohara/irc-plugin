package com.irc;

import org.junit.Test;

import java.awt.Color;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The palette nicks are colored from, shared by the chat pane and the nicklists so a nick looks
 * the same in both: 50 bright hues once around the wheel, each readable on the dark panel and
 * still readable dimmed to half for an away nick.
 */
public class IrcNickColorTest {

    /** Reflection-free access: the palette is exercised through nickColor's full range. */
    private static Set<String> allProducedColors() {
        Set<String> colors = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            colors.add(IrcPanel.ChannelPane.nickColor("nick" + i));
        }
        return colors;
    }

    @Test
    public void paletteHasFiftyColors() {
        assertEquals(50, allProducedColors().size());
    }

    @Test
    public void everyPaletteEntryIsAHexColor() {
        for (String color : allProducedColors()) {
            assertTrue("expected #RRGGBB, got: " + color, color.matches("#[0-9A-F]{6}"));
        }
    }

    @Test
    public void everyPaletteEntryIsBrightEnoughForTheDarkPanel() {
        for (String hex : allProducedColors()) {
            Color c = Color.decode(hex);
            int brightest = Math.max(c.getRed(), Math.max(c.getGreen(), c.getBlue()));
            assertTrue(hex + " is too dark to read once dimmed", brightest >= 0xE4);
        }
    }

    @Test
    public void nickColorIsDeterministic() {
        assertEquals(IrcPanel.ChannelPane.nickColor("bob"), IrcPanel.ChannelPane.nickColor("bob"));
    }

    @Test
    public void nickColorHandlesTheMinimumHashCodeWithoutCrashing() {
        // Math.abs(Integer.MIN_VALUE) is still Integer.MIN_VALUE, so the old
        // "Math.abs(h) % length" produced a negative index here. Math.floorMod does not.
        assertEquals(Integer.MIN_VALUE, "polygenelubricants".hashCode());
        String color = IrcPanel.ChannelPane.nickColor("polygenelubricants");
        assertTrue("must return a palette colour, got: " + color, allProducedColors().contains(color));
    }

    @Test
    public void nickColorHandlesAnEmptyNick() {
        assertTrue(allProducedColors().contains(IrcPanel.ChannelPane.nickColor("")));
    }

    @Test
    public void nickColorSpreadsAcrossTheWholePalette() {
        List<String> sampled = Arrays.asList(
                IrcPanel.ChannelPane.nickColor("alice"),
                IrcPanel.ChannelPane.nickColor("bob"),
                IrcPanel.ChannelPane.nickColor("carol"));
        assertEquals("distinct nicks should not all collide", 3, new HashSet<>(sampled).size());
    }

    @Test
    public void dimmedIsHalfWayToTheBackground() {
        assertEquals(new Color(0x81, 0x31, 0x32),
                IrcPanel.dimmed(Color.decode("#E44343"), new Color(0x1E, 0x1F, 0x22)));
    }
}
