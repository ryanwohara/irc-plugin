package com.irc;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/** Moves the live chat controls between hosts. All methods run on the Swing EDT. */
final class IrcPanelWindow {
    private final JPanel dockHost;
    private final JPanel content;
    private final Runnable beforeMove;
    private final Runnable hidePreviews;
    private final Runnable dockRequested;
    private JFrame frame;
    private Rectangle bounds;
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

    IrcPanelWindow(JPanel dockHost, JPanel content, Runnable beforeMove,
                   Runnable hidePreviews, Runnable dockRequested) {
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
                frame.setMinimumSize(new Dimension(280, 250));
                frame.setSize(420, 560);
                if (owner != null) {
                    frame.setIconImages(owner.getIconImages());
                }
                frame.setLocationRelativeTo(owner);
                if (bounds != null) {
                    // Reuse geometry within this plugin session, provided its title bar is visible.
                    for (java.awt.GraphicsDevice screen : java.awt.GraphicsEnvironment
                            .getLocalGraphicsEnvironment().getScreenDevices()) {
                        if (screen.getDefaultConfiguration().getBounds()
                                .contains(bounds.x + bounds.width / 2, bounds.y + 10)) {
                            frame.setBounds(bounds);
                            break;
                        }
                    }
                }
                frame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) { dockRequested.run(); }
                });
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
            bounds = frame.getBounds();
            dockHost.add(content, BorderLayout.CENTER);
            frame.dispose();
            frame = null;
            dockHost.revalidate();
            dockHost.repaint();
        }
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
