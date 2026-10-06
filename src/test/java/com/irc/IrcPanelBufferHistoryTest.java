package com.irc;

import org.junit.Before;
import org.junit.Test;

import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;

/** Alt+/ and {@code Alt+<} / {@code Alt+>} follow the buffers the panel has shown. */
public class IrcPanelBufferHistoryTest {
    private final IrcPanel panel = new IrcPanel();
    private final JTabbedPane tabs = new JTabbedPane();

    @Before
    public void setUp() throws Exception {
        set("config", new IrcConfig() {
            @Override public String username() { return "tester"; }
            @Override public String password() { return ""; }
        });
        set("tabbedPane", tabs);
        set("inputField", new JTextField());
        // The GUI wires this up; headless tests have to do it themselves.
        tabs.addChangeListener(e -> panel.onFocusedBufferChanged());
        panel.addChannel("System");
        panel.addChannel("#a");
        panel.addChannel("#b");
        panel.addChannel("#c");
    }

    @Test
    public void altSlashTogglesBetweenTheLastTwoBuffers() {
        panel.setFocusedChannel("#a");
        panel.setFocusedChannel("#c");
        panel.jumpToLastBuffer();
        assertEquals("#a", current());
        panel.jumpToLastBuffer();
        assertEquals("#c", current());
    }

    @Test
    public void altArrowsWalkTheVisitedBuffers() {
        panel.setFocusedChannel("#a");
        panel.setFocusedChannel("#c");
        panel.jumpToChannel(3);
        panel.bufferHistoryBack();
        assertEquals("#c", current());
        panel.bufferHistoryBack();
        assertEquals("#a", current());
        panel.bufferHistoryForward();
        panel.bufferHistoryForward();
        assertEquals("#b", current());
        panel.bufferHistoryForward();
        assertEquals("#b", current());
    }

    @Test
    public void closedAndRenamedBuffersAreFollowed() {
        panel.setFocusedChannel("#a");
        panel.setFocusedChannel("#b");
        panel.setFocusedChannel("#c");
        panel.removeChannel("#b");
        panel.renameChannel("#a", "#a2");
        panel.setFocusedChannel("#c");
        panel.bufferHistoryBack();
        assertEquals("#a2", current());
    }

    @Test
    public void altUpAndDownStepThroughTheTabsAndWrap() {
        panel.setFocusedChannel("#b");
        panel.stepChannel(1);
        assertEquals("#c", current());
        panel.stepChannel(1);
        assertEquals("System", current());
        panel.stepChannel(-1);
        assertEquals("#c", current());
        panel.stepChannel(-1);
        assertEquals("#b", current());
    }

    private String current() {
        return panel.getCurrentBuffer().getName();
    }

    private void set(String name, Object value) throws Exception {
        Field field = IrcPanel.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(panel, value);
    }
}
