package com.irc;

import javax.swing.*;
import java.awt.*;

public class PreviewManager {
    private final Timer debounceTimer;
    private Point pendingPoint;
    private String pendingUrl;
    private final IrcPanel.ChannelPane channelPane;

    public PreviewManager(IrcPanel.ChannelPane channelPane) {
        this.channelPane = channelPane;
        this.debounceTimer = new Timer(100, e -> queueShowPreview());
        this.debounceTimer.setRepeats(false);
    }

    public void requestShow(Point mousePoint, String url) {
        cancelPreview();
        this.pendingPoint = mousePoint;
        this.pendingUrl = url;
        debounceTimer.restart();
    }

    private void queueShowPreview() {
        if (pendingPoint != null && pendingUrl != null && channelPane.isShowing()) {
            channelPane.showImagePreview(pendingPoint, pendingUrl);
        }
    }

    public void cancelPreview() {
        debounceTimer.stop();
        pendingPoint = null;
        pendingUrl = null;
        channelPane.hideImagePreview();
    }
}