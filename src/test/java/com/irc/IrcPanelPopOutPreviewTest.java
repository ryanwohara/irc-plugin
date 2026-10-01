package com.irc;

import com.google.common.cache.Cache;
import okhttp3.OkHttpClient;
import org.junit.Before;
import org.junit.Test;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertTrue;

public class IrcPanelPopOutPreviewTest {
    private static final String IMAGE_URL = "https://example.com/preview.png";

    @Before
    public void requiresDesktop() {
        org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
    }

    @Test
    public void previewShowsDocked() throws Exception {
        assertTrue("preview should show docked", previewShown(true, false));
    }

    @Test
    public void previewShowsInPopOutWithSidebarEnabled() throws Exception {
        assertTrue("preview should show in the pop-out", previewShown(true, true));
    }

    @Test
    public void previewShowsInPopOutWithSidebarDisabled() throws Exception {
        assertTrue("preview should show in the pop-out", previewShown(false, true));
    }

    private boolean previewShown(boolean sidebarInWindow, boolean poppedOut) throws Exception {
        AtomicReference<IrcPanel> panelRef = new AtomicReference<>();
        AtomicReference<JFrame> mainRef = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            IrcPanel panel = new IrcPanel();
            panelRef.set(panel);
            setField(IrcPanel.class, panel, "config", new IrcConfig() {
                @Override public String username() { return "tester"; }
                @Override public String password() { return ""; }
                @Override public boolean hoverPreviewImages() { return true; }
            });
            setField(IrcPanel.class, panel, "okHttpClient", new OkHttpClient());
            panel.init((channel, text) -> {}, (channel, password) -> {}, channel -> {},
                    reconnect -> {}, query -> {}, () -> {});
            panel.initializeGui();
            if (sidebarInWindow) {
                JFrame main = new JFrame();
                mainRef.set(main);
                main.add(panel.getWrappedPanel());
                main.setSize(260, 600);
                main.setVisible(true);
            }
            panel.addChannel("#test");
        });
        try {
            SwingUtilities.invokeAndWait(() -> {
                IrcPanel panel = panelRef.get();
                panel.setFocusedChannel("#test");
                if (poppedOut) panel.setDetached(true, false);
            });
            // Let window activation events from opening the frames arrive before hovering.
            Thread.sleep(1000);
            SwingUtilities.invokeAndWait(() -> {
                IrcPanel panel = panelRef.get();
                IrcPanel.ChannelPane pane = panel.getChannelPanes().get("#test");
                seedCache(pane);
                try {
                    MouseEvent move = new MouseEvent(pane, MouseEvent.MOUSE_MOVED,
                            System.currentTimeMillis(), 0, 10, 10, 0, false);
                    pane.fireHyperlinkUpdate(new HyperlinkEvent(pane, HyperlinkEvent.EventType.ENTERED,
                            new URL(IMAGE_URL), null, null, move));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            long deadline = System.currentTimeMillis() + 3000;
            AtomicBoolean shown = new AtomicBoolean();
            while (System.currentTimeMillis() < deadline) {
                SwingUtilities.invokeAndWait(() -> shown.set(currentPopup(panelRef.get()) != null));
                if (shown.get()) return true;
                Thread.sleep(50);
            }
            return false;
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                panelRef.get().shutdown();
                if (mainRef.get() != null) mainRef.get().dispose();
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static void seedCache(IrcPanel.ChannelPane pane) {
        try {
            BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            PreviewManager manager = (PreviewManager) getField(IrcPanel.ChannelPane.class, pane, "previewManager");
            ((Cache<String, byte[]>) getField(PreviewManager.class, manager, "imageCache"))
                    .put(IMAGE_URL, bytes.toByteArray());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Object currentPopup(IrcPanel panel) {
        IrcPanel.ChannelPane pane = panel.getChannelPanes().get("#test");
        PreviewManager manager = (PreviewManager) getField(IrcPanel.ChannelPane.class, pane, "previewManager");
        return getField(PreviewManager.class, manager, "currentImagePreview");
    }

    private static Object getField(Class<?> type, Object target, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setField(Class<?> type, Object target, String name, Object value) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
