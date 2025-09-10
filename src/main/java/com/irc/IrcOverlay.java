package com.irc;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.ColorScheme;

import javax.inject.Inject;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;

@Slf4j
public class IrcOverlay extends Overlay {
    private final Client client;
    private final IrcPanel panel;

    final int tabHeight = 12;
    final int tabSpacing = 2; // space between tabs
    final int padding = 8;

    @Setter
    private boolean enabled;

    @Inject
    public IrcOverlay(Client client, IrcPanel panel, IrcConfig config) {
        this.client = client;
        this.panel = panel;
        this.enabled = config.overlayEnabled();

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    public void subscribeEvents() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (panel == null) return false;

            if (e.getID() == KeyEvent.KEY_PRESSED) {
                if (e.getKeyCode() == KeyEvent.VK_PAGE_UP) {
                    panel.cycleChannelBackwards();
                    e.consume();
                    return true;
                } else if (e.getKeyCode() == KeyEvent.VK_PAGE_DOWN) {
                    panel.cycleChannel();
                    e.consume();
                    return true;
                }

                Component focusOwner = KeyboardFocusManager
                        .getCurrentKeyboardFocusManager()
                        .getFocusOwner();

                // Only process if NOT inside the text input
                if (!focusOwner.equals(panel.inputField)) {
                    if (e.getKeyCode() == KeyEvent.VK_BACK_QUOTE && (e.getModifiersEx() & KeyEvent.SHIFT_DOWN_MASK) != 0) {
                        panel.cycleChannelBackwards();
                        e.consume();
                        return true;
                    } else if (e.getKeyCode() == KeyEvent.VK_BACK_QUOTE) {
                        panel.cycleChannel();
                        e.consume();
                        return true;
                    }
                }

            }

            return false;
        });
    }

    private static final int CHATBOX_GROUP = 162;
    private static final int CHATBOX_MESSAGES_CHILD = 0;
    private static final int CHATAREA = InterfaceID.Chatbox.CHATAREA;

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!enabled || panel == null)
            return null;

        Widget chatboxMessages = client.getWidget(CHATBOX_GROUP, CHATBOX_MESSAGES_CHILD);
        Widget chatarea = client.getWidget(CHATAREA);
        if (chatboxMessages == null || chatboxMessages.isHidden() || chatarea == null || chatarea.isHidden())
            return null;
        net.runelite.api.Point loc = chatboxMessages.getCanvasLocation();
        int x = loc.getX() + padding;
        int y = loc.getY() + padding;
        int scrollbarWidth = 16;
        int width = chatboxMessages.getWidth() - scrollbarWidth - padding * 2; // additional padding
        int height = tabHeight;

        // background
        graphics.setColor(ColorScheme.DARKER_GRAY_COLOR);
        graphics.fillRect(x, y, width, height);

        // tabs
        java.util.List<String> channels = panel.getChannelNames();
        int activeTabIndex = Math.max(0, channels.indexOf(panel.getCurrentChannel()));

        int xOffset = 0;
        int yOffset = 0;
        for (int i = 0; i < channels.size(); i++) {
            boolean isActive = i == activeTabIndex;
            String channel = channels.get(i);
            boolean isUnread = panel.unreadMessages.get(channel);

            FontMetrics fm = graphics.getFontMetrics();
            int tabWidth = fm.stringWidth(channel) + padding * 2 - tabSpacing; // 8px padding each side

            // stop drawing if tab exceeds width
            if (xOffset + tabWidth > width) {
                yOffset += height;
                xOffset = 0;
            }

            // tab background
            graphics.setColor(isActive ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR.darker());
            graphics.fillRect(x + xOffset, y + yOffset, tabWidth, height);

            // channel name
            graphics.setColor(isActive ? Color.WHITE : isUnread ? ColorScheme.BRAND_ORANGE.brighter() : ColorScheme.BRAND_ORANGE.darker());
            graphics.drawString(channel, x + xOffset + 8, y + yOffset + height);

            xOffset += tabWidth + tabSpacing;
        }

        return new Dimension(width, height);
    }
}
