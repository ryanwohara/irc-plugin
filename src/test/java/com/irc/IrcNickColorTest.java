package com.irc;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The palette nicks are colored from, shared by the chat pane and the nicklist dropdown so a
 * nick looks the same in both.
 *
 * 36 codes: the classic 02-13 plus the extended palette's vivid and pastel rows 64-87. The
 * near-black rows (16-39) and dark grays (88-93) are excluded as unreadable on the dark panel.
 */
public class IrcNickColorTest {

    /** Reflection-free access: the palette is exercised through nickColorId's full range. */
    private static Set<String> allProducedIds() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            ids.add(IrcPanel.ChannelPane.nickColorId("nick" + i));
        }
        return ids;
    }

    @Test
    public void paletteHasThirtySixColors() {
        assertEquals(36, allProducedIds().size());
    }

    @Test
    public void paletteKeepsTheClassicCodesTwoThroughThirteen() {
        Set<String> ids = allProducedIds();
        for (String classic : Arrays.asList("02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12", "13")) {
            assertTrue("classic code " + classic + " should still be in the palette", ids.contains(classic));
        }
    }

    @Test
    public void paletteIncludesExtendedRowsSixtyFourThroughEightySeven() {
        Set<String> ids = allProducedIds();
        for (int code = 64; code <= 87; code++) {
            assertTrue("extended code " + code + " should be in the palette", ids.contains(String.valueOf(code)));
        }
    }

    @Test
    public void paletteExcludesUnreadableRows() {
        Set<String> ids = allProducedIds();
        assertFalse("00/01 are pure white and black", ids.contains("00") || ids.contains("01"));
        assertFalse("16 is near-black", ids.contains("16"));
        assertFalse("88 is a dark gray", ids.contains("88"));
    }

    @Test
    public void everyPaletteEntryResolvesToARealColor() {
        for (String id : allProducedIds()) {
            String color = IrcPanel.ChannelPane.htmlColorById(id);
            assertTrue("code " + id + " must resolve to a hex color, got: " + color, color.startsWith("#"));
        }
    }

    @Test
    public void nickColorIdIsDeterministic() {
        assertEquals(IrcPanel.ChannelPane.nickColorId("bob"), IrcPanel.ChannelPane.nickColorId("bob"));
    }

    @Test
    public void nickColorIdHandlesTheMinimumHashCodeWithoutCrashing() {
        // Math.abs(Integer.MIN_VALUE) is still Integer.MIN_VALUE, so the old
        // "Math.abs(h) % length" produced a negative index here. Math.floorMod does not.
        assertEquals(Integer.MIN_VALUE, "polygenelubricants".hashCode());
        String id = IrcPanel.ChannelPane.nickColorId("polygenelubricants");
        assertTrue("must return a usable palette code, got: " + id, allProducedIds().contains(id));
    }

    @Test
    public void nickColorIdHandlesAnEmptyNick() {
        assertTrue(allProducedIds().contains(IrcPanel.ChannelPane.nickColorId("")));
    }

    @Test
    public void nickColorIdSpreadsAcrossTheWholePalette() {
        List<String> sampled = Arrays.asList(
                IrcPanel.ChannelPane.nickColorId("alice"),
                IrcPanel.ChannelPane.nickColorId("bob"),
                IrcPanel.ChannelPane.nickColorId("carol"));
        assertEquals("distinct nicks should not all collide", 3, new HashSet<>(sampled).size());
    }
}
