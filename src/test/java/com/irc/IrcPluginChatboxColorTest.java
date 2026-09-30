package com.irc;

import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import org.junit.Test;

import java.awt.Color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The in-game chat box colour is opt-in. Off, messages keep RuneLite's own chat colours (the
 * NORMAL colour type, which the Chat Colour plugin controls); on, they get the chosen colour.
 */
public class IrcPluginChatboxColorTest {

    private static IrcConfig config(boolean enabled, Color color) {
        return config(enabled, color, false);
    }

    private static IrcConfig config(boolean enabled, Color color, boolean ircColors) {
        return new IrcConfig() {
            @Override public String username() { return "tester"; }
            @Override public String password() { return ""; }
            @Override public boolean inGameTextColorEnabled() { return enabled; }
            @Override public Color inGameTextColor() { return color; }
            @Override public boolean ircColorsInGame() { return ircColors; }
        };
    }

    private static final IrcConfig IRC_COLORS = config(false, null, true);

    private static ChatMessageBuilder normal() {
        return new ChatMessageBuilder().append(ChatColorType.NORMAL);
    }

    @Test
    public void offByDefault() {
        IrcConfig defaults = new IrcConfig() {
            @Override public String username() { return "tester"; }
            @Override public String password() { return ""; }
        };
        assertFalse(defaults.inGameTextColorEnabled());
    }

    @Test
    public void disabledKeepsRuneLiteChatColours() {
        String expected = new ChatMessageBuilder().append(ChatColorType.NORMAL).append("hello").build();
        assertEquals(expected, IrcPlugin.chatboxMessage("hello", config(false, Color.RED)));
    }

    @Test
    public void enabledUsesTheChosenColour() {
        assertEquals("<col=123456>hello</col>", IrcPlugin.chatboxMessage("hello", config(true, new Color(0x12, 0x34, 0x56))));
    }

    @Test
    public void enabledWithNoColourFallsBackToRuneLiteChatColours() {
        String expected = new ChatMessageBuilder().append(ChatColorType.NORMAL).append("hello").build();
        assertEquals(expected, IrcPlugin.chatboxMessage("hello", config(true, null)));
    }

    @Test
    public void ircColoursOnByDefault() {
        IrcConfig defaults = new IrcConfig() {
            @Override public String username() { return "tester"; }
            @Override public String password() { return ""; }
        };
        assertTrue(defaults.ircColorsInGame());
    }

    @Test
    public void ircColoursOffStripsTheCodes() {
        assertEquals(normal().append("red plain").build(),
                IrcPlugin.chatboxMessage("\u00034red\u000F plain", config(false, null)));
    }

    @Test
    public void ircColourBecomesAColTag() {
        assertEquals(new ChatMessageBuilder().append(new Color(0xFF0000), "red").build(),
                IrcPlugin.chatboxMessage("\u00034red", IRC_COLORS));
    }

    @Test
    public void resetReturnsToTheBaseColour() {
        String expected = new ChatMessageBuilder().append(new Color(0xFF0000), "red")
                .append(ChatColorType.NORMAL).append(" plain").build();
        assertEquals(expected, IrcPlugin.chatboxMessage("\u00034red\u000F plain", IRC_COLORS));
    }

    @Test
    public void bareColourCodeAlsoResets() {
        String expected = normal().append("a ").append(new Color(0x00FF00), "green")
                .append(ChatColorType.NORMAL).append(" b").build();
        assertEquals(expected, IrcPlugin.chatboxMessage("a \u00039green\u0003 b", IRC_COLORS));
    }

    @Test
    public void backgroundIsDroppedBecauseTheChatBoxCannotDrawIt() {
        assertEquals(new ChatMessageBuilder().append(new Color(0xFF0000), "hi").build(),
                IrcPlugin.chatboxMessage("\u00034,8hi", IRC_COLORS));
    }

    @Test
    public void namedPaletteColoursBecomeHex() {
        // The panel palette names 0 and 1 "white" and "black"; the chat box needs hex.
        assertEquals(new ChatMessageBuilder().append(Color.WHITE, "w").append(Color.BLACK, "b").build(),
                IrcPlugin.chatboxMessage("\u00030w\u00031b", IRC_COLORS));
    }

    @Test
    public void extendedPaletteColour() {
        assertEquals(new ChatMessageBuilder().append(new Color(0x470000), "x").build(),
                IrcPlugin.chatboxMessage("\u000316x", IRC_COLORS));
    }

    @Test
    public void defaultColour99IsTheBaseColour() {
        assertEquals(normal().append("x").build(), IrcPlugin.chatboxMessage("\u000399x", IRC_COLORS));
    }

    @Test
    public void otherFormattingIsStripped() {
        assertEquals(normal().append("bold italic under").build(),
                IrcPlugin.chatboxMessage("\u0002bold\u0002 \u001Ditalic\u001D \u001Funder\u001F", IRC_COLORS));
    }

    @Test
    public void baseColourIsTheCustomInGameColourWhenSet() {
        Color custom = new Color(0x123456);
        String expected = new ChatMessageBuilder().append(new Color(0xFF0000), "red")
                .append(custom, " plain").build();
        assertEquals(expected, IrcPlugin.chatboxMessage("\u00034red\u000F plain", config(true, custom, true)));
    }
}
