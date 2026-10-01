package com.irc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Guards the mIRC color-code to HTML-color mapping used when rendering messages.
 * Codes 0-15 are the classic palette; 16-98 are the extended palette from the
 * modern IRC formatting spec (https://modern.ircdocs.horse/formatting.html). Code
 * 99 is "default" and anything out of range falls back to black.
 */
public class IrcColorCodeTest {

    @Test
    public void classicPaletteIsUnchanged() {
        assertEquals("white", IrcPanel.ChannelPane.htmlColorById("0"));
        assertEquals("black", IrcPanel.ChannelPane.htmlColorById("1"));
        assertEquals("black", IrcPanel.ChannelPane.htmlColorById("01"));
        assertEquals("#008080", IrcPanel.ChannelPane.htmlColorById("10"));
        assertEquals("#C0C0C0", IrcPanel.ChannelPane.htmlColorById("15"));
    }

    @Test
    public void extendedPaletteBoundariesMapToSpecValues() {
        assertEquals("#470000", IrcPanel.ChannelPane.htmlColorById("16"));
        assertEquals("#FFFFFF", IrcPanel.ChannelPane.htmlColorById("98"));
    }

    @Test
    public void extendedPaletteInteriorValuesMapToSpecValues() {
        assertEquals("#FF0000", IrcPanel.ChannelPane.htmlColorById("52"));
        assertEquals("#000000", IrcPanel.ChannelPane.htmlColorById("88"));
        assertEquals("#B5B500", IrcPanel.ChannelPane.htmlColorById("42"));
    }

    @Test
    public void defaultAndOutOfRangeCodesFallBackToBlack() {
        assertEquals("black", IrcPanel.ChannelPane.htmlColorById("99"));
        assertEquals("black", IrcPanel.ChannelPane.htmlColorById("100"));
        assertEquals("black", IrcPanel.ChannelPane.htmlColorById("notacode"));
    }
}
