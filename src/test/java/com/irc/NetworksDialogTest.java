package com.irc;

import org.junit.Test;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class NetworksDialogTest {
    private static NetworkConfig builtIn() {
        return NetworkConfig.builder().id(NetworkConfig.SWIFTIRC_ID).name("SwiftIRC").host("fiery.swiftirc.net").build();
    }

    private static NetworkConfig rizon() {
        return NetworkConfig.builder().id("r1").name("Rizon").host("irc.rizon.net").port(6697).tls(true)
                .nick("Mikey").serverPassword("").saslAccount("mikey").saslPassword("pw").autojoin("#foo").build();
    }

    @Test
    public void validateRejectsIncompleteOrClashingNetworks() {
        List<NetworkConfig> others = Arrays.asList(builtIn(), rizon());
        NetworkConfig fresh = rizon().toBuilder().id("new").name("Libera").host("irc.libera.chat").build();
        assertNull(NetworksDialog.validate(fresh, others));
        assertNotNull(NetworksDialog.validate(fresh.toBuilder().name("  ").build(), others));
        assertNotNull(NetworksDialog.validate(fresh.toBuilder().host("").build(), others));
        assertNotNull(NetworksDialog.validate(fresh.toBuilder().port(0).build(), others));
        assertNotNull(NetworksDialog.validate(fresh.toBuilder().port(65536).build(), others));
        assertNotNull(NetworksDialog.validate(fresh.toBuilder().name("rizon").build(), others));
        assertNotNull(NetworksDialog.validate(fresh.toBuilder().name("swiftirc").build(), others));
        // Editing Rizon itself does not clash with Rizon.
        assertNull(NetworksDialog.validate(rizon().toBuilder().port(7000).build(), others));
    }

    @Test
    public void formRoundTripsANetwork() {
        assertEquals(rizon(), new NetworksDialog.NetworkForm(rizon()).toConfig());
    }

    @Test
    public void nonNumericPortFailsValidation() {
        NetworksDialog.NetworkForm form = new NetworksDialog.NetworkForm(rizon());
        form.port.setText("abc");
        assertNotNull(NetworksDialog.validate(form.toConfig(), Collections.singletonList(builtIn())));
    }

    @Test
    public void formRoundTripsTlsVerification() {
        NetworkConfig unverified = rizon().toBuilder().verifyTls(false).build();
        assertEquals(unverified, new NetworksDialog.NetworkForm(unverified).toConfig());
    }

    @Test
    public void verifyTlsOnlyAppliesWhileTlsIsOn() {
        NetworksDialog.NetworkForm form = new NetworksDialog.NetworkForm(rizon());
        assertTrue(form.verifyTls.isSelected());
        assertTrue(form.verifyTls.isEnabled());
        assertFalse(form.tlsWarning.isVisible());

        form.verifyTls.doClick();
        assertTrue("TLS on, verification off: warn", form.tlsWarning.isVisible());

        form.tls.doClick();
        assertFalse(form.verifyTls.isEnabled());
        assertFalse("no TLS, nothing to verify", form.tlsWarning.isVisible());

        form.tls.doClick();
        assertTrue(form.verifyTls.isEnabled());
        assertTrue(form.tlsWarning.isVisible());
    }

    @Test
    public void zncPresetLeavesVerificationAlone() {
        NetworksDialog.NetworkForm form = new NetworksDialog.NetworkForm(rizon().toBuilder().tls(false).build());
        form.applyZncPreset();
        assertTrue(form.verifyTls.isSelected());
        assertTrue(form.verifyTls.isEnabled());
        assertTrue(form.zncHint.getText().contains("Verify TLS"));
    }

    @Test
    public void zncPresetTurnsOnTlsAndShowsTheHint() {
        NetworksDialog.NetworkForm form = new NetworksDialog.NetworkForm(rizon().toBuilder().tls(false).build());
        assertFalse(form.zncHint.isVisible());
        form.applyZncPreset();
        assertTrue(form.tls.isSelected());
        assertTrue(form.zncHint.isVisible());
    }

    /**
     * The form is shown in a JOptionPane dialog sized when it opens. Rows that appear later (the
     * ZNC hint, the unverified-TLS warning) must grow the dialog, or they push OK/Cancel out of it.
     */
    @Test
    public void revealedRowsKeepTheDialogButtonsOnScreen() throws Exception {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            NetworksDialog.NetworkForm form = new NetworksDialog.NetworkForm(rizon().toBuilder().tls(false).build());
            JDialog dialog = new JOptionPane(form, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION)
                    .createDialog(null, "Edit");
            try {
                dialog.pack();
                assertButtonsFit(dialog);
                form.applyZncPreset();
                assertButtonsFit(dialog);
                form.verifyTls.doClick();
                assertTrue(form.tlsWarning.isVisible());
                assertButtonsFit(dialog);
            } finally {
                dialog.dispose();
            }
        });
    }

    private static void assertButtonsFit(JDialog dialog) {
        dialog.validate();
        Container content = dialog.getContentPane();
        JButton ok = findButton(content, "OK");
        assertNotNull(ok);
        Rectangle bounds = SwingUtilities.convertRectangle(ok.getParent(), ok.getBounds(), content);
        assertTrue("OK button is cut off: " + bounds + " in " + content.getSize(),
                bounds.y + bounds.height <= content.getHeight() && bounds.x + bounds.width <= content.getWidth());
    }

    private static JButton findButton(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton && text.equals(((JButton) child).getText())) return (JButton) child;
            if (child instanceof Container) {
                JButton found = findButton((Container) child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    public void duplicateGetsANewIdAndACopyName() {
        NetworkConfig copy = NetworksDialog.duplicateOf(rizon());
        assertNotEquals(rizon().getId(), copy.getId());
        assertEquals("Rizon (copy)", copy.getName());
        assertEquals(rizon().getHost(), copy.getHost());
        assertEquals(rizon().getSaslPassword(), copy.getSaslPassword());
    }

    @Test
    public void describeShowsStateAddressAndDisabled() {
        assertEquals("● Rizon   irc.rizon.net:6697", NetworksDialog.describe(rizon(), true));
        assertEquals("○ Rizon   irc.rizon.net:6697  (disabled)",
                NetworksDialog.describe(rizon().toBuilder().enabled(false).build(), false));
    }

    @Test
    public void builtInHasAShortFormAndCanBeToggled() throws Exception {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        List<String> calls = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            NetworksDialog dialog = new NetworksDialog(null, builtIn().toBuilder().enabled(false).build(),
                    Collections.singletonList(rizon()), Collections.emptyList(), recorder(calls));
            try {
                dialog.selectNetwork(NetworkConfig.SWIFTIRC_ID);
                assertTrue("SwiftIRC has its own short form", find(dialog, "ircNetworkEdit").isEnabled());
                assertFalse(find(dialog, "ircNetworkRemove").isEnabled());
                assertFalse(find(dialog, "ircNetworkDuplicate").isEnabled());
                JButton connect = (JButton) find(dialog, "ircNetworkConnect");
                assertTrue("a network that doesn't connect automatically can still be connected by hand",
                        connect.isEnabled());
                assertEquals("Connect", connect.getText());

                dialog.selectNetwork("r1");
                assertTrue(find(dialog, "ircNetworkEdit").isEnabled());
                assertEquals("Disconnect", connect.getText());
                connect.doClick();
                assertEquals(Collections.singletonList("r1 false"), calls);
            } finally {
                dialog.dispose();
            }
        });
    }

    @Test
    public void onlyOneNetworkShowsInGameChat() throws Exception {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        List<String> calls = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            NetworksDialog dialog = new NetworksDialog(null, builtIn(), Collections.singletonList(rizon()),
                    Collections.emptyList(), recorder(calls));
            try {
                assertEquals(NetworkConfig.SWIFTIRC_ID, dialog.inGameNetwork());
                dialog.applyInGameChoice("r1", true);
                assertEquals("r1", dialog.inGameNetwork());
                dialog.applyInGameChoice("r1", true);
                // Unticking a network that isn't the in-game one changes nothing.
                dialog.applyInGameChoice(NetworkConfig.SWIFTIRC_ID, false);
                assertEquals("r1", dialog.inGameNetwork());
                // Unticking the in-game network hands game chat back to SwiftIRC.
                dialog.applyInGameChoice("r1", false);
                assertEquals(NetworkConfig.SWIFTIRC_ID, dialog.inGameNetwork());
                assertEquals(java.util.Arrays.asList("in game r1", "in game swiftirc"), calls);
            } finally {
                dialog.dispose();
            }
        });
    }

    @Test
    public void removingTheInGameNetworkHandsGameChatBackToSwiftIrc() throws Exception {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        List<String> calls = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            NetworksDialog dialog = new NetworksDialog(null, builtIn(), Collections.singletonList(rizon()),
                    Collections.emptyList(), recorder(calls));
            try {
                dialog.setInGameNetwork("r1");
                dialog.removeNetwork("r1");
                assertEquals(NetworkConfig.SWIFTIRC_ID, dialog.inGameNetwork());
                assertTrue(calls.contains("save 0"));
                assertTrue(calls.contains("in game swiftirc"));
            } finally {
                dialog.dispose();
            }
        });
    }

    @Test
    public void builtInFormHoldsItsTwoSwitches() {
        NetworksDialog.BuiltInForm form = new NetworksDialog.BuiltInForm(false, true);
        assertFalse(form.enabled.isSelected());
        assertTrue(form.inGame.isSelected());
    }

    @Test
    public void networkFormCarriesTheInGameChoice() {
        assertTrue(new NetworksDialog.NetworkForm(rizon(), true).inGame.isSelected());
        assertFalse(new NetworksDialog.NetworkForm(rizon()).inGame.isSelected());
    }

    @Test
    public void describeMarksTheInGameNetwork() {
        assertEquals("● Rizon   irc.rizon.net:6697  (in game)", NetworksDialog.describe(rizon(), true, true));
        assertEquals("○ Rizon   irc.rizon.net:6697  (disabled)  (in game)",
                NetworksDialog.describe(rizon().toBuilder().enabled(false).build(), false, true));
    }

    private static NetworksDialog.Callbacks recorder(List<String> calls) {
        return new NetworksDialog.Callbacks() {
            @Override public void save(List<NetworkConfig> extras) { calls.add("save " + extras.size()); }
            @Override public void saveOrder(List<String> ids) { calls.add("order " + ids); }
            @Override public boolean isConnected(String id) { return "r1".equals(id); }
            @Override public void setConnected(String id, boolean c) { calls.add(id + " " + c); }
            @Override public void saveBuiltIn(boolean enabled) { calls.add("swiftirc enabled " + enabled); }
            @Override public void saveInGameNetwork(String id) { calls.add("in game " + id); }
        };
    }

    @Test
    public void moveUpAndDownReorderEveryNetworkAndSaveTheOrder() throws Exception {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        List<String> calls = new ArrayList<>();
        NetworkConfig libera = rizon().toBuilder().id("l2").name("Libera").host("irc.libera.chat").build();
        SwingUtilities.invokeAndWait(() -> {
            NetworksDialog dialog = new NetworksDialog(null, builtIn(), Arrays.asList(rizon(), libera),
                    Arrays.asList("r1", "gone", NetworkConfig.SWIFTIRC_ID), new NetworksDialog.Callbacks() {
                        @Override public void save(List<NetworkConfig> extras) {
                            List<String> ids = new ArrayList<>();
                            for (NetworkConfig extra : extras) ids.add(extra.getId());
                            calls.add("save " + ids);
                        }
                        @Override public void saveOrder(List<String> ids) { calls.add("order " + ids); }
                        @Override public boolean isConnected(String id) { return false; }
                        @Override public void setConnected(String id, boolean c) { }
                    });
            try {
                JList<?> list = (JList<?>) find(dialog, "ircNetworkList");
                JButton up = (JButton) find(dialog, "ircNetworkUp");
                JButton down = (JButton) find(dialog, "ircNetworkDown");
                assertEquals(Arrays.asList("r1", NetworkConfig.SWIFTIRC_ID, "l2"), ids(list));

                dialog.selectNetwork("r1");
                assertFalse(up.isEnabled());
                assertTrue(down.isEnabled());
                dialog.selectNetwork("l2");
                assertTrue(up.isEnabled());
                assertFalse(down.isEnabled());

                // SwiftIRC moves like any other network.
                dialog.selectNetwork(NetworkConfig.SWIFTIRC_ID);
                down.doClick();
                assertEquals(Arrays.asList("r1", "l2", NetworkConfig.SWIFTIRC_ID), ids(list));
                assertEquals(NetworkConfig.SWIFTIRC_ID, ((NetworkConfig) list.getSelectedValue()).getId());
                dialog.selectNetwork("l2");
                up.doClick();
                assertEquals(Arrays.asList("l2", "r1", NetworkConfig.SWIFTIRC_ID), ids(list));
                assertEquals(Arrays.asList(
                        "save [r1, l2]", "order [r1, l2, swiftirc]",
                        "save [l2, r1]", "order [l2, r1, swiftirc]"), calls);

                // Reloading from the saved settings keeps the order.
                dialog.setNetworks(builtIn(), Arrays.asList(libera, rizon()), Arrays.asList("l2", "r1", NetworkConfig.SWIFTIRC_ID));
                assertEquals(Arrays.asList("l2", "r1", NetworkConfig.SWIFTIRC_ID), ids(list));
            } finally {
                dialog.dispose();
            }
        });
    }

    private static List<String> ids(JList<?> list) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < list.getModel().getSize(); i++) ids.add(((NetworkConfig) list.getModel().getElementAt(i)).getId());
        return ids;
    }

    private static Component find(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container) {
                Component found = find((Container) child, name);
                if (found != null) return found;
            }
        }
        return null;
    }
}
