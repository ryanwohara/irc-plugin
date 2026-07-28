package com.irc;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Per-channel roster of who is present and what membership modes they hold.
 *
 * Fed by NAMES (353/366), JOIN, PART, QUIT, KICK, NICK and MODE. The interesting cases are
 * parameter alignment in compound MODE strings, NAMES replacing rather than merging, and
 * QUIT/NICK reporting which channels they touched so only those get redrawn.
 */
public class ChannelUserListTest {

    private ChannelUserList users;

    @Before
    public void setUp() {
        users = new ChannelUserList(ModeSpec.defaults());
    }

    /** Loads a roster the way the protocol does: one or more 353 lines, then a 366. */
    private void names(String channel, String... rawEntries) {
        users.addNames(channel, Arrays.asList(rawEntries));
        users.endNames(channel);
    }

    private List<String> nicks(String channel) {
        List<String> result = new java.util.ArrayList<>();
        for (ChannelUserList.Entry entry : users.snapshot(channel)) {
            result.add(entry.getPrefix() + entry.getNick());
        }
        return result;
    }

    @Test
    public void namesPopulatesUsersAndTheirPrefixes() {
        names("#chan", "@bob", "+dave", "erin");
        assertEquals(Arrays.asList("@bob", "+dave", "erin"), nicks("#chan"));
    }

    @Test
    public void namesHandlesMultiplePrefixesAndShowsTheHighest() {
        names("#chan", "@+bob");
        assertEquals(Collections.singletonList("@bob"), nicks("#chan"));
    }

    @Test
    public void multiplePrefixesAreAllRetainedSoRevokingOneLeavesTheOther() {
        names("#chan", "@+bob");
        users.applyModeChange("#chan", Arrays.asList("-o", "bob"));
        assertEquals("voice survives losing op", Collections.singletonList("+bob"), nicks("#chan"));
    }

    @Test
    public void namesAcrossMultipleLinesAccumulateBeforeTheCommit() {
        users.addNames("#chan", Arrays.asList("@bob", "+dave"));
        users.addNames("#chan", Collections.singletonList("erin"));
        users.endNames("#chan");
        assertEquals(Arrays.asList("@bob", "+dave", "erin"), nicks("#chan"));
    }

    @Test
    public void aSecondNamesRoundReplacesRatherThanMerges() {
        names("#chan", "@bob", "+dave", "erin");
        names("#chan", "@bob");
        assertEquals("stale users are dropped", Collections.singletonList("@bob"), nicks("#chan"));
    }

    @Test
    public void endNamesWithoutPendingIsANoOp() {
        names("#chan", "@bob");
        users.endNames("#chan");
        assertEquals(Collections.singletonList("@bob"), nicks("#chan"));
    }

    @Test
    public void joinAddsAnUnprivilegedUser() {
        names("#chan", "@bob");
        users.join("#chan", "erin");
        assertEquals(Arrays.asList("@bob", "erin"), nicks("#chan"));
    }

    @Test
    public void partRemovesTheUser() {
        names("#chan", "@bob", "erin");
        users.part("#chan", "erin");
        assertEquals(Collections.singletonList("@bob"), nicks("#chan"));
    }

    @Test
    public void kickRemovesTheUser() {
        names("#chan", "@bob", "erin");
        users.kick("#chan", "erin");
        assertEquals(Collections.singletonList("@bob"), nicks("#chan"));
    }

    @Test
    public void quitRemovesTheUserFromEveryChannelAndReportsWhich() {
        names("#one", "erin", "bob");
        names("#two", "erin");
        names("#three", "bob");

        List<String> affected = users.quit("erin");

        assertEquals(2, affected.size());
        assertTrue(affected.contains("#one"));
        assertTrue(affected.contains("#two"));
        assertEquals(Collections.singletonList("bob"), nicks("#one"));
        assertEquals(Collections.emptyList(), nicks("#two"));
    }

    @Test
    public void renameKeepsModesAndReportsAffectedChannels() {
        names("#one", "@bob");
        names("#two", "+bob");

        List<String> affected = users.rename("bob", "robert");

        assertEquals(2, affected.size());
        assertEquals(Collections.singletonList("@robert"), nicks("#one"));
        assertEquals(Collections.singletonList("+robert"), nicks("#two"));
    }

    @Test
    public void modeGrantsOp() {
        names("#chan", "bob");
        users.applyModeChange("#chan", Arrays.asList("+o", "bob"));
        assertEquals(Collections.singletonList("@bob"), nicks("#chan"));
    }

