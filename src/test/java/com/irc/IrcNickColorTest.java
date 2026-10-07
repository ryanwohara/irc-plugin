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
 * the same in both: 100 hues, all readable on the dark panel.
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
    public void paletteHasOneHundredColors() {
        assertEquals(100, allProducedColors().size());
    }

    @Test
    public void everyPaletteEntryIsAHexColor() {
        for (String color : allProducedColors()) {
            assertTrue("expected #RRGGBB, got: " + color, color.matches("#[0-9A-F]{6}"));
        }
    }

    /** WCAG relative luminance of an sRGB colour. */
    private static double luminance(Color c) {
        double[] channels = {c.getRed(), c.getGreen(), c.getBlue()};
        for (int i = 0; i < 3; i++) {
            double v = channels[i] / 255;
            channels[i] = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
    }

    /**
     * The 50 added colours were each chosen for at least 3:1; the floor is lower because three of
     * the original 50, the deep blues #434AE4, #6744F5 and #7643E4, sit between 2.6 and 2.9.
     */
    @Test
    public void everyPaletteEntryIsReadableOnTheDarkPanel() {
        double panel = luminance(new Color(0x1E, 0x1F, 0x22));
        for (String hex : allProducedColors()) {
            double contrast = (luminance(Color.decode(hex)) + 0.05) / (panel + 0.05);
            assertTrue(hex + " contrast is only " + contrast, contrast >= 2.5);
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
