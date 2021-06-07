package com.irc;

import net.runelite.client.ui.PluginPanel;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import javax.swing.*;
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class IrcPanel extends PluginPanel
{
    private static final JPanel panel = new JPanel();
    private static final JLabel label = new JLabel();

    void init()
    {
        panel.removeAll();

        label.setText("<html><table width=230 style=\"word-wrap:break-word;\"></table></html>");

        panel.add(label);

        this.add(panel, BorderLayout.NORTH);
    }

    private static void addMessage(String message)
    {
        panel.removeAll();

        String existingMessages = label.getText().replace("</table></html>", "");

        label.setText(existingMessages + "<tr><td>" + message + "</td></tr></table></html>");

        log.warn(label.getText());

        panel.add(label);
    }

    public static void message(String message)
    {
        addMessage(message);
    }
}
