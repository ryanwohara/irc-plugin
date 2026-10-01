package com.irc.emoji;

import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies emoji data is loaded from emojis.json into EmojiManager's lookups. Guards the
 * refactor that parses entries directly into the manager's final collections instead of
 * copying them in from temporary collections.
 */
public class EmojiServiceTest {

    @Test
    public void initializeLoadsEmojisAndAliases() {
        new EmojiService(new Gson()).initialize();

        // U+1F1E6 U+1F1E8 = 🇦🇨 (regional indicators A + C), the first entry in
        // emojis.json, registered under the alias "ac".
        Emoji ac = EmojiManager.getByUnicode("🇦🇨");
        assertNotNull(ac);
        assertTrue(ac.getAliases().contains("ac"));
    }
}
