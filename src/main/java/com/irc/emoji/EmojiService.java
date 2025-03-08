package com.irc.emoji;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Singleton
public class EmojiService {
    private static final String EMOJI_JSON_PATH = "/emojis.json";

    private final Gson gson;

    @Inject
    public EmojiService(Gson gson) {
        this.gson = gson;
    }

    public void initialize() {
        try {
            log.debug("Initializing emoji data");
            loadEmojis();
            log.debug("Successfully initialized emoji data");
        } catch (Exception e) {
            log.error("Failed to initialize emoji data", e);
            throw new RuntimeException("Failed to initialize emoji data", e);
        }
    }

    private void loadEmojis() throws Exception {
        Map<String, Emoji> emojiByUnicode = new HashMap<>();
        Map<String, Emoji> emojiByAlias = new HashMap<>();
        List<Emoji> allEmojis = new ArrayList<>();

        try (InputStream is = EmojiManager.class.getResourceAsStream(EMOJI_JSON_PATH)) {
            if (is == null) {
                throw new RuntimeException("Could not find resource: " + EMOJI_JSON_PATH);
            }

            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                JsonArray emojiArray = gson.fromJson(reader, JsonArray.class);

                if (emojiArray == null) {
                    throw new RuntimeException("Failed to parse emoji JSON or file is empty");
                }

                for (int i = 0; i < emojiArray.size(); i++) {
                    JsonObject emojiJson = emojiArray.get(i).getAsJsonObject();
                    if (!emojiJson.has("emoji")) continue;

                    String unicode = emojiJson.get("emoji").getAsString();
                    boolean supportsFitzpatrick = emojiJson.has("supports_fitzpatrick") ?
                            emojiJson.get("supports_fitzpatrick").getAsBoolean() : false;

                    List<String> aliases = new ArrayList<>();
                    if (emojiJson.has("aliases")) {
                        JsonArray aliasesJson = emojiJson.getAsJsonArray("aliases");
                        for (int j = 0; j < aliasesJson.size(); j++) {
                            aliases.add(aliasesJson.get(j).getAsString());
                        }
                    }

                    Emoji emoji = new Emoji(unicode, aliases, supportsFitzpatrick);
                    emojiByUnicode.put(unicode, emoji);
                    allEmojis.add(emoji);

                    for (String alias : aliases) {
                        emojiByAlias.put(alias, emoji);
                    }
                }
            }
        }

        EmojiManager.initialize(emojiByUnicode, emojiByAlias, allEmojis);
    }
}