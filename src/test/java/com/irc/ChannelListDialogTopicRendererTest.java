package com.irc;

import org.junit.Test;

import javax.swing.JTable;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

/**
 * The Topic column's tooltip.
 *
 * The design requires the topic be "rendered with formatting codes stripped, full text in a
 * tooltip since the column truncates" - the column is 440px wide and most real topics are longer,
 * so without the tooltip the tail of a topic is simply unreadable. The column shipped with the
 * default renderer and no tooltip at all.
 *
 * The dialog itself cannot be built headless (it is a Window), but the renderer is an ordinary
 * JLabel subclass, so the tooltip is asserted directly without one.
 */
public class ChannelListDialogTopicRendererTest {

    private static String tooltipFor(Object value) {
        ChannelListDialog.TopicRenderer renderer = new ChannelListDialog.TopicRenderer();
        JTable table = new JTable(new ChannelListTableModel());
        renderer.getTableCellRendererComponent(table, value, false, false, 0, 2);
        return renderer.getToolTipText();
    }

    @Test
    public void tooltipCarriesTheWholeTopic() {
        String longTopic = "Ask about quest help here, and read the pinned guide before posting - "
                + "we answer faster when you say which quest and which step you are stuck on.";
        assertEquals(longTopic, tooltipFor(longTopic));
    }

    @Test
    public void shortTopicsGetATooltipToo() {
        assertEquals("General chatter", tooltipFor("General chatter"));
    }

    /** An empty tooltip still pops an empty box open on hover, so an empty topic gets none. */
    @Test
    public void emptyTopicHasNoTooltip() {
        assertNull(tooltipFor(""));
    }

    @Test
    public void nullValueHasNoTooltip() {
        assertNull(tooltipFor(null));
    }

    /** A row reused for a different channel must not keep the previous row's tooltip. */
    @Test
    public void tooltipIsReplacedWhenTheRendererIsReused() {
        ChannelListDialog.TopicRenderer renderer = new ChannelListDialog.TopicRenderer();
        JTable table = new JTable(new ChannelListTableModel());

        renderer.getTableCellRendererComponent(table, "first topic", false, false, 0, 2);
        renderer.getTableCellRendererComponent(table, "second topic", false, false, 1, 2);
        assertEquals("second topic", renderer.getToolTipText());

        renderer.getTableCellRendererComponent(table, "", false, false, 2, 2);
        assertNull("an empty topic must clear the stale tooltip", renderer.getToolTipText());
    }

    /**
     * A correct renderer nobody installed is worth nothing, and the tests above would not notice.
     *
     * The dialog is a Window, so this can only run where one can be created; JUnit reports it as
     * skipped rather than passed elsewhere, which is the honest outcome - a silent early return
     * would look like coverage that is not there.
     */
    @Test
    public void theTopicColumnIsWiredToTheRenderer() throws Exception {
        assumeFalse("needs a display to construct the dialog", GraphicsEnvironment.isHeadless());

        ChannelListDialog dialog = new ChannelListDialog(null, null, null);
        try {
            Field tableField = ChannelListDialog.class.getDeclaredField("table");
            tableField.setAccessible(true);
            JTable table = (JTable) tableField.get(dialog);

            assertTrue("the Topic column must use the tooltip renderer",
                    table.getColumnModel().getColumn(2).getCellRenderer()
                            instanceof ChannelListDialog.TopicRenderer);
            assertNull("the other columns keep the default renderer",
                    table.getColumnModel().getColumn(0).getCellRenderer());
        } finally {
            dialog.dispose();
        }
    }
}
