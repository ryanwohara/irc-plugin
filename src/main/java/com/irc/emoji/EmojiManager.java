package com.irc.emoji;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class EmojiManager {
    private static final String EMOJI_JSON_PATH = "/emojis.json";
    private static final Map<String, Emoji> EMOJI_BY_UNICODE = new HashMap<>();
    private static final Map<String, Emoji> EMOJI_BY_ALIAS = new HashMap<>();
    private static final List<Emoji> ALL_EMOJIS = new ArrayList<>();
    public static final EmojiTrie EMOJI_TRIE;

    static {
        try {
            loadEmojis();
            EMOJI_TRIE = new EmojiTrie(ALL_EMOJIS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load emoji data", e);
        }
    }

    private static void loadEmojis() throws Exception {
        try (InputStream is = EmojiManager.class.getResourceAsStream(EMOJI_JSON_PATH);
             Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {

            Gson gson = new Gson();
            JsonArray emojiArray = gson.fromJson(reader, JsonArray.class);

            for (int i = 0; i < emojiArray.size(); i++) {
                JsonObject emojiJson = emojiArray.get(i).getAsJsonObject();
                if (!emojiJson.has("emoji")) continue;

                String unicode = emojiJson.get("emoji").getAsString();
                boolean supportsFitzpatrick = emojiJson.has("supports_fitzpatrick") ?
                        emojiJson.get("supports_fitzpatrick").getAsBoolean() : false;

                List<String> aliases = new ArrayList<>();
                JsonArray aliasesJson = emojiJson.getAsJsonArray("aliases");
                for (int j = 0; j < aliasesJson.size(); j++) {
                    aliases.add(aliasesJson.get(j).getAsString());
                }

                Emoji emoji = new Emoji(unicode, aliases, supportsFitzpatrick);
                EMOJI_BY_UNICODE.put(unicode, emoji);
                ALL_EMOJIS.add(emoji);

                for (String alias : aliases) {
                    EMOJI_BY_ALIAS.put(alias, emoji);
                }
            }
        }
    }

    public static Emoji getByUnicode(String unicode) {
        if (unicode == null) return null;
        return EMOJI_TRIE.getEmoji(unicode);
    }

    public static Emoji getByAlias(String alias) {
        if (alias == null) return null;
        // Remove colons if present
        if (alias.startsWith(":") && alias.endsWith(":")) {
            alias = alias.substring(1, alias.length() - 1);
        }
        return EMOJI_BY_ALIAS.get(alias);
    }

    public static Collection<Emoji> getAll() {
        return ALL_EMOJIS;
    }

    public static boolean containsEmoji(String string) {
        if (string == null) return false;
        return getEmojiEndPos(string.toCharArray(), 0) != -1;
    }

    public static int getEmojiEndPos(char[] text, int startPos) {
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