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

        label.setText("<html><body style=\"width:180px;overflow:hidden;\"></body></html>");

        panel.add(label);

        this.add(panel, BorderLayout.NORTH);
    }

    private static void addMessage(String message)
    {
        panel.removeAll();

        String existingMessages = label.getText().replace("</body></html>", "");

        message = message.replaceAll("(http[^ ]+)", "<a href=\"$1\" alt=\"$1\">link</a>");

        label.setText(existingMessages + "<div style=\"width:180px;word-wrap:break-word;overflow:hidden;\">" + message + "</div></body></html>");

        panel.add(label);
    }

    public static void message(String message)
    {
        addMessage(message);
    }
}
