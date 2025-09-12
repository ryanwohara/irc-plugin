package com.irc;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
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
import java.awt.event.KeyListener;
import java.util.List;

@Slf4j
public class IrcOverlay extends Overlay implements KeyListener {
    private final Client client;
    private final IrcPanel panel;

    final int tabHeight = 12;
    final int tabSpacing = 2; // space between tabs
    final int padding = 8;

    @Setter
    private boolean enabled;
    private IrcConfig config;

    @Inject
    public IrcOverlay(Client client, IrcPanel panel, IrcConfig config) {
        this.client = client;
        this.panel = panel;
        this.config = config;
        this.enabled = config.overlayEnabled();

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);

        this.client.getCanvas().addKeyListener(this);
    }

    public void subscribeEvents() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (panel == null) return false;

            if (e.getID() == KeyEvent.KEY_PRESSED) {
                if (e.getKeyCode() == KeyEvent.VK_PAGE_UP && this.config.pageUpDownNavigation()) {
                    panel.cycleChannelBackwards();
                    e.consume();
                    return true;
                } else if (e.getKeyCode() == KeyEvent.VK_PAGE_DOWN && this.config.pageUpDownNavigation()) {
                    panel.cycleChannel();
                    e.consume();
                    return true;
                }

                Component focusOwner = KeyboardFocusManager
                        .getCurrentKeyboardFocusManager()
                        .getFocusOwner();

                // Only process if NOT inside the text input
                if (!focusOwner.equals(panel.inputField) && chatboxFocused() && this.config.backTickNavigation()) {
                    if (e.getKeyCode() == KeyEvent.VK_BACK_QUOTE && (e.getModifiersEx() & KeyEvent.SHIFT_DOWN_MASK) != 0) {
                        e.consume();
                        panel.cycleChannelBackwards();
                        return true;
                    } else if (e.getKeyCode() == KeyEvent.VK_BACK_QUOTE) {
                        e.consume();
                        panel.cycleChannel();
                        return true;
                    }
                }

            }

            return false;
        });
    }

    private boolean isHidden(int component) {
        Widget w = client.getWidget(component);
        return w == null || w.isSelfHidden();
    }

    boolean isDialogOpen() {
        // Most chat dialogs with numerical input are added without the chatbox or its key listener being removed,
        // so chatboxFocused() is true. The chatbox onkey script uses the following logic to ignore key presses,
        // so we will use it too to not remap F-keys.
        return isHidden(InterfaceID.Chatbox.MES_LAYER_HIDE) || isHidden(InterfaceID.Chatbox.CHATDISPLAY)
                // We want to block F-key remapping in the bank pin interface too, so it does not interfere with the
                // Keyboard Bankpin feature of the Bank plugin
                || !isHidden(InterfaceID.BankpinKeypad.UNIVERSE);
    }

    boolean isOptionsDialogOpen() {
        return client.getWidget(InterfaceID.Chatmenu.OPTIONS) != null;
    }

    boolean chatboxFocused() {
        Widget chatboxParent = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
        if (chatboxParent == null || chatboxParent.getOnKeyListener() == null) {
            return false;
        }

        // If the search box on the world map is open and focused, ~keypress_permit blocks the keypress
        Widget worldMapSearch = client.getWidget(InterfaceID.Worldmap.MAPLIST_DISPLAY);
        if (worldMapSearch != null && client.getVarcIntValue(VarClientID.WORLDMAP_SEARCHING) == 1) {
            return false;
        }

        // The report interface blocks input due to 162:54 being hidden, however player/npc dialog and
        // options do this too, and so we can't disable remapping just due to 162:54 being hidden.
        Widget report = client.getWidget(InterfaceID.Reportabuse.UNIVERSE);
        if (report != null) {
            return false;
        }

        return true;
    }

    private static final int CHATBOX_GROUP = 162;
    private static final int CHATBOX_MESSAGES_CHILD = 0;
    private static final int CHATAREA = InterfaceID.Chatbox.CHATAREA;

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!enabled || panel == null || isDialogOpen() || isOptionsDialogOpen())
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
            graphics.drawString(channel, x + xOffset + padding, y + yOffset + height);

            xOffset += tabWidth + tabSpacing;
        }

        return new Dimension(width, height);
    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (panel == null) return;

        if (e.getKeyCode() == KeyEvent.VK_PAGE_UP && this.config.pageUpDownNavigation()) {
            panel.cycleChannelBackwards();
            e.consume();
            return;
        } else if (e.getKeyCode() == KeyEvent.VK_PAGE_DOWN && this.config.pageUpDownNavigation()) {
            panel.cycleChannel();
            e.consume();
            return;
        }

        Component focusOwner = KeyboardFocusManager
                .getCurrentKeyboardFocusManager()
                .getFocusOwner();

        // Only process if NOT inside the text input
        if (!focusOwner.equals(panel.inputField) && chatboxFocused() && this.config.backTickNavigation()) {
            if (e.getKeyCode() == KeyEvent.VK_BACK_QUOTE && (e.getModifiersEx() & KeyEvent.SHIFT_DOWN_MASK) != 0) {
                panel.cycleChannelBackwards();
                e.consume();
            } else if (e.getKeyCode() == KeyEvent.VK_BACK_QUOTE) {
                log.debug("f");
                panel.cycleChannel();
                e.consume();
            }
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {
        // Unnecessary
    }

    @Override
    public void keyReleased(KeyEvent e) {
        // Unnecessary
    }
}
