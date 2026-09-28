package com.irc;

import org.junit.Test;
import org.junit.Before;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Rectangle;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Desktop tests with real Swing windows; no IRC connection or RuneLite login is needed. */
public class IrcPanelWindowTest {
    @Before
    public void requiresDesktop() {
        org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
    }

    @Test
    public void switchingHostsPreservesChatAndResizeFillsWindow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel dock = new JPanel(new BorderLayout());
            JPanel content = new JPanel(new BorderLayout());
            JTextField draft = new JTextField("unfinished message");
            JTabbedPane tabs = new JTabbedPane();
            JTextArea history = new JTextArea("older message\nlatest message");
            tabs.addTab("System", new JPanel());
            tabs.addTab("#test", new JScrollPane(history));
            tabs.setSelectedIndex(1);
            content.add(tabs, BorderLayout.CENTER);
            content.add(draft, BorderLayout.SOUTH);
            dock.add(content, BorderLayout.CENTER);
            IrcPanelWindow host = new IrcPanelWindow(dock, content, () -> {}, () -> {}, () -> {});
            try {
                for (int i = 0; i < 3; i++) {
                    host.setDetached(true, false);
                    JFrame frame = (JFrame) SwingUtilities.getWindowAncestor(content);
                    assertTrue(frame.isVisible());
                    assertEquals(0, dock.getComponentCount());
                    frame.setSize(620, 680);
                    frame.validate();
                    assertEquals(frame.getContentPane().getWidth(), content.getWidth());
                    assertEquals(frame.getContentPane().getHeight(), content.getHeight());
                    assertEquals(1, tabs.getSelectedIndex());
                    assertEquals("unfinished message", draft.getText());
                    assertEquals("older message\nlatest message", history.getText());
                    host.setDetached(true, true);
                    assertSame(frame, SwingUtilities.getWindowAncestor(content));
                    assertTrue(frame.isAlwaysOnTop());
                    host.setDetached(false, false);
                    assertSame(dock, content.getParent());
                    assertFalse(frame.isDisplayable());
                }
            } finally {
                host.shutdown();
            }
        });
    }

    @Test
    public void closeRequestsDockAndShutdownRemovesWindowListeners() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JFrame main = new JFrame();
            JPanel dock = new JPanel(new BorderLayout());
            JPanel content = new JPanel();
            dock.add(content, BorderLayout.CENTER);
            main.add(dock);
            main.setSize(300, 400);
            main.setVisible(true);
            int focusListeners = main.getWindowFocusListeners().length;
            int componentListeners = main.getComponentListeners().length;
            AtomicInteger dockRequests = new AtomicInteger();
            IrcPanelWindow host = new IrcPanelWindow(dock, content, () -> {}, () -> {},
                    dockRequests::incrementAndGet);
            try {
                assertEquals(focusListeners + 1, main.getWindowFocusListeners().length);
                host.setDetached(true, false);
                JFrame popOut = (JFrame) SwingUtilities.getWindowAncestor(content);
                assertEquals(focusListeners, main.getWindowFocusListeners().length);
                popOut.dispatchEvent(new WindowEvent(popOut, WindowEvent.WINDOW_CLOSING));
                assertEquals(1, dockRequests.get());
                // The config event performs the actual docking, not JFrame's close operation.
                host.setDetached(false, false);
                assertSame(dock, content.getParent());
                host.setDetached(true, false);
                JFrame reopened = (JFrame) SwingUtilities.getWindowAncestor(content);
                Rectangle bounds = reopened.getBounds();
                host.setDetached(false, false);
                host.setDetached(true, false);
                JFrame restored = (JFrame) SwingUtilities.getWindowAncestor(content);
                assertEquals(bounds, restored.getBounds());
                host.shutdown();
                assertFalse(restored.isDisplayable());
                assertEquals(focusListeners, main.getWindowFocusListeners().length);
                assertEquals(componentListeners, main.getComponentListeners().length);
                host.shutdown();
            } finally {
                host.shutdown();
                main.dispose();
            }
        });
    }
}
