package com.irc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Guards the contract that IrcPlugin relies on for /umode: the nick reported by the
 * adapter must follow the network's confirmed nick, i.e. it is driven by the raw NICK
 * event rather than an optimistic local guess. Previously IrcPlugin kept its own copy
 * of the nick that was set once at connect and never updated, so /nick desynced it from
 * the network.
 */
public class IrcAdapterNickTest {

    private static IrcConfig stubConfig() {
        // IrcConfig only has two abstract methods; everything else is a default.
        return new IrcConfig() {
            @Override
            public String username() {
                return "mynick";
            }

            @Override
            public String password() {
                return "";
            }
        };
    }

    @Test
    public void getNickFollowsRawNickEvent() {
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), message -> {}, null, "mynick");

        assertEquals("mynick", adapter.getNick());

        // The server echoes our own nick change back to us. The adapter must adopt the
        // new nick from this raw NICK line, not from any optimistic local update.
        adapter.getClient().processLine(":mynick!runelite@host NICK newnick");

        assertEquals("newnick", adapter.getNick());
    }

    @Test
    public void getNickIgnoresOtherUsersNickChanges() {
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), message -> {}, null, "mynick");

        // Someone else changing their nick must not move our own nick.
        adapter.getClient().processLine(":someoneelse!u@host NICK othernew");

        assertEquals("mynick", adapter.getNick());
    }

    @Test
    public void nickTakenDuringRegistrationAdoptsServerAssignedNick() {
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), message -> {}, null, "mynick");
        SimpleIrcClient client = adapter.getClient();

        // Server rejects our requested nick (433 ERR_NICKNAMEINUSE) ...
        client.processLine(":server 433 * mynick :Nickname is already in use");
        // ... we retry and the server welcomes us as mynick_ (001 RPL_WELCOME).
        client.processLine(":server 001 mynick_ :Welcome to IRC mynick_!runelite@host");

        assertEquals("mynick_", client.getNick());
        assertEquals("mynick_", adapter.getNick());
    }

    @Test
    public void welcomeNumericIsAuthoritativeForNick() {
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), message -> {}, null, "mynick");
        SimpleIrcClient client = adapter.getClient();

        // The server may register us under a nick that differs from what we asked for;
        // RPL_WELCOME is the network's authoritative statement of our nick.
        client.processLine(":server 001 ServerChosen :Welcome to IRC ServerChosen!runelite@host");

        assertEquals("ServerChosen", client.getNick());
        assertEquals("ServerChosen", adapter.getNick());
    }
}
