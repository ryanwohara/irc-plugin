package com.irc;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Moves the live chat controls between hosts. All methods run on the Swing EDT. */
final class IrcPanelWindow {
    private final JPanel dockHost;
    private final JPanel content;
    private final Runnable beforeMove;
    private final Runnable hidePreviews;
    private final Runnable dockRequested;
    /** The saved {@link PopOutGeometry}, serialized. */
    private final Supplier<String> loadGeometry;
    private final Consumer<String> saveGeometry;
    private JFrame frame;
    /** The pop-out's bounds when last neither maximized nor minimized. */
    private Rectangle normalBounds;
    /** Saves once a move or resize settles, rather than on every step of a drag. */
    private final Timer saveTimer = new Timer(500, e -> saveGeometry());
    private final ComponentAdapter frameGeometryListener = new ComponentAdapter() {
        @Override
        public void componentMoved(ComponentEvent e) { geometryChanged(); }

        @Override
        public void componentResized(ComponentEvent e) { geometryChanged(); }
    };
    private Window observedWindow;
    private final WindowAdapter windowListener = new WindowAdapter() {
        @Override
        public void windowLostFocus(WindowEvent e) { hidePreviews.run(); }

        @Override
        public void windowDeactivated(WindowEvent e) { hidePreviews.run(); }

        @Override
        public void windowIconified(WindowEvent e) { hidePreviews.run(); }
    };
    private final ComponentAdapter componentListener = new ComponentAdapter() {
        @Override
        public void componentMoved(ComponentEvent e) { hidePreviews.run(); }

        @Override
        public void componentResized(ComponentEvent e) { hidePreviews.run(); }
    };
    private final HierarchyListener hierarchyListener = this::hierarchyChanged;

    private void hierarchyChanged(HierarchyEvent e) {
        if ((e.getChangeFlags() & (HierarchyEvent.PARENT_CHANGED | HierarchyEvent.SHOWING_CHANGED)) != 0) {
            observeWindow(SwingUtilities.getWindowAncestor(content));
            if (!content.isShowing()) {
                hidePreviews.run();
            }
        }
    }

    /** Remembers the pop-out's geometry only while this object lives. */
    IrcPanelWindow(JPanel dockHost, JPanel content, Runnable beforeMove,
                   Runnable hidePreviews, Runnable dockRequested) {
        this(dockHost, content, beforeMove, hidePreviews, dockRequested, new AtomicReference<>());
    }

    private IrcPanelWindow(JPanel dockHost, JPanel content, Runnable beforeMove,
                           Runnable hidePreviews, Runnable dockRequested, AtomicReference<String> memory) {
        this(dockHost, content, beforeMove, hidePreviews, dockRequested, memory::get, memory::set);
    }

    IrcPanelWindow(JPanel dockHost, JPanel content, Runnable beforeMove, Runnable hidePreviews,
                   Runnable dockRequested, Supplier<String> loadGeometry, Consumer<String> saveGeometry) {
        this.loadGeometry = loadGeometry;
        this.saveGeometry = saveGeometry;
        saveTimer.setRepeats(false);
        this.dockHost = dockHost;
        this.content = content;
        this.beforeMove = beforeMove;
        this.hidePreviews = hidePreviews;
        this.dockRequested = dockRequested;
        content.addHierarchyListener(hierarchyListener);
        observeWindow(SwingUtilities.getWindowAncestor(content));
    }

    void setDetached(boolean detached, boolean alwaysOnTop) {
        assert SwingUtilities.isEventDispatchThread();
        if (detached) {
            if (frame == null) {
                beforeMove.run();
                Window owner = SwingUtilities.getWindowAncestor(content);
                frame = new JFrame("Global Chat (IRC)");
                frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                frame.setMinimumSize(new Dimension(640, 360));
                frame.setSize(960, 620);
                if (owner != null) {
                    frame.setIconImages(owner.getIconImages());
                }
                frame.setLocationRelativeTo(owner);
                // Reopen where it was last time, provided its title bar is on a screen still there.
                PopOutGeometry saved = PopOutGeometry.parse(loadGeometry.get());
                boolean restore = saved != null && saved.titleBarVisible(screens());
                if (restore) frame.setBounds(saved.bounds);
                normalBounds = frame.getBounds();
                if (restore && saved.maximized) frame.setExtendedState(Frame.MAXIMIZED_BOTH);
                frame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) { dockRequested.run(); }
                });
                frame.addWindowStateListener(e -> geometryChanged());
                frame.addComponentListener(frameGeometryListener);
                frame.add(content, BorderLayout.CENTER);
                dockHost.revalidate();
                dockHost.repaint();
                frame.setAlwaysOnTop(alwaysOnTop);
                frame.setVisible(true);
            } else {
                frame.setAlwaysOnTop(alwaysOnTop);
            }
        } else if (frame != null) {
            beforeMove.run();
            saveTimer.stop();
            saveGeometry();
            dockHost.add(content, BorderLayout.CENTER);
            frame.dispose();
            frame = null;
            dockHost.revalidate();
            dockHost.repaint();
        }
    }

    private void geometryChanged() {
        if (frame == null) return;
        // Maximized or minimized bounds aren't worth keeping: un-maximizing goes back to these.
        if (frame.getExtendedState() == Frame.NORMAL) normalBounds = frame.getBounds();
        saveTimer.restart();
    }

    private void saveGeometry() {
        if (frame == null || normalBounds == null) return;
        boolean maximized = (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
        saveGeometry.accept(new PopOutGeometry(normalBounds, maximized).serialize());
    }

    private static List<Rectangle> screens() {
        List<Rectangle> screens = new ArrayList<>();
        for (GraphicsDevice screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            screens.add(screen.getDefaultConfiguration().getBounds());
        }
        return screens;
    }

    void toFront() {
        assert SwingUtilities.isEventDispatchThread();
        if (frame == null) return;
        if ((frame.getExtendedState() & Frame.ICONIFIED) != 0) {
            frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
        }
        frame.toFront();
        frame.requestFocus();
    }

    private void observeWindow(Window window) {
        if (observedWindow == window) return;
        if (observedWindow != null) {
            observedWindow.removeWindowFocusListener(windowListener);
            observedWindow.removeWindowListener(windowListener);
            observedWindow.removeComponentListener(componentListener);
        }
        observedWindow = window;
        if (window != null) {
            window.addWindowFocusListener(windowListener);
            window.addWindowListener(windowListener);
            window.addComponentListener(componentListener);
        }
    }

    void shutdown() {
        assert SwingUtilities.isEventDispatchThread();
        setDetached(false, false);
        content.removeHierarchyListener(hierarchyListener);
        observeWindow(null);
    }
}
