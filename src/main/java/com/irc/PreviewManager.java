package com.irc;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
public class PreviewManager {
    private final OkHttpClient okHttpClient;
    private static final int MAX_PREVIEW_WIDTH = 500;
    private static final int MAX_PREVIEW_HEIGHT = 500;
    private Popup currentImagePreview;
    private CompletableFuture<?> imagePreviewFuture;
    private final Timer debounceTimer;
    private Point pendingPoint;
    private String pendingUrl;
    private final IrcPanel.ChannelPane channelPane;
    private static final Pattern IMAGE_URL_PATTERN = Pattern.compile("\\.(png|jpe?g|bmp)(\\?.*)?$", Pattern.CASE_INSENSITIVE);
    private final Cache<String, byte[]> imageCache;

    public PreviewManager(IrcPanel.ChannelPane channelPane, OkHttpClient okHttpClient) {
        this.channelPane = channelPane;
        this.debounceTimer = new Timer(100, e -> queueShowPreview());
        this.debounceTimer.setRepeats(false);
        this.imageCache = CacheBuilder.newBuilder()
                .maximumSize(50)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build();
        this.okHttpClient = okHttpClient;
    }

    public void requestShow(Point mousePoint, String url) {
        cancelPreview();
        this.pendingPoint = mousePoint;
        this.pendingUrl = url;
        debounceTimer.restart();
    }

    private void queueShowPreview() {
        if (pendingPoint != null && pendingUrl != null && channelPane.isShowing()) {
            showImagePreview(this.pendingPoint, this.pendingUrl);
        }
    }

    public void cancelPreview() {
        debounceTimer.stop();
        pendingPoint = null;
        pendingUrl = null;
        hideImagePreview();
    }

    public boolean isImageUrl(String url) {
        return IMAGE_URL_PATTERN.matcher(url).find();
    }

    public void showImagePreview(Point mousePoint, String imageUrl) {
        cancelPreviewManager();
        this.pendingPoint = mousePoint;

        if (imagePreviewFuture != null && !imagePreviewFuture.isDone()) {
            imagePreviewFuture.cancel(true);
        }

        imagePreviewFuture = CompletableFuture.runAsync(() -> {
            try {
                handleStaticImagePreview(mousePoint, imageUrl);
            } catch (Exception e) {
                log.warn("Failed to create image preview for {}", imageUrl, e);
            }
        });
    }

    private void handleStaticImagePreview(Point mousePoint, String imageUrl) throws IOException {
        if (okHttpClient == null) {
            log.warn("No OkHttp connection available");
            return;
        }

        byte[] imageBytes = imageCache.getIfPresent(imageUrl);

        if (imageBytes == null || imageBytes.length == 0) {
            log.debug("Cache miss for {}, fetching from network.", imageUrl);

            String encodedUrl = URLEncoder.encode(imageUrl, StandardCharsets.UTF_8);

            // CloudFlare worker to protect users from IP grabbers
            String url = "https://image-proxy.cold-pine-9570.workers.dev/?url=" + encodedUrl;
            // Discord doesn't support CloudFlare workers :(
            if (imageUrl.startsWith("https://cdn.discordapp.com/")) {
                url = imageUrl;
            }

            Request request = new Request.Builder().url(url).build();
            okHttpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    e.printStackTrace();
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        log.warn("Failed to fetch image preview for {}", imageUrl);
                        return;
                    }

                    if (response.code() != 200) {
                        log.warn("Failed to fetch image: {} returned status {}", imageUrl, response.code());
                        return;
                    }

                    String contentType = response.header("Content-Type");
                    if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
                        log.warn("Invalid content-type for {}: {}", imageUrl, contentType);
                        return;
                    }

                    if (response.body() == null) {
                        log.warn("Empty body for: {}", imageUrl);
                        return;
                    }

                    try (InputStream in = response.body().byteStream();
                         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

                        byte[] buffer = new byte[4096];
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            baos.write(buffer, 0, n);
                        }
                        byte[] imageBytes = baos.toByteArray();
                        imageCache.put(imageUrl, imageBytes);
                        response.close();

                        showPreview(imageUrl, imageBytes);
                    }
                }
            });
        } else {
            log.debug("Cache hit for {}", imageUrl);
            showPreview(imageUrl, imageBytes);
        }
    }

    private void showPreview(String imageUrl, byte[] imageBytes) throws IOException {
        if (imageBytes != null) {
            BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (originalImage == null) {
                log.warn("Could not decode image from URL: {}", imageUrl);
                imageCache.invalidate(imageUrl);
                return;
            }

            ImageIcon imageIcon = new ImageIcon(scaleImage(originalImage));

            JLabel preview = new JLabel(imageIcon);
            preview.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseExited(MouseEvent e) {
                    cancelPreviewManager();
                }
            });
            SwingUtilities.invokeLater(() -> displayPopup(this.pendingPoint, preview));
        }
    }

    private BufferedImage scaleImage(BufferedImage originalImage) {
        double scale = Math.min(1.0, Math.min(
                (double) MAX_PREVIEW_WIDTH / originalImage.getWidth(),
                (double) MAX_PREVIEW_HEIGHT / originalImage.getHeight()
        ));

        int scaledWidth = (int) (originalImage.getWidth() * scale);
        int scaledHeight = (int) (originalImage.getHeight() * scale);

        BufferedImage scaledImage = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = scaledImage.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(originalImage, 0, 0, scaledWidth, scaledHeight, null);
        g2d.dispose();
        return scaledImage;
    }

    private void displayPopup(Point location, JComponent content) {
        try {
            cancelPreviewManager();
            if (!channelPane.isShowing()) {
                return;
            }

            Window topLevelWindow = SwingUtilities.getWindowAncestor(channelPane);
            if (topLevelWindow == null) return;

            if (location == null) {
                log.warn("Mouse location is null");
                return;
            }
            SwingUtilities.convertPointToScreen(location, channelPane);

            Dimension contentSize = content.getPreferredSize();
            Rectangle screenBounds = topLevelWindow.getGraphicsConfiguration().getBounds();

            // Adjust Y coordinate
            if (location.y + contentSize.height > screenBounds.y + screenBounds.height) {
                Point componentOnScreen = channelPane.getLocationOnScreen();
                int yAbove = componentOnScreen.y + location.y - contentSize.height;

                if (yAbove >= screenBounds.y) {
                    location.y = yAbove;
                } else {
                    location.y = screenBounds.y + screenBounds.height - contentSize.height;
                }
            }

            if (location.x + contentSize.width > screenBounds.x + screenBounds.width) {
                location.x = screenBounds.x + screenBounds.width - contentSize.width;
            }

            if (location.y < screenBounds.y) {
                location.y = screenBounds.y;
            }
            if (location.x < screenBounds.x) {
                location.x = screenBounds.x;
            }

            content.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
            content.setOpaque(true);

            currentImagePreview = PopupFactory.getSharedInstance().getPopup(channelPane, content, location.x, location.y);
            currentImagePreview.show();
        } catch (Exception e) {
            log.warn("Could not display popup", e);
        }
    }

    void hideImagePreview() {
        if (imagePreviewFuture != null && !imagePreviewFuture.isDone()) {
            imagePreviewFuture.cancel(true);
            imagePreviewFuture = null;
        }
        if (currentImagePreview != null) {
            currentImagePreview.hide();
            currentImagePreview = null;
        }
    }

    void cancelPreviewManager() {
        cancelPreview();
    }
}