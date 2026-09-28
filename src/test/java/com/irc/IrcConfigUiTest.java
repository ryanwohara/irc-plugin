package com.irc;

import com.google.gson.Gson;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigDescriptor;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.PluginDescriptor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class IrcConfigUiTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void dockAndCloseUpdateRealRuneLiteCheckboxAndReopenWithOneClick() throws Exception {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        EventBus bus = new EventBus();
        ConfigManager manager = configManager(bus, temporary.newFile("config.properties"));
        IrcConfig config = manager.getConfig(IrcConfig.class);
        manager.setDefaultConfiguration(config, false);
        manager.setConfiguration("irc", "popOut", true);
        SwingUtilities.invokeAndWait(() -> {
            IrcPanel panel = new IrcPanel();
            JFrame settings = new JFrame();
            JFrame unrelated = new JFrame();
            try {
                PluginDescriptor plugin = IrcPlugin.class.getAnnotation(PluginDescriptor.class);
                settings.add(configPanel(manager, config, plugin.name(), plugin.description()));
                settings.setSize(300, 700);
                settings.setVisible(true);
                // Same setting label/tooltip in a different plugin must remain untouched.
                unrelated.add(configPanel(manager, config, "Other plugin", "Other settings"));
                unrelated.setSize(300, 500);
                unrelated.setVisible(true);
                JCheckBox checkbox = checkbox(settings, "Pop out window");
                JCheckBox other = checkbox(unrelated, "Pop out window");
                JCheckBox enabled = checkbox(settings, "Enabled");
                assertNotNull(checkbox);
                assertTrue(checkbox.isSelected());

                // Reproduce RuneLite's stale UI before exercising the workaround.
                manager.setConfiguration("irc", "popOut", false);
                assertTrue(checkbox.isSelected());
                AtomicInteger saves = new AtomicInteger();
                bus.register(ConfigChanged.class, event -> {
                    if ("irc".equals(event.getGroup()) && "popOut".equals(event.getKey())) {
                        saves.incrementAndGet();
                        panel.setDetached(config.popOut(), false);
                    }
                }, 0);
                IrcConfigUi.syncPopOutCheckbox(manager.getConfigDescriptor(config), false);
                assertFalse(checkbox.isSelected());
                assertEquals(0, saves.get()); // Refresh must not fire the setting's save action.
                assertTrue(other.isSelected());

                setField(panel, "config", config);
                setField(panel, "configManager", manager);
                panel.init((channel, message) -> {}, (channel, password) -> {}, channel -> {},
                        reconnect -> {}, query -> {}, () -> {});
                panel.initializeGui();
                panel.inputField.setText("keep this draft");
                for (boolean closeWithX : new boolean[]{false, true}) {
                    checkbox.doClick();
                    assertTrue(config.popOut());
                    JFrame popOut = (JFrame) SwingUtilities.getWindowAncestor(panel.getChatContent());
                    assertNotNull(popOut);
                    assertTrue(popOut.isVisible());
                    int beforeClose = saves.get();
                    if (closeWithX) {
                        popOut.dispatchEvent(new WindowEvent(popOut, WindowEvent.WINDOW_CLOSING));
                    } else {
                        findDock(panel.getChatContent()).doClick();
                    }
                    assertFalse(config.popOut());
                    assertFalse(checkbox.isSelected());
                    assertFalse(popOut.isDisplayable());
                    assertEquals(beforeClose + 1, saves.get());
                    assertTrue(config.sidePanel());
                    assertTrue(enabled.isSelected());
                    assertTrue(other.isSelected());
                    assertEquals("keep this draft", panel.inputField.getText());
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            } finally {
                panel.shutdown();
                settings.dispose();
                unrelated.dispose();
            }
        });
    }

    /** Real config storage in a temporary file; never loads/saves the user's RuneLite profile. */
    private static ConfigManager configManager(EventBus bus, File file) throws Exception {
        Constructor<?> constructor = ConfigManager.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        ConfigManager manager;
        try {
            manager = (ConfigManager) constructor.newInstance(null, executor, bus, null, new Gson(), null, null, null);
        } finally {
            executor.shutdownNow(); // Disable periodic config synchronization in this offline test.
        }
        Constructor<?> dataConstructor = Class.forName("net.runelite.client.config.ConfigData")
                .getDeclaredConstructor(File.class);
        dataConstructor.setAccessible(true);
        setField(manager, "configProfile", dataConstructor.newInstance(file));
        return manager;
    }

    /** Instantiate RuneLite's actual package-private settings UI, not a simulated checkbox. */
    private static Container configPanel(ConfigManager manager, Config config, String name, String description)
            throws ReflectiveOperationException {
        Class<?> descriptorClass = Class.forName("net.runelite.client.plugins.config.PluginConfigurationDescriptor");
        Constructor<?> descriptorConstructor = descriptorClass.getDeclaredConstructor(String.class,
                String.class, String[].class, Config.class, ConfigDescriptor.class);
        descriptorConstructor.setAccessible(true);
        Object descriptor = descriptorConstructor.newInstance(name, description, new String[0], config,
                manager.getConfigDescriptor(config));
        Class<?> panelClass = Class.forName("net.runelite.client.plugins.config.ConfigPanel");
        Constructor<?> constructor = panelClass.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object[] args = new Object[constructor.getParameterCount()];
        args[1] = manager;
        Container panel = (Container) constructor.newInstance(args);
        Method init = panelClass.getDeclaredMethod("init", descriptorClass);
        init.setAccessible(true);
        init.invoke(panel, descriptor);
        return panel;
    }

    private static void setField(Object object, String name, Object value) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static JCheckBox checkbox(Container root, String label) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel && label.equals(((JLabel) child).getText())) {
                for (Component sibling : root.getComponents()) {
                    if (sibling instanceof JCheckBox) return (JCheckBox) sibling;
                }
            }
            if (child instanceof Container) {
                JCheckBox found = checkbox((Container) child, label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JButton findDock(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton && "Dock ↗".equals(((JButton) child).getText())) return (JButton) child;
            if (child instanceof Container) {
                JButton found = findDock((Container) child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
