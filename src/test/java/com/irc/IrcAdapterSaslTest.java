package com.irc;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Guards the adapter's handling of the SASL result numerics: success and failure must each
 * surface a system message to the user (failure without disconnecting), replacing the
 * feedback the old NickServ IDENTIFY NOTICE used to provide.
 */
public class IrcAdapterSaslTest {

    private static IrcConfig stubConfig() {
        return new IrcConfig() {
            @Override
            public String username() {
                return "mynick";
            }

            @Override
            public String accountName() {
                return "myaccount";
            }

            @Override
            public String password() {
                return "secret";
            }
        };
    }

    @Test
    public void saslSuccessNumericSurfacesSystemMessage() {
        List<IrcMessage> messages = new ArrayList<>();
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), messages::add, null, "mynick");

        adapter.getClient().processLine(":server 903 mynick :SASL authentication successful");

        assertTrue("SASL success should produce a system message mentioning SASL",
                messages.stream().anyMatch(m ->
                        m.getContent() != null && m.getContent().toLowerCase().contains("sasl")));
    }

    @Test
    public void saslFailureNumericSurfacesSystemMessage() {
        List<IrcMessage> messages = new ArrayList<>();
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), messages::add, null, "mynick");

        adapter.getClient().processLine(":server 904 mynick :Bad password");

        assertTrue("SASL failure should produce a system message mentioning SASL",
                messages.stream().anyMatch(m ->
                        m.getContent() != null && m.getContent().toLowerCase().contains("sasl")));
    }
}
