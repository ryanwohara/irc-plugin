package com.irc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The shared IRC formatting stripper.
 *
 * The background group must accept one OR two digits. The two constants this class replaced both
 * required exactly two, which left a stray ",5" behind on text coloured foreground 04 on
 * background 5 - while the renderer (IrcPanel.COLORS) parsed that same text as a real background.
 * Stripper and renderer must agree on what counts as a colour code.
 */
public class IrcFormattingTest {

    private static final String BOLD = String.valueOf((char) 0x02);
    private static final String COLOR = String.valueOf((char) 0x03);
    private static final String ITALIC = String.valueOf((char) 0x1D);
    private static final String REVERSE = String.valueOf((char) 0x15);
    private static final String RESET = String.valueOf((char) 0x0F);

    @Test
    public void stripsBoldItalicReverseAndReset() {
        assertEquals("hello", IrcFormatting.stripCodes(BOLD + "hello" + RESET));
        assertEquals("hello", IrcFormatting.stripCodes(ITALIC + "hello" + ITALIC));
        assertEquals("hello", IrcFormatting.stripCodes(REVERSE + "hello" + REVERSE));
    }

    @Test
    public void stripsBareColorCode() {
        assertEquals("hello", IrcFormatting.stripCodes(COLOR + "hello"));
    }

    @Test
    public void stripsOneAndTwoDigitForeground() {
        assertEquals("hello", IrcFormatting.stripCodes(COLOR + "4hello"));
        assertEquals("hello", IrcFormatting.stripCodes(COLOR + "04hello"));
    }

    @Test
    public void stripsTwoDigitBackground() {
        assertEquals("hello", IrcFormatting.stripCodes(COLOR + "04,12hello"));
    }

    /** The bug this task fixes: a single-digit background used to leave ",5" behind. */
    @Test
    public void stripsSingleDigitBackground() {
        assertEquals("hello", IrcFormatting.stripCodes(COLOR + "04,5hello"));
        assertEquals("hello", IrcFormatting.stripCodes(COLOR + "4,5hello"));
    }

    /** A comma that is not part of a colour code is ordinary text and must survive. */
    @Test
    public void keepsCommasThatAreNotBackgroundCodes() {
        assertEquals("hi, there", IrcFormatting.stripCodes(COLOR + "04hi, there"));
        assertEquals("a,b", IrcFormatting.stripCodes("a,b"));
    }

    @Test
    public void leavesPlainTextUntouched() {
        assertEquals("nothing to strip", IrcFormatting.stripCodes("nothing to strip"));
    }

    @Test
    public void toleratesNullAndEmpty() {
        assertNull(IrcFormatting.stripCodes(null));
        assertEquals("", IrcFormatting.stripCodes(""));
    }
}
