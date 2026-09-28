package com.irc;

import net.runelite.client.config.ConfigDescriptor;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigItemDescriptor;
import net.runelite.client.plugins.PluginDescriptor;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;

/** Compatibility with RuneLite's config UI, which does not listen for ConfigChanged. */
final class IrcConfigUi {
    private IrcConfigUi() {}

    static void syncPopOutCheckbox(ConfigDescriptor descriptor, boolean selected) {
        assert SwingUtilities.isEventDispatchThread();
        for (ConfigItemDescriptor item : descriptor.getItems()) {
            if ("popOut".equals(item.getItem().keyName())) {
                for (Window window : Window.getWindows()) {
                    if (window.isDisplayable()) syncConfigPanels(window, item.getItem(), selected);
                }
                return;
            }
        }
    }

    private static void syncConfigPanels(Container container, ConfigItem item, boolean selected) {
        // ConfigPanel is package-private and exposes no public refresh API. Use only the
        // public Swing component tree, and touch only IRC's exact labelled checkbox row.
        // If RuneLite changes its layout, leave it alone; the saved config is still correct.
        if (container.getClass().getName().equals("net.runelite.client.plugins.config.ConfigPanel")) {
            PluginDescriptor plugin = IrcPlugin.class.getAnnotation(PluginDescriptor.class);
            if (containsLabel(container, plugin.name(), tooltip(plugin.name(), plugin.description()))) {
                syncRow(container, item, selected);
            }
            return;
        }
        for (Component child : container.getComponents()) {
            if (child instanceof Container) syncConfigPanels((Container) child, item, selected);
        }
    }

    private static void syncRow(Container container, ConfigItem item, boolean selected) {
        boolean matches = false;
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel && matches((JLabel) child, item.name(), tooltip(item.name(), item.description()))) {
                matches = true;
                break;
            }
        }
        if (matches) {
            for (Component child : container.getComponents()) {
                if (child instanceof JCheckBox) {
                    // setSelected updates the display without firing the checkbox's save action.
                    ((JCheckBox) child).setSelected(selected);
                }
            }
            return;
        }
        for (Component child : container.getComponents()) {
            if (child instanceof Container) syncRow((Container) child, item, selected);
        }
    }

    private static boolean containsLabel(Container container, String name, String tooltip) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel && matches((JLabel) child, name, tooltip)) return true;
            if (child instanceof Container && containsLabel((Container) child, name, tooltip)) return true;
        }
        return false;
    }

    private static boolean matches(JLabel label, String name, String tooltip) {
        return name.equals(label.getText()) && tooltip.equals(label.getToolTipText());
    }

    private static String tooltip(String name, String description) {
        return "<html>" + name + ":<br>" + description + "</html>";
    }
}
