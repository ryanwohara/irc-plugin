package com.irc;

import org.junit.Test;

import java.util.regex.Matcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The URL pattern behind both link surfaces - the side panel (IrcPanel) and the game chatbox
 * (IrcPlugin) share this one Pattern, so a gap in it breaks clickable links in both at once.
 *
 * The failure mode that matters is truncation, not rejection: a character missing from the trailing
 * class does not stop the URL matching, it just ends the match early. A wiki search link would still
 * light up as a link and still be clickable - it would simply go somewhere else. So these tests
 * assert the WHOLE url is captured, never merely that something matched.
 */
public class IrcLinkPatternTest {

    /** The full text the pattern claims, or null when it claims nothing. */
    private static String firstMatch(String text) {
        Matcher matcher = IrcPanel.VALID_LINK.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    /** The reported case: the wiki encodes search spaces as '+'. */
    @Test
    public void keepsPlusInAQueryString() {
        String url = "https://oldschool.runescape.wiki/w/Special:Search?search=ocean+encounters";
        assertEquals(url, firstMatch(url));
    }

    @Test
    public void keepsSeveralPluses() {
        String url = "https://oldschool.runescape.wiki/w/Special:Search?search=a+b+c+d";
        assertEquals(url, firstMatch(url));
    }

    @Test
    public void keepsPlusInAPathSegment() {
        String url = "https://example.com/a+b/c+d";
        assertEquals(url, firstMatch(url));
    }

    @Test
    public void keepsPlusAlongsideOtherQuerySyntax() {
        String url = "https://example.com/s?q=ocean+encounters&lang=en-gb&page=2";
        assertEquals(url, firstMatch(url));
    }

    /** A link in the middle of a sentence stops at the space, not before it. */
    @Test
    public void stopsAtWhitespaceNotAtThePlus() {
        String url = "https://oldschool.runescape.wiki/w/Special:Search?search=ocean+encounters";
        assertEquals(url, firstMatch("look at " + url + " for details"));
    }

    // --- fragments ---

    /** Wiki section links are the common case: everything after '#' used to be dropped. */
    @Test
    public void keepsAFragment() {
        String url = "https://oldschool.runescape.wiki/w/Ocean#Locations";
        assertEquals(url, firstMatch(url));
    }

    @Test
    public void keepsAFragmentAlongsideAQueryAndPluses() {
        String url = "https://oldschool.runescape.wiki/w/Special:Search?search=ocean+encounters#results";
        assertEquals(url, firstMatch(url));
    }

    @Test
    public void keepsAFragmentContainingUnderscoresAndDashes() {
        String url = "https://example.com/page#some_section-two";
        assertEquals(url, firstMatch(url));
    }

    @Test
    public void stopsAtWhitespaceAfterAFragment() {
        String url = "https://oldschool.runescape.wiki/w/Ocean#Locations";
        assertEquals(url, firstMatch("see " + url + " for the table"));
    }

    /** A channel name is not a link, and must not become one. */
    @Test
    public void doesNotTreatAHashAsALinkByItself() {
        assertNull(firstMatch("#help"));
        assertNull(firstMatch("join #osrs please"));
        assertEquals("https://example.com/x", firstMatch("#chan https://example.com/x"));
    }

    // --- regressions: everything that worked before must still work ---

    @Test
    public void stillMatchesAPlainUrl() {
        assertEquals("https://example.com", firstMatch("https://example.com"));
        assertEquals("http://example.com/path", firstMatch("http://example.com/path"));
    }

    @Test
    public void stillMatchesTheExistingPunctuationSet() {
        String url = "https://example.com/a-b_c/d.e?f=g&h=i%20j,k;l:m";
        assertEquals(url, firstMatch(url));
    }

    @Test
    public void stillMatchesASubdomainedHost() {
        String url = "https://oldschool.runescape.wiki/w/Ocean";
        assertEquals(url, firstMatch(url));
    }

    @Test
    public void stillIgnoresNonUrlText() {
        assertNull(firstMatch("no link here"));
        assertNull(firstMatch("not a url: example"));
    }

    /**
     * A bare '+' must not become a link on its own, and must not extend a match backwards - the
     * plus is only ever valid in the trailing portion, after a host.
     */
    @Test
    public void doesNotTreatAPlusAsALinkByItself() {
        assertNull(firstMatch("1+1=2"));
        assertEquals("https://example.com/x", firstMatch("a+b https://example.com/x"));
    }
}
