package com.irc.emoji;

import java.util.*;

public class EmojiManager {
    private static final Map<String, Emoji> EMOJI_BY_UNICODE = new HashMap<>();
    private static final Map<String, Emoji> EMOJI_BY_ALIAS = new HashMap<>();
    private static final List<Emoji> ALL_EMOJIS = new ArrayList<>();
    private static EmojiTrie EMOJI_TRIE;
    private static boolean initialized = false;

    static void initialize(Map<String, Emoji> emojiByUnicode, Map<String, Emoji> emojiByAlias, List<Emoji> allEmojis) {
        if (initialized) {
            return;
        }


        EMOJI_BY_UNICODE.clear();
        EMOJI_BY_UNICODE.putAll(emojiByUnicode);

        EMOJI_BY_ALIAS.clear();
        EMOJI_BY_ALIAS.putAll(emojiByAlias);

        ALL_EMOJIS.clear();
        ALL_EMOJIS.addAll(allEmojis);

        EMOJI_TRIE = new EmojiTrie(ALL_EMOJIS);

        initialized = true;
    }

    public static Emoji getByUnicode(String unicode) {
        if (unicode == null) return null;
        return EMOJI_TRIE.getEmoji(unicode);
    }

    public static int getEmojiEndPos(char[] text, int startPos) {
        if (!initialized) {
            throw new IllegalStateException("EmojiManager has not been initialized. Call initialize() first.");
        }

        int best = -1;
        for (int j = startPos + 1; j <= text.length; j++) {
            EmojiTrie.Matches status = EMOJI_TRIE.isEmoji(text, startPos, j);
            if (status.exactMatch()) {
                best = j;
            } else if (status.impossibleMatch()) {
                return best;
            }
        }
        return best;
    }
}