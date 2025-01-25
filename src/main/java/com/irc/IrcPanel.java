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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.*;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringEscapeUtils;


@Slf4j
public class IrcPanel extends PluginPanel
{
    private static final JPanel panel = new JPanel();
    private static final JLabel container = new JLabel();
    private static final JScrollPane scroller = new JScrollPane(panel, JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
    private static final Integer width = 2100;
    private static final Integer height = 5000;

    void init()
    {
        clearMessages();
        container.setSize(width, height);
        container.setHorizontalTextPosition(SwingConstants.LEFT);
        container.setHorizontalAlignment(SwingConstants.LEFT);
        container.setVerticalTextPosition(SwingConstants.TOP);
        container.setVerticalAlignment(SwingConstants.TOP);
        container.setMaximumSize(new Dimension(width, height));
        container.setPreferredSize(new Dimension(width, height));

        scroller.setPreferredSize(new Dimension(width, height));
        scroller.setMaximumSize(new Dimension(width, height));

        this.add(scroller, BorderLayout.NORTH);
    }

    private static void addMessage(String message)
    {
        Pattern r = Pattern.compile("(http[^ ]+)");
        Matcher m = r.matcher(message);

        final String url;

        if (m.find()) {
            url = m.group(0);

            message = message.replaceAll(url, "<a href=\"" + url + "\" alt=\"" + url + "\">link</a>");
        } else {
            url = "";
        }

        int line_height = 20;

        JLabel label = new JLabel("<html>" + StringEscapeUtils.escapeHtml4(message) + "</html>");
        label.setSize(width, line_height);
        label.setHorizontalAlignment(SwingConstants.LEFT);
        label.setVerticalAlignment(SwingConstants.TOP);
        label.setLocation(0, container.getComponentCount() * line_height);

        if (!url.isEmpty()) {
            label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); // Change cursor to hand
            label.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    try {
                        Desktop.getDesktop().browse(new URI(url));
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            });
        }

        container.add(label);
        container.repaint();
    }

    private static void clearMessages()
    {
        panel.removeAll();

        panel.add(container);
    }

    public static void message(String message)
    {
        addMessage(message);
    }

    public static void clearLogs() { clearMessages(); }

}
