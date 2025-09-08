package com.irc;

import lombok.Setter;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.ColorScheme;

import javax.inject.Inject;
import java.awt.*;
import java.awt.event.KeyEvent;

public class IrcOverlay extends Overlay
{
    private final Client client;
    private final IrcPanel panel;

    @Setter
    private boolean enabled = false;

    final int tabHeight = 20;
    final int tabSpacing = 2; // space between tabs

    @Inject
    public IrcOverlay(Client client, IrcPanel panel, IrcConfig config)
    {
        this.client = client;
        this.panel = panel;
        this.enabled = config.overlayEnabled()  ;
        setPosition(OverlayPosition.ABOVE_CHATBOX_RIGHT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        subscribeKeyEvents();
    }

    private void subscribeKeyEvents() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (panel == null || !enabled) return false;

            if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_TAB) {
                cycleChannel();
                e.consume();
                return true;
            }
            return false;
        });
    }

    private void cycleChannel() {
        java.util.List<String> channels = panel.getChannelNames();
        // skip first two special tabs (system/status)
        java.util.List<String> visibleChannels = channels.subList(Math.min(2, channels.size()), channels.size());
        if (visibleChannels.isEmpty()) return;

        String current = panel.getCurrentChannel();
        int index = visibleChannels.indexOf(current);
        index = (index + 1) % visibleChannels.size();
        panel.setFocusedChannel(visibleChannels.get(index));
    }

    private static final int CHATBOX_GROUP = 162;
    private static final int CHATBOX_MESSAGES_CHILD = 0;

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!enabled || panel == null)
            return null;

        Widget chatboxMessages = client.getWidget(CHATBOX_GROUP, CHATBOX_MESSAGES_CHILD);
        if (chatboxMessages == null || chatboxMessages.isHidden())
            return null;

        int width = chatboxMessages.getWidth();

        // draw background bar
        graphics.setColor(ColorScheme.DARKER_GRAY_COLOR);
        graphics.fillRect(0, 0, width, tabHeight);

        java.util.List<String> channels = panel.getChannelNames();
        int activeTabIndex = Math.max(0, channels.indexOf(panel.getCurrentChannel()));

        int x = 0; // start drawing from left
        for (int i = 2; i < channels.size(); i++) { // skip first two tabs
            boolean isActive = i == activeTabIndex;

            String channel = channels.get(i);
            FontMetrics fm = graphics.getFontMetrics();
            int tabWidth = fm.stringWidth(channel) + 16; // padding 8px each side

            // draw tab background
            graphics.setColor(isActive ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR.darker());
            graphics.fillRect(x, 0, tabWidth, tabHeight);
            // draw channel name
            graphics.setColor(isActive ? Color.WHITE : ColorScheme.BRAND_ORANGE.darker());
            graphics.drawString(channel, x + 8, tabHeight - 6);

            x += tabWidth + tabSpacing;
        }

        return new Dimension(width, tabHeight);
    }
}
