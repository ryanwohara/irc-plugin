package com.irc;

import org.junit.Test;

import javax.swing.Action;
import javax.swing.JTextField;
import java.awt.event.ActionEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Verifies the input box is actually wired to InputHistory: the VK_UP/VK_DOWN key bindings
 * installed by setupShortcuts must resolve to actions that recall history into inputField.
 * The InputHistory logic itself is covered by {@link InputHistoryTest}; this guards the glue
 * (matching action keys, caret handling) that unit tests can't see.
 */
public class IrcPanelInputHistoryTest {

    private static InputHistory history(IrcPanel panel) throws Exception {
        Field field = IrcPanel.class.getDeclaredField("inputHistory");
        field.setAccessible(true);
        return (InputHistory) field.get(panel);
    }

    private static void fire(JTextField input, String actionKey) {
        Action action = input.getActionMap().get(actionKey);
        assertNotNull("action '" + actionKey + "' must be bound", action);
        action.actionPerformed(new ActionEvent(input, ActionEvent.ACTION_PERFORMED, actionKey));
    }

    @Test
    public void upBindingRecallsLastSentMessageAndDownRestoresDraft() throws Exception {
        IrcPanel panel = new IrcPanel();
        panel.inputField = new JTextField();

        Method setupShortcuts = IrcPanel.class.getDeclaredMethod("setupShortcuts");
        setupShortcuts.setAccessible(true);
        setupShortcuts.invoke(panel);

        InputHistory history = history(panel);
        history.add("hello");
        history.add("world");

        // User has typed an unsent draft, then presses Up.
        panel.inputField.setText("draft");
        fire(panel.inputField, "historyPrevious");
        assertEquals("world", panel.inputField.getText());
        assertEquals("caret at end", "world".length(), panel.inputField.getCaretPosition());

        fire(panel.inputField, "historyPrevious");
        assertEquals("hello", panel.inputField.getText());

        // Down back down past the newest restores the stashed draft.
        fire(panel.inputField, "historyNext");
        assertEquals("world", panel.inputField.getText());
        fire(panel.inputField, "historyNext");
        assertEquals("draft", panel.inputField.getText());
    }
}
