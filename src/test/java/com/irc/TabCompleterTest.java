package com.irc;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TabCompleterTest {

    private static final List<String> NICKS = Arrays.asList("bob", "Bobby", "alice");
    private static final List<String> CHANNELS = Arrays.asList("#runescape", "#rs", "#help");

    private TabCompleter completer;

    @Before
    public void setUp() {
        completer = new TabCompleter();
    }

    private String complete(String text, int caret, boolean forward) {
        TabCompleter.Result result = completer.complete(text, caret, NICKS, CHANNELS, forward);
        return result == null ? null : result.text + "|" + result.caret;
    }

    private String complete(String text) {
        return complete(text, text.length(), true);
    }

    @Test
    public void nickAtLineStartGetsAColon() {
        assertEquals("alice: |7", complete("al"));
    }

    @Test
    public void nickMidLineGetsASpace() {
        assertEquals("hi alice |9", complete("hi al"));
    }

    @Test
    public void matchingIsCaseInsensitiveAndKeepsTheNicksCasing() {
        assertEquals("Bobby: |7", complete("BOBB"));
    }

    @Test
    public void channelsCompleteFromHash() {
        assertEquals("join #help |11", complete("join #he"));
    }

    @Test
    public void channelAtLineStartGetsNoColon() {
        assertEquals("#help |6", complete("#he"));
    }

    @Test
    public void repeatedTabCyclesThroughMatchesAndWraps() {
        assertEquals("bob: |5", complete("bo"));
        assertEquals("Bobby: |7", complete("bob: ", 5, true));
        assertEquals("bob: |5", complete("Bobby: ", 7, true));
    }

    @Test
    public void shiftTabCyclesBackwards() {
        assertEquals("Bobby: |7", complete("bo", 2, false));
        assertEquals("bob: |5", complete("Bobby: ", 7, false));
    }

    @Test
    public void anEditStartsAFreshCompletion() {
        assertEquals("bob: |5", complete("bo"));
        assertEquals("bob: alice |11", complete("bob: al"));
    }

    @Test
    public void completesTheWordBeforeTheCaretAndKeepsTheRest() {
        assertEquals("alice: how are you|6", complete("al how are you", 2, true));
        assertEquals("hi alice how are you|8", complete("hi al how are you", 5, true));
    }

    @Test
    public void nothingToCompleteLeavesTheInputAlone() {
        assertNull(complete(""));
        assertNull(complete("hello "));
        assertNull(complete("zed"));
    }

    /** The input box is shared, so Tab after switching buffers must complete from the new buffer's nicks. */
    @Test
    public void cyclingAfterABufferSwitchUsesTheNewBuffersNicks() {
        assertEquals("bob: |5", complete("bo"));
        TabCompleter.Result result = completer.complete("bob: ", 5, Arrays.asList("boris", "alice"), CHANNELS, true);
        assertEquals("boris: |7", result.text + "|" + result.caret);
    }

    @Test
    public void cyclingStopsWhenNoMatchesAreLeft() {
        assertEquals("bob: |5", complete("bo"));
        assertNull(completer.complete("bob: ", 5, Arrays.asList("alice"), CHANNELS, true));
    }

    /** Someone unrelated joining mid-cycle must not restart the cycle. */
    @Test
    public void anUnrelatedRosterChangeKeepsCycling() {
        assertEquals("bob: |5", complete("bo"));
        TabCompleter.Result result = completer.complete("bob: ", 5, Arrays.asList("bob", "Bobby", "alice", "zed"), CHANNELS, true);
        assertEquals("Bobby: |7", result.text + "|" + result.caret);
    }
}
