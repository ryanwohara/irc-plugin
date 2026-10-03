package com.irc;

import com.google.gson.Gson;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class NetworkStoreTest {
    private final Gson gson = new Gson();

    private static NetworkConfig rizon() {
        return NetworkConfig.builder().id("a1").name("Rizon").host("irc.rizon.net").port(6697).tls(true)
                .nick("Mikey").serverPassword("").saslAccount("mikey").saslPassword("pw")
                .autojoin("#foo,#bar").enabled(true).build();
    }

    @Test
    public void roundTripsEveryField() {
        NetworkConfig znc = NetworkConfig.builder().id("b2").name("ZNC").host("znc.example.net").port(1025)
                .tls(false).nick("").serverPassword("me/libera:pass word").saslAccount("").saslPassword("")
                .autojoin("").enabled(false).build();
        List<NetworkConfig> parsed = NetworkStore.parse(gson, NetworkStore.serialize(gson, Arrays.asList(rizon(), znc)));
        assertEquals(Arrays.asList(rizon(), znc), parsed);
    }

    @Test
    public void missingFieldsTakeDefaults() {
        List<NetworkConfig> parsed = NetworkStore.parse(gson,
                "[{\"id\":\"a1\",\"name\":\"Rizon\",\"host\":\"irc.rizon.net\"}]");
        NetworkConfig n = parsed.get(0);
        assertEquals(6697, n.getPort());
        assertTrue(n.isTls());
        assertTrue(n.isEnabled());
        assertEquals("", n.getNick());
        assertEquals("", n.getServerPassword());
        assertEquals("", n.getAutojoin());
    }

    @Test
    public void blankOrCorruptInputYieldsNoNetworks() {
        assertEquals(Collections.emptyList(), NetworkStore.parse(gson, ""));
        assertEquals(Collections.emptyList(), NetworkStore.parse(gson, null));
        assertEquals(Collections.emptyList(), NetworkStore.parse(gson, "{not json"));
        assertEquals(Collections.emptyList(), NetworkStore.parse(gson, "{\"id\":\"a1\"}"));
    }

    @Test
    public void skipsInvalidDuplicateAndReservedEntries() {
        String json = "["
                + "{\"name\":\"NoId\",\"host\":\"h\"},"
                + "{\"id\":\"x\",\"name\":\"NoHost\"},"
                + "{\"id\":\"y\",\"host\":\"h\"},"
                + "{\"id\":\"swiftirc\",\"name\":\"Fake\",\"host\":\"h\"},"
                + "{\"id\":\"a1\",\"name\":\"Rizon\",\"host\":\"irc.rizon.net\"},"
                + "{\"id\":\"a1\",\"name\":\"Dupe\",\"host\":\"h\"},"
                + "null]";
        List<NetworkConfig> parsed = NetworkStore.parse(gson, json);
        assertEquals(1, parsed.size());
        assertEquals("Rizon", parsed.get(0).getName());
    }

    @Test
    public void autojoinChannelsAreTrimmedAndPrefixed() {
        NetworkConfig n = rizon().toBuilder().autojoin(" #a, b ,,&c ").build();
        assertEquals(Arrays.asList("#a", "#b", "&c"), n.autojoinChannels());
    }

    @Test
    public void onlyConnectionFieldsNeedAReconnect() {
        NetworkConfig base = rizon();
        assertFalse(base.connectionDiffers(base.toBuilder().name("Rizon2").autojoin("#x").enabled(false).build()));
        assertTrue(base.connectionDiffers(base.toBuilder().port(6667).build()));
        assertTrue(base.connectionDiffers(base.toBuilder().host("irc2.rizon.net").build()));
        assertTrue(base.connectionDiffers(base.toBuilder().tls(false).build()));
        assertTrue(base.connectionDiffers(base.toBuilder().nick("Other").build()));
        assertTrue(base.connectionDiffers(base.toBuilder().serverPassword("p").build()));
        assertTrue(base.connectionDiffers(base.toBuilder().saslPassword("p2").build()));
    }

    @Test
    public void swiftIrcIsBuiltFromTheConnectionSettings() {
        NetworkConfig swift = NetworkConfig.swiftIrc(new IrcConfig() {
            @Override public String username() { return "Mikey"; }
            @Override public String password() { return "secret"; }
            @Override public String accountName() { return "acct"; }
        });
        assertEquals(NetworkConfig.SWIFTIRC_ID, swift.getId());
        assertEquals("SwiftIRC", swift.getName());
        assertEquals("fiery.swiftirc.net", swift.getHost());
        assertEquals(6697, swift.getPort());
        assertTrue(swift.isTls());
        assertEquals("acct", swift.getSaslAccount());
        assertEquals("secret", swift.getSaslPassword());
        assertEquals("#rshelp", swift.getAutojoin());
        assertTrue(swift.isBuiltIn());
    }

    @Test
    public void swiftIrcToleratesANullPassword() {
        NetworkConfig swift = NetworkConfig.swiftIrc(new IrcConfig() {
            @Override public String username() { return "Mikey"; }
            @Override public String password() { return null; }
        });
        assertEquals("", swift.getSaslPassword());
    }
}