    @Test
    public void modeRevokesVoice() {
        names("#chan", "+bob");
        users.applyModeChange("#chan", Arrays.asList("-v", "bob"));
        assertEquals(Collections.singletonList("bob"), nicks("#chan"));
    }

    @Test
    public void compoundModeSkipsTheParameterlessLetter() {
        // The regression this whole design exists to prevent: "m" takes no parameter, so "bob"
        // belongs to "o". Getting this wrong ops nobody, or the wrong person.
        names("#chan", "bob");
        users.applyModeChange("#chan", Arrays.asList("+mo", "bob"));
        assertEquals(Collections.singletonList("@bob"), nicks("#chan"));
    }

    @Test
    public void mixedSignModeAppliesEachLetterToItsOwnParameter() {
        names("#chan", "bob", "+carol");
        users.applyModeChange("#chan", Arrays.asList("+o-v", "bob", "carol"));
        assertEquals(Arrays.asList("@bob", "carol"), nicks("#chan"));
    }

    @Test
    public void removingATypeCModeConsumesNoParameter() {
        // "l" (limit) takes a parameter when set but not when removed, so the lone parameter
        // "bob" belongs to "+o". An implementation that wrongly consumed one for "-l" would
        // leave "+o" with nothing and fail to op anyone.
        names("#chan", "bob");
        users.applyModeChange("#chan", Arrays.asList("-l+o", "bob"));
        assertEquals(Collections.singletonList("@bob"), nicks("#chan"));
    }

    @Test
    public void settingATypeBModeConsumesItsParameter() {
        // "k" (key) always takes a parameter, so "secret" is the key and "bob" belongs to "o".
        names("#chan", "bob");
        users.applyModeChange("#chan", Arrays.asList("+ko", "secret", "bob"));
        assertEquals(Collections.singletonList("@bob"), nicks("#chan"));
    }

    @Test
    public void modeTargetingAnUnknownNickIsIgnored() {
        names("#chan", "bob");
        users.applyModeChange("#chan", Arrays.asList("+o", "nosuchuser"));
        assertEquals(Collections.singletonList("bob"), nicks("#chan"));
    }

    @Test
    public void modeWithMissingParametersDoesNotThrow() {
        names("#chan", "bob");
        users.applyModeChange("#chan", Collections.singletonList("+o"));
        assertEquals(Collections.singletonList("bob"), nicks("#chan"));
    }

    @Test
    public void modeOnAnUnknownChannelIsIgnored() {
        users.applyModeChange("#nosuchchannel", Arrays.asList("+o", "bob"));
        assertEquals(Collections.emptyList(), nicks("#nosuchchannel"));
    }

    @Test
    public void snapshotSortsByRankThenCaseInsensitiveAlphabetically() {
        names("#chan", "zoe", "@Bob", "+dave", "~founder", "Alice", "%carol");
        assertEquals(
                Arrays.asList("~founder", "@Bob", "%carol", "+dave", "Alice", "zoe"),
                nicks("#chan"));
    }

    @Test
    public void nickMatchingIsCaseInsensitiveButDisplayCasingIsKept() {
        names("#chan", "@Bob");
        users.applyModeChange("#chan", Arrays.asList("-o", "bob"));
        assertEquals(Collections.singletonList("Bob"), nicks("#chan"));
    }

    @Test
    public void snapshotIsImmutable() {
        names("#chan", "bob");
        List<ChannelUserList.Entry> snapshot = users.snapshot("#chan");
        try {
            snapshot.add(new ChannelUserList.Entry("evil", "", 0));
            org.junit.Assert.fail("snapshot must not be modifiable");
        } catch (UnsupportedOperationException expected) {
            // the EDT reads this while the socket thread mutates the source
        }
    }

    @Test
    public void snapshotOfAnUnknownChannelIsEmpty() {
        assertEquals(Collections.emptyList(), users.snapshot("#nope"));
    }

    @Test
    public void removeChannelDropsItsRoster() {
        names("#chan", "bob");
        users.removeChannel("#chan");
        assertEquals(Collections.emptyList(), users.snapshot("#chan"));
    }

    @Test
    public void clearDropsEverything() {
        names("#one", "bob");
        names("#two", "erin");
        users.clear();
        assertEquals(Collections.emptyList(), users.snapshot("#one"));
        assertEquals(Collections.emptyList(), users.snapshot("#two"));
    }
}
