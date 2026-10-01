package com.irc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Nick sanitising for /nick and its chatbox form ;;nick.
 *
 * Users routinely type a nick with a space in it. A space cannot reach the wire: setNick sends
 * "NICK " + nick as a single raw line, so "foo bar" would go out as NICK with two parameters and
 * the server would silently take only "foo". The old code dodged that by refusing multi-word input
 * outright, which looked to the user like the command did nothing at all.
 *
 * Runs of whitespace collapse to ONE underscore rather than one per character - "foo   bar" becomes
 * "foo_bar", not "foo___bar". A triple underscore is not what anyone typing three spaces meant, and
 * nicks are length-capped by the server.
 */
public class IrcPluginNickTest {

    @Test
    public void replacesASingleSpaceWithAnUnderscore() {
        assertEquals("foo_bar", IrcPlugin.sanitizeNick("foo bar"));
    }

    /** Each separate gap becomes its own underscore. */
    @Test
    public void replacesEverySeparateSpace() {
        assertEquals("a_b_c", IrcPlugin.sanitizeNick("a b c"));
    }

    /** A run of spaces is one gap, so it collapses to one underscore. */
    @Test
    public void collapsesRunsOfSpacesToASingleUnderscore() {
        assertEquals("foo_bar", IrcPlugin.sanitizeNick("foo   bar"));
    }

    /** Surrounding whitespace is not part of the name, so it is trimmed rather than converted. */
    @Test
    public void trimsSurroundingWhitespaceInsteadOfConvertingIt() {
        assertEquals("foo", IrcPlugin.sanitizeNick("  foo  "));
        assertEquals("foo_bar", IrcPlugin.sanitizeNick("  foo bar  "));
    }

    @Test
    public void leavesAValidNickUntouched() {
        assertEquals("Ryan", IrcPlugin.sanitizeNick("Ryan"));
        assertEquals("guthix_", IrcPlugin.sanitizeNick("guthix_"));
        assertEquals("[bot]", IrcPlugin.sanitizeNick("[bot]"));
    }

    /** Tabs are whitespace too and would break the wire format the same way a space does. */
    @Test
    public void convertsNonSpaceWhitespace() {
        assertEquals("foo_bar", IrcPlugin.sanitizeNick("foo\tbar"));
        assertEquals("foo_bar", IrcPlugin.sanitizeNick("foo \t bar"));
    }

    /** Nothing usable in, nothing out - the caller declines to send rather than sending "NICK _". */
    @Test
    public void yieldsEmptyForNothingUsable() {
        assertEquals("", IrcPlugin.sanitizeNick(""));
        assertEquals("", IrcPlugin.sanitizeNick("   "));
        assertEquals("", IrcPlugin.sanitizeNick("\t"));
        assertEquals("", IrcPlugin.sanitizeNick(null));
    }

    /** The result must never contain whitespace - that is the whole point. */
    @Test
    public void resultNeverContainsWhitespace() {
        String[] inputs = {"foo bar", "a b c", "foo   bar", "  x  y  ", "foo\tbar"};
        for (String input : inputs) {
            String result = IrcPlugin.sanitizeNick(input);
            assertEquals("whitespace survived sanitising of \"" + input + "\": \"" + result + "\"",
                    -1, indexOfWhitespace(result));
        }
    }

    private static int indexOfWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
