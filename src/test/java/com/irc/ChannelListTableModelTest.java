package com.irc;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The channel browser's data layer, kept out of the JDialog so it is testable without a Window.
 *
 * The Users column must report Integer.class: that is the single thing that makes TableRowSorter
 * sort it numerically rather than lexically, and lexical order would rank 9 above 412 - the exact
 * question a channel browser exists to answer. Topics arrive with IRC colour codes in them and are
 * stripped for display, so the filter has to match what the user can actually see.
 */
public class ChannelListTableModelTest {

    private static final String COLOR = String.valueOf((char) 0x03);

    private static List<ChannelListEntry> sample() {
        return Arrays.asList(
                new ChannelListEntry("#help", 412, "Get help here"),
                new ChannelListEntry("#chat", 380, "General chatter"),
                new ChannelListEntry("#quest", 9, "Quest guides and help"));
    }

    private static ChannelListTableModel modelWith(List<ChannelListEntry> entries) {
        ChannelListTableModel model = new ChannelListTableModel();
        model.setEntries(entries);
        return model;
    }

    /** The whole reason the browser is usable: 412 must outrank 9. */
    @Test
    public void usersColumnIsIntegerSoItSortsNumerically() {
        ChannelListTableModel model = modelWith(sample());
        assertEquals(Integer.class, model.getColumnClass(1));
        assertEquals(String.class, model.getColumnClass(0));
        assertEquals(String.class, model.getColumnClass(2));
    }

    @Test
    public void exposesThreeColumnsInOrder() {
        ChannelListTableModel model = modelWith(sample());
        assertEquals(3, model.getColumnCount());
        assertEquals("Channel", model.getColumnName(0));
        assertEquals("Users", model.getColumnName(1));
        assertEquals("Topic", model.getColumnName(2));
    }

    @Test
    public void rowsExposeNameCountAndTopic() {
        ChannelListTableModel model = modelWith(sample());
        assertEquals(3, model.getRowCount());
        assertEquals("#help", model.getValueAt(0, 0));
        assertEquals(412, model.getValueAt(0, 1));
        assertEquals("Get help here", model.getValueAt(0, 2));
    }

    @Test
    public void stripsFormattingCodesFromDisplayedTopics() {
        ChannelListTableModel model = modelWith(Collections.singletonList(
                new ChannelListEntry("#styled", 3, COLOR + "04,5Bright" + COLOR + " topic")));
        assertEquals("Bright topic", model.getValueAt(0, 2));
    }

    @Test
    public void filterMatchesChannelName() {
        ChannelListTableModel model = modelWith(sample());
        model.setFilter("quest");
        assertEquals(1, model.getRowCount());
        assertEquals("#quest", model.getValueAt(0, 0));
    }

    @Test
    public void filterMatchesTopicText() {
        ChannelListTableModel model = modelWith(sample());
        model.setFilter("chatter");
        assertEquals(1, model.getRowCount());
        assertEquals("#chat", model.getValueAt(0, 0));
    }

    @Test
    public void filterIsCaseInsensitive() {
        ChannelListTableModel model = modelWith(sample());
        model.setFilter("QUEST");
        assertEquals(1, model.getRowCount());
    }

    @Test
    public void emptyFilterShowsEverything() {
        ChannelListTableModel model = modelWith(sample());
        model.setFilter("quest");
        model.setFilter("");
        assertEquals(3, model.getRowCount());
    }

    /** A filter typed against a styled topic must match the stripped text the user sees. */
    @Test
    public void filterMatchesStrippedTopicNotRawCodes() {
        // The needle straddles the code: "Br" + code + "ight" is not a contiguous "Bright" in the
        // raw text, only in the stripped text. A fixture where the code sits wholly before or
        // after the needle would pass even if the filter searched the raw string.
        ChannelListTableModel model = modelWith(Collections.singletonList(
                new ChannelListEntry("#styled", 3, "Br" + COLOR + "04" + "ight topic")));
        model.setFilter("Bright");
        assertEquals(1, model.getRowCount());
    }

    @Test
    public void reportsTotalAndShownCounts() {
        ChannelListTableModel model = modelWith(sample());
        assertEquals(3, model.getTotalCount());
        assertEquals(3, model.getShownCount());

        model.setFilter("quest");
        assertEquals(3, model.getTotalCount());
        assertEquals(1, model.getShownCount());
    }

