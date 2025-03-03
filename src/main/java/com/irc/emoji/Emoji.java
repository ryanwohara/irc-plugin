package com.irc.emoji;

import java.util.Collections;
import java.util.List;

public class Emoji {
    private final String unicode;
    private final List<String> aliases;
    private final boolean supportsFitzpatrick;

    public Emoji(String unicode, List<String> aliases, boolean supportsFitzpatrick) {
        this.unicode = unicode;
        this.aliases = Collections.unmodifiableList(aliases);
        this.supportsFitzpatrick = supportsFitzpatrick;
    }

    public String getUnicode() {
        return unicode;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public boolean supportsFitzpatrick() {
        return supportsFitzpatrick;
    }

    public String getUnicode(Fitzpatrick fitzpatrick) {
        if (!supportsFitzpatrick) {
            throw new UnsupportedOperationException("This emoji doesn't support Fitzpatrick modifiers");
        }
        return fitzpatrick == null ? unicode : unicode + fitzpatrick.unicode;
    }
}