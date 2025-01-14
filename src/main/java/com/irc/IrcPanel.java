/*
 * Copyright (c) 2020, Ryan W. O'Hara <ryan@ryanwohara.com>, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
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
        clearMessages();

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

    private static void clearMessages()
    {
        panel.removeAll();

        label.setText("<html><body style=\"width:180px;overflow:hidden;\"></body></html>");

        panel.add(label);
    }

    public static void message(String message)
    {
        addMessage(message);
    }

    public static void clearLogs() { clearMessages(); }
}