    /** Joining acts on the entry behind a row, so the mapping must survive filtering. */
    @Test
    public void getEntryAtTracksTheFilteredView() {
        ChannelListTableModel model = modelWith(sample());
        model.setFilter("quest");
        assertEquals("#quest", model.getEntryAt(0).getName());
    }

    @Test
    public void replacingEntriesReappliesTheActiveFilter() {
        ChannelListTableModel model = modelWith(sample());
        model.setFilter("quest");
        assertEquals(1, model.getRowCount());

        model.setEntries(Arrays.asList(
                new ChannelListEntry("#questing", 5, "more quests"),
                new ChannelListEntry("#other", 5, "unrelated")));
        assertEquals(1, model.getRowCount());
        assertEquals("#questing", model.getValueAt(0, 0));
    }

    @Test
    public void handlesEmptyResults() {
        ChannelListTableModel model = modelWith(Collections.emptyList());
        assertEquals(0, model.getRowCount());
        assertEquals(0, model.getTotalCount());
    }

    @Test
    public void matchesPredicateHandlesNullAndBlankFilters() {
        ChannelListEntry entry = new ChannelListEntry("#help", 1, "topic");
        assertTrue(ChannelListTableModel.matches(entry, null));
        assertTrue(ChannelListTableModel.matches(entry, ""));
        assertTrue(ChannelListTableModel.matches(entry, "   "));
        assertFalse(ChannelListTableModel.matches(entry, "nomatch"));
    }

    @Test
    public void toleratesNullTopics() {
        ChannelListTableModel model = modelWith(Collections.singletonList(
                new ChannelListEntry("#notopic", 2, null)));
        assertEquals("", model.getValueAt(0, 2));
        model.setFilter("notopic");
        assertEquals(1, model.getRowCount());
    }

    /**
     * Whole-branch review finding: the class doc claimed topics were stripped "once, up front" and
     * they were not - stripCodes ran on every getValueAt and on every entry inside applyFilter, so
     * a filter keystroke was one regex pass per row. At the 20,000-row cap that is 20,000 regex
     * passes per character typed, on the EDT.
     *
     * Identity is the observable consequence of the fix: stripCodes goes through
     * Matcher.replaceAll, which allocates a new String whenever it actually removes something, so
     * a topic that contains codes can only yield the same instance twice if it was computed once
     * and kept. A topic with no codes would pass either way (replaceAll returns the input), hence
     * the deliberately styled fixture.
     */
    @Test
    public void strippedTopicsAreComputedOncePerEntry() {
        ChannelListTableModel model = modelWith(Collections.singletonList(
                new ChannelListEntry("#styled", 3, COLOR + "04Bright" + COLOR + " topic")));

        Object first = model.getValueAt(0, 2);
        assertEquals("Bright topic", first);
        assertSame("repainting a cell must not re-run the stripper", first, model.getValueAt(0, 2));
    }

    /** Filtering must reuse the precomputed rows rather than rebuild them per keystroke. */
    @Test
    public void filteringDoesNotRecomputeStrippedTopics() {
        ChannelListTableModel model = modelWith(Collections.singletonList(
                new ChannelListEntry("#styled", 3, COLOR + "04Bright" + COLOR + " topic")));
        Object before = model.getValueAt(0, 2);

        model.setFilter("bright");
        model.setFilter("br");
        model.setFilter("");

        assertEquals(1, model.getRowCount());
        assertSame("each keystroke must reuse the row stripped when the entries were set",
                before, model.getValueAt(0, 2));
    }

    /** Replacing the entries must restrip: the new rows are different objects entirely. */
    @Test
    public void replacingEntriesRestripsTheNewTopics() {
        ChannelListTableModel model = modelWith(Collections.singletonList(
                new ChannelListEntry("#one", 1, COLOR + "04first")));
        assertEquals("first", model.getValueAt(0, 2));

        model.setEntries(Collections.singletonList(
                new ChannelListEntry("#two", 2, COLOR + "04second")));
        assertEquals("second", model.getValueAt(0, 2));
    }
}
