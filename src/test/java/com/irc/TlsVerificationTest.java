package com.irc;

import com.google.gson.Gson;
import org.junit.Test;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** The per-network "Verify TLS certificate" option, from storage down to the socket. */
public class TlsVerificationTest {
    private static final String WARNING = "TLS certificate verification is off for this network.";

    private static NetworkConfig znc() {
        return NetworkConfig.builder().id("z1").name("ZNC").host("10.0.0.5").port(1025).build();
    }

    private static IrcConfig stubConfig() {
        return new IrcConfig() {
            @Override public String username() { return "me"; }
            @Override public String password() { return ""; }
        };
    }

    @Test
    public void verificationIsOnByDefaultAndForSwiftIrc() {
        assertTrue(znc().isVerifyTls());
        assertTrue(NetworkConfig.swiftIrc(stubConfig()).isVerifyTls());
    }

    @Test
    public void aSavedNetworkWithoutTheSettingStillVerifies() {
        List<NetworkConfig> parsed = NetworkStore.parse(new Gson(),
                "[{\"id\":\"z1\",\"name\":\"ZNC\",\"host\":\"10.0.0.5\"}]");
        assertTrue(parsed.get(0).isVerifyTls());
    }

    @Test
    public void theSettingRoundTrips() {
        Gson gson = new Gson();
        NetworkConfig unverified = znc().toBuilder().verifyTls(false).build();
        assertEquals(Collections.singletonList(unverified),
                NetworkStore.parse(gson, NetworkStore.serialize(gson, Collections.singletonList(unverified))));
    }

    @Test
    public void changingItNeedsAReconnect() {
        assertTrue(znc().connectionDiffers(znc().toBuilder().verifyTls(false).build()));
    }

    @Test
    public void unverifiedSocketsSkipTheHostnameCheck() throws Exception {
        SSLSocket verified = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
        SimpleIrcClient.applyTlsSettings(verified, true);
        assertEquals("HTTPS", verified.getSSLParameters().getEndpointIdentificationAlgorithm());

        SSLSocket unverified = (SSLSocket) SimpleIrcClient.socketFactory(false).createSocket();
        SimpleIrcClient.applyTlsSettings(unverified, false);
        assertNull(unverified.getSSLParameters().getEndpointIdentificationAlgorithm());
    }

    @Test
    public void theUnverifiedTrustManagerAcceptsAnyChain() throws Exception {
        SimpleIrcClient.TRUST_ANY.checkServerTrusted(new X509Certificate[0], "RSA");
        assertEquals(0, SimpleIrcClient.TRUST_ANY.getAcceptedIssuers().length);
    }

    @Test
    public void theAdapterPassesTheSettingToItsClient() {
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), znc().toBuilder().verifyTls(false).build(), m -> { }, null, "me");
        assertFalse(adapter.getClient().isVerifyingTls());
    }

    @Test
    public void anUnverifiedConnectionWarnsInItsSystemBufferOnRegistration() {
        assertEquals(1, warningsOnConnect(znc().toBuilder().verifyTls(false).build()));
        assertEquals(0, warningsOnConnect(znc()));
        // Without TLS there is no certificate to verify, so nothing to warn about.
        assertEquals(0, warningsOnConnect(znc().toBuilder().tls(false).verifyTls(false).build()));
    }

    private static int warningsOnConnect(NetworkConfig network) {
        List<IrcMessage> messages = new ArrayList<>();
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(stubConfig(), network, messages::add, null, "me");
        adapter.getClient().processLine(":server 001 me :Welcome");
        int warnings = 0;
        for (IrcMessage message : messages) {
            if (WARNING.equals(message.getContent())) {
                assertEquals("System", message.getChannel());
                assertEquals(network.getId(), message.getNetworkId());
                warnings++;
            }
        }
        return warnings;
    }
}
