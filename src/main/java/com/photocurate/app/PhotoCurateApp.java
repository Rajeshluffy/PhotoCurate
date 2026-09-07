package com.photocurate.app;


import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.geom.AffineTransform;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class PhotoCurateApp extends JFrame {
    private JTextField sourceFolderField;
    private JTextField targetFolderField;
    private JButton scanButton;
    private JButton startReviewButton;
    private JLabel statusLabel;
    private List<File> imageFiles;
    private static final String[] IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".gif", ".ARW", ".arw"};

    public PhotoCurateApp() {
        setTitle("Image Selector");
        setSize(600, 250);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        imageFiles = new ArrayList<>();

        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridx = 0; gbc.gridy = 0;
        mainPanel.add(new JLabel("Source Folder:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        sourceFolderField = new JTextField();
        mainPanel.add(sourceFolderField, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        JButton browseSrcButton = new JButton("Browse...");
        browseSrcButton.addActionListener(e -> browseFolder(sourceFolderField));
        mainPanel.add(browseSrcButton, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        mainPanel.add(new JLabel("Target Folder:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        targetFolderField = new JTextField();
        mainPanel.add(targetFolderField, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        JButton browseTgtButton = new JButton("Browse...");
        browseTgtButton.addActionListener(e -> browseFolder(targetFolderField));
        mainPanel.add(browseTgtButton, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 3;
        scanButton = new JButton("Scan for Images");
        scanButton.addActionListener(e -> scanForImages());
        mainPanel.add(scanButton, gbc);

        gbc.gridy = 3;
        startReviewButton = new JButton("Start Review");
        startReviewButton.setEnabled(false);
        startReviewButton.addActionListener(e -> startReview());
        mainPanel.add(startReviewButton, gbc);

        gbc.gridy = 4;
        statusLabel = new JLabel(" ");
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        mainPanel.add(statusLabel, gbc);

        add(mainPanel);
    }

    private void browseFolder(JTextField field) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            field.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void scanForImages() {
        String sourcePath = sourceFolderField.getText().trim();
        if (sourcePath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select a source folder.",
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        File sourceDir = new File(sourcePath);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            JOptionPane.showMessageDialog(this, "Invalid source folder.",
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        imageFiles.clear();
        scanDirectory(sourceDir);

        statusLabel.setText("Found " + imageFiles.size() + " image(s)");
        startReviewButton.setEnabled(imageFiles.size() > 0);
    }

    private void scanDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file);
            } else if (isImageFile(file)) {
                imageFiles.add(file);
            }
        }
    }

    private boolean isImageFile(File file) {
        String name = file.getName().toLowerCase();
        for (String ext : IMAGE_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    private void startReview() {
        String targetPath = targetFolderField.getText().trim();
        if (targetPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select a target folder.",
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        File targetDir = new File(targetPath);
        if (!targetDir.exists()) {
            int result = JOptionPane.showConfirmDialog(this,
                "Target folder doesn't exist. Create it?",
                "Confirm", JOptionPane.YES_NO_OPTION);
            if (result == JOptionPane.YES_OPTION) {
                if (!targetDir.mkdirs()) {
                    JOptionPane.showMessageDialog(this, "Failed to create target folder.",
                        "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            } else {
                return;
            }
        }

        new ImageReviewWindow(imageFiles, targetDir, this);
    }

    /**
     * Displays a BufferedImage scaled to fit the panel (or at an explicit zoom level),
     * without visible quality loss compared to native viewers.
     *
     * Root cause of the "blurry/aliased" look this replaces: Graphics2D's bicubic
     * interpolation resamples in a single pass with a fixed small kernel, so when the
     * reduction ratio is large (e.g. a 6000px photo shrunk to fit an 1200px panel) most
     * source pixels are skipped rather than averaged in, producing aliasing/softness.
     * The fix is to never downscale by more than 2x in a single step (progressiveScale
     * below) - each intermediate bicubic pass then has enough source detail to filter
     * correctly, matching how viewers like Windows Photos render thumbnails.
     *
     * At 1:1 (no scaling) the original raster is blitted directly with no resampling
     * at all, so exact pixels are preserved.
     */
    static class ImagePanel extends JPanel {
        private BufferedImage originalImage;
        private BufferedImage scaledCache;
        private int scaledCacheW = -1;
        private int scaledCacheH = -1;

        // 0 means "fit to window"; otherwise an explicit user-chosen zoom factor.
        private double zoom = 0;
        private static final double MIN_ZOOM = 0.05;
        private static final double MAX_ZOOM = 8.0;

        // Extra pan offset (used once the image no longer fits fully in the panel).
        private int panX;
        private int panY;
        private Point dragStart;

        private boolean isSelected;

        // Notified whenever the zoom level changes, so the containing window can
        // refresh its status label with the current zoom percentage.
        private Runnable onZoomChanged;

        // Draw geometry from the most recent paint, reused for the selection overlay.
        private int imgX, imgY, renderW, renderH;

        public ImagePanel() {
            setBackground(Color.BLACK);
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            addMouseWheelListener(this::onMouseWheel);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    dragStart = e.getPoint();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    requestFocusInWindow();
                }
                @Override
                public void mouseReleased(MouseEvent e) {
                    dragStart = null;
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                }
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        resetZoom();
                    }
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragStart == null) return;
                    panX += e.getX() - dragStart.x;
                    panY += e.getY() - dragStart.y;
                    dragStart = e.getPoint();
                    repaint();
                }
            });
            addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    scaledCache = null;
                    repaint();
                }
            });
        }

        /** Registers a callback fired after the zoom level changes (wheel, reset, +/-). */
        public void setOnZoomChanged(Runnable callback) {
            this.onZoomChanged = callback;
        }

        /** Replaces the displayed image and resets zoom/pan back to "fit to window". */
        public void setImage(BufferedImage img) {
            this.originalImage = img;
            this.zoom = 0;
            this.panX = 0;
            this.panY = 0;
            this.scaledCache = null;
            repaint();
        }

        public void setSelected(boolean selected) {
            this.isSelected = selected;
            repaint();
        }

        public void resetZoom() {
            zoom = 0;
            panX = 0;
            panY = 0;
            repaint();
            fireZoomChanged();
        }

        public void zoomIn() {
            setZoom(effectiveScale() * 1.25);
        }

        public void zoomOut() {
            setZoom(effectiveScale() / 1.25);
        }

        private void setZoom(double newZoom) {
            zoom = clamp(newZoom, MIN_ZOOM, MAX_ZOOM);
            repaint();
            fireZoomChanged();
        }

        /** Current zoom as a percentage of the image's native pixel size (100 = 1:1). */
        public int getZoomPercent() {
            return (int) Math.round(effectiveScale() * 100);
        }

        private void fireZoomChanged() {
            if (onZoomChanged != null) onZoomChanged.run();
        }

        private void onMouseWheel(MouseWheelEvent e) {
            if (originalImage == null) return;
            double current = effectiveScale();
            double factor = e.getWheelRotation() < 0 ? 1.15 : 1 / 1.15;
            setZoom(current * factor);
        }

        private static double clamp(double v, double lo, double hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        /** Scale that fits the whole image inside the panel without upscaling past 1:1. */
        private double fitScale() {
            if (originalImage == null) return 1.0;
            int pw = Math.max(1, getWidth());
            int ph = Math.max(1, getHeight());
            double s = Math.min((double) pw / originalImage.getWidth(), (double) ph / originalImage.getHeight());
            return Math.min(s, 1.0);
        }

        private double effectiveScale() {
            return zoom > 0 ? zoom : fitScale();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (originalImage == null) return;

            Graphics2D g2d = (Graphics2D) g;
            double scale = effectiveScale();

            renderW = (int) Math.round(originalImage.getWidth() * scale);
            renderH = (int) Math.round(originalImage.getHeight() * scale);

            int panelW = getWidth();
            int panelH = getHeight();
            int baseX = (panelW - renderW) / 2;
            int baseY = (panelH - renderH) / 2;

            imgX = baseX + panX;
            imgY = baseY + panY;

            if (renderW == originalImage.getWidth() && renderH == originalImage.getHeight()) {
                // Exact 1:1 - blit the original pixels with no resampling whatsoever.
                g2d.drawImage(originalImage, imgX, imgY, null);
            } else if (scale < 1.0) {
                g2d.drawImage(getScaledDown(renderW, renderH), imgX, imgY, null);
            } else {
                // Upscaling: draw live with high-quality hints (no benefit to caching
                // since it's already larger than the source, one pass is sufficient).
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.drawImage(originalImage, imgX, imgY, renderW, renderH, null);
            }

            if (isSelected) {
                drawSelectionOverlay(g2d);
            }
        }

        private void drawSelectionOverlay(Graphics2D g2d) {
            g2d.setColor(new Color(0, 255, 0));
            g2d.setStroke(new BasicStroke(8));
            g2d.drawRect(imgX, imgY, renderW, renderH);

            int checkSize = 80;
            int checkX = imgX + renderW - checkSize - 20;
            int checkY = imgY + 20;

            g2d.setColor(new Color(0, 200, 0));
            g2d.fillOval(checkX, checkY, checkSize, checkSize);

            g2d.setColor(Color.WHITE);
            g2d.setStroke(new BasicStroke(6));
            int[] checkXPoints = {checkX + 20, checkX + 35, checkX + 60};
            int[] checkYPoints = {checkY + 40, checkY + 55, checkY + 25};
            for (int i = 0; i < checkXPoints.length - 1; i++) {
                g2d.drawLine(checkXPoints[i], checkYPoints[i],
                             checkXPoints[i + 1], checkYPoints[i + 1]);
            }
        }

        /** Returns a bicubic-downscaled copy of originalImage at (w,h), cached until it changes. */
        private BufferedImage getScaledDown(int w, int h) {
            if (scaledCache != null && scaledCacheW == w && scaledCacheH == h) {
                return scaledCache;
            }
            scaledCache = progressiveScale(originalImage, w, h);
            scaledCacheW = w;
            scaledCacheH = h;
            return scaledCache;
        }

        /**
         * Downscales in successive halving steps (never more than 2x reduction per pass)
         * so each bicubic pass has enough neighboring source pixels to filter correctly.
         * A single-pass bicubic resize at large reduction ratios aliases/loses detail;
         * this is the standard workaround for that Java2D limitation.
         */
        private static BufferedImage progressiveScale(BufferedImage src, int targetW, int targetH) {
            int type = src.getTransparency() == Transparency.OPAQUE
                ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;

            BufferedImage current = src;
            int w = src.getWidth();
            int h = src.getHeight();

            while (w != targetW || h != targetH) {
                int nextW = w > targetW ? Math.max(targetW, w / 2) : targetW;
                int nextH = h > targetH ? Math.max(targetH, h / 2) : targetH;

                BufferedImage step = new BufferedImage(nextW, nextH, type);
                Graphics2D g2 = step.createGraphics();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.drawImage(current, 0, 0, nextW, nextH, null);
                g2.dispose();

                current = step;
                w = nextW;
                h = nextH;
            }
            return current;
        }
    }

    /**
     * Loads a BufferedImage off the EDT so browsing large images doesn't freeze the UI,
     * and applies the file's EXIF orientation so it displays the same way it would in
     * Windows Photos instead of in the camera sensor's raw (often sideways) orientation.
     */
    private static void loadImageAsync(File file, java.util.function.Consumer<BufferedImage> onLoaded,
                                        java.util.function.Consumer<Exception> onError) {
        new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                return readImageWithOrientation(file);
            }
            @Override
            protected void done() {
                try {
                    onLoaded.accept(get());
                } catch (Exception e) {
                    onError.accept(e);
                }
            }
        }.execute();
    }

    /**
     * Reads an image and, for formats that carry EXIF (JPEG), rotates/flips it according
     * to the "Orientation" tag so the pixels come out upright. Plain ImageIO.read() ignores
     * this tag entirely, which is why photos taken in portrait mode were appearing rotated
     * here while looking correct in Windows Photos (which does honor the tag).
     */
    private static BufferedImage readImageWithOrientation(File file) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(file)) {
            if (iis == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return null;

            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                BufferedImage img = reader.read(0);
                if (img == null) return null;

                int orientation = 1;
                try {
                    orientation = extractExifOrientation(reader.getImageMetadata(0));
                } catch (Exception ignored) {
                    // No/unsupported metadata for this format - treat as already upright.
                }
                return applyExifOrientation(img, orientation);
            } finally {
                reader.dispose();
            }
        }
    }

    private static int extractExifOrientation(IIOMetadata metadata) {
        if (metadata == null) return 1;
        for (String formatName : metadata.getMetadataFormatNames()) {
            if (!"javax_imageio_jpeg_image_1.0".equals(formatName)) continue;
            byte[] exifSegment = findExifSegment(metadata.getAsTree(formatName));
            if (exifSegment != null) {
                return parseOrientationTag(exifSegment);
            }
        }
        return 1;
    }

    /** Walks the JPEG metadata tree looking for the raw APP1 (0xE1 / 225) "Exif" marker segment. */
    private static byte[] findExifSegment(Node node) {
        if (node instanceof IIOMetadataNode && "unknown".equals(node.getNodeName())) {
            IIOMetadataNode ioNode = (IIOMetadataNode) node;
            if ("225".equals(ioNode.getAttribute("MarkerTag"))) {
                Object userObject = ioNode.getUserObject();
                if (userObject instanceof byte[]) {
                    return (byte[]) userObject;
                }
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            byte[] found = findExifSegment(children.item(i));
            if (found != null) return found;
        }
        return null;
    }

    /** Parses the TIFF/EXIF IFD0 for tag 0x0112 (Orientation); returns 1 (normal) if absent/invalid. */
    private static int parseOrientationTag(byte[] exif) {
        int tiffStart = 6; // skip the "Exif\0\0" identifier
        if (exif.length < tiffStart + 8) return 1;

        boolean bigEndian;
        if (exif[tiffStart] == 'M' && exif[tiffStart + 1] == 'M') {
            bigEndian = true;
        } else if (exif[tiffStart] == 'I' && exif[tiffStart + 1] == 'I') {
            bigEndian = false;
        } else {
            return 1;
        }

        int ifdOffset = readInt32(exif, tiffStart + 4, bigEndian);
        int ifdPos = tiffStart + ifdOffset;
        if (ifdPos < 0 || ifdPos + 2 > exif.length) return 1;

        int numEntries = readInt16(exif, ifdPos, bigEndian);
        for (int i = 0; i < numEntries; i++) {
            int entryPos = ifdPos + 2 + i * 12;
            if (entryPos + 12 > exif.length) break;
            int tag = readInt16(exif, entryPos, bigEndian);
            if (tag == 0x0112) {
                int value = readInt16(exif, entryPos + 8, bigEndian);
                return (value >= 1 && value <= 8) ? value : 1;
            }
        }
        return 1;
    }

    private static int readInt16(byte[] data, int offset, boolean bigEndian) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        return bigEndian ? (b0 << 8) | b1 : (b1 << 8) | b0;
    }

    private static int readInt32(byte[] data, int offset, boolean bigEndian) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        int b2 = data[offset + 2] & 0xFF;
        int b3 = data[offset + 3] & 0xFF;
        return bigEndian
            ? (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
            : (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
    }

    /** Applies the standard EXIF orientation transform (values 1-8) to produce an upright image. */
    private static BufferedImage applyExifOrientation(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) return image;

        int width = image.getWidth();
        int height = image.getHeight();
        AffineTransform transform = new AffineTransform();

        switch (orientation) {
            case 2: // Flip horizontal
                transform.scale(-1.0, 1.0);
                transform.translate(-width, 0);
                break;
            case 3: // 180 degrees
                transform.translate(width, height);
                transform.rotate(Math.PI);
                break;
            case 4: // Flip vertical
                transform.scale(1.0, -1.0);
                transform.translate(0, -height);
                break;
            case 5: // Transpose
                transform.rotate(-Math.PI / 2);
                transform.scale(-1.0, 1.0);
                break;
            case 6: // 90 degrees CW
                transform.translate(height, 0);
                transform.rotate(Math.PI / 2);
                break;
            case 7: // Transverse
                transform.scale(-1.0, 1.0);
                transform.translate(-height, 0);
                transform.translate(0, width);
                transform.rotate(3 * Math.PI / 2);
                break;
            case 8: // 90 degrees CCW
                transform.translate(0, width);
                transform.rotate(3 * Math.PI / 2);
                break;
            default:
                return image;
        }

        boolean swapsDimensions = orientation >= 5;
        int newWidth = swapsDimensions ? height : width;
        int newHeight = swapsDimensions ? width : height;

        int type = image.getType() == BufferedImage.TYPE_CUSTOM
            ? BufferedImage.TYPE_INT_ARGB : image.getType();
        BufferedImage rotated = new BufferedImage(newWidth, newHeight, type);
        Graphics2D g2d = rotated.createGraphics();
        // NEAREST_NEIGHBOR, not BICUBIC: every EXIF orientation is an exact 90/180/270-degree
        // rotation and/or mirror - a lossless permutation of pixels. Any smoothing filter here
        // only blurs the image for no benefit, since there is no fractional-pixel geometry to
        // interpolate.
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g2d.drawImage(image, transform, null);
        g2d.dispose();
        return rotated;
    }

    /** Rotates an image 90 degrees clockwise as an exact, lossless pixel permutation. */
    private static BufferedImage rotate90Clockwise(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int type = image.getType() == BufferedImage.TYPE_CUSTOM
            ? BufferedImage.TYPE_INT_ARGB : image.getType();

        BufferedImage rotated = new BufferedImage(height, width, type);
        Graphics2D g2d = rotated.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        AffineTransform tx = new AffineTransform();
        tx.translate(height / 2.0, width / 2.0);
        tx.rotate(Math.PI / 2);
        tx.translate(-width / 2.0, -height / 2.0);

        g2d.drawImage(image, tx, null);
        g2d.dispose();
        return rotated;
    }

    /** Applies {@code degrees} (any multiple of 90) of clockwise rotation, freshly loaded images. */
    private static BufferedImage applyRotationDegrees(BufferedImage image, int degrees) {
        int steps = (((degrees % 360) + 360) % 360) / 90;
        BufferedImage result = image;
        for (int i = 0; i < steps; i++) {
            result = rotate90Clockwise(result);
        }
        return result;
    }

    // Inner class for full-screen image review
    class ImageReviewWindow extends JFrame {
        private List<File> images;
        private File targetDir;
        private int currentIndex;
        private ImagePanel imagePanel;
        private JLabel infoLabel;
        private JLabel zoomLabel;
        private List<File> selectedImages;
        private JFrame parentFrame;
        private BufferedImage currentImage;
        private JButton toggleButton;
        private JTextField imageNumberField;
        private int loadToken = 0;

        // Manual rotation (degrees clockwise) the user has applied per file via the Rotate
        // button, kept separately from the pixels so it survives navigating away and back -
        // each reload re-decodes the file from disk and reapplies this instead of the rotation
        // being lost with the discarded in-memory BufferedImage.
        private final Map<File, Integer> manualRotations = new HashMap<>();

        // Temporary staging area for side-by-side comparison of similar/duplicate photos.
        // Purely a UI convenience: it never touches the source files, and membership here is
        // independent of selectedImages (the actual "final album" set copied on Finish).
        private final LinkedHashSet<File> compareTray = new LinkedHashSet<>();
        private JButton addToCompareButton;
        private JButton openCompareButton;

        public ImageReviewWindow(List<File> images, File targetDir, JFrame parentFrame) {
            this.images = images;
            this.targetDir = targetDir;
            this.currentIndex = 0;
            this.selectedImages = new ArrayList<>();
            this.parentFrame = parentFrame;

            setTitle("Review Images");
            setExtendedState(JFrame.MAXIMIZED_BOTH);
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setLayout(new BorderLayout());

            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    parentFrame.setVisible(true);
                    parentFrame.toFront();
                }
            });

            parentFrame.setVisible(false);

            imagePanel = new ImagePanel();
            imagePanel.setOnZoomChanged(this::updateZoomLabel);
            add(imagePanel, BorderLayout.CENTER);

            JPanel controlPanel = new JPanel(new BorderLayout());
            controlPanel.setBackground(Color.DARK_GRAY);

            infoLabel = new JLabel();
            infoLabel.setForeground(Color.WHITE);
            infoLabel.setHorizontalAlignment(SwingConstants.CENTER);
            infoLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            zoomLabel = new JLabel();
            zoomLabel.setForeground(Color.LIGHT_GRAY);
            zoomLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 15));

            JPanel topPanel = new JPanel(new BorderLayout());
            topPanel.setBackground(Color.DARK_GRAY);
            topPanel.add(infoLabel, BorderLayout.CENTER);
            topPanel.add(zoomLabel, BorderLayout.EAST);
            controlPanel.add(topPanel, BorderLayout.NORTH);

            JPanel buttonPanel = new JPanel(new FlowLayout());
            buttonPanel.setBackground(Color.DARK_GRAY);

            JButton prevButton = new JButton("Previous (←)");
            prevButton.setPreferredSize(new Dimension(150, 40));
            prevButton.addActionListener(e -> showPrevious());

            JButton nextButton = new JButton("Next (→)");
            nextButton.setPreferredSize(new Dimension(150, 40));
            nextButton.addActionListener(e -> showNext());

            toggleButton = new JButton("💡 Select (Y)");
            toggleButton.setPreferredSize(new Dimension(150, 40));
            toggleButton.setFont(new Font(toggleButton.getFont().getName(), Font.BOLD, 16));
            toggleButton.addActionListener(e -> toggleSelection());

            JButton rotateButton = new JButton("Rotate (R)");
            rotateButton.setPreferredSize(new Dimension(150, 40));
            rotateButton.addActionListener(e -> rotateImage());

            JButton resetZoomButton = new JButton("Reset Zoom (0)");
            resetZoomButton.setPreferredSize(new Dimension(150, 40));
            resetZoomButton.addActionListener(e -> imagePanel.resetZoom());

            imageNumberField = new JTextField(4);
            imageNumberField.setHorizontalAlignment(SwingConstants.CENTER);
            JButton goToButton = new JButton("Go");
            goToButton.addActionListener(e -> goToImage());

            JPanel goToPanel = new JPanel(new FlowLayout());
            goToPanel.setBackground(Color.DARK_GRAY);
            goToPanel.add(new JLabel("Go to image:"));
            goToPanel.add(imageNumberField);
            goToPanel.add(goToButton);

            JButton reviewSelectedButton = new JButton("Review Selected (V)");
            reviewSelectedButton.setPreferredSize(new Dimension(160, 40));
            reviewSelectedButton.addActionListener(e -> reviewSelected());

            addToCompareButton = new JButton("Add to Compare (C)");
            addToCompareButton.setPreferredSize(new Dimension(170, 40));
            addToCompareButton.addActionListener(e -> toggleAddToCompare());

            openCompareButton = new JButton("Compare Tray (0)");
            openCompareButton.setPreferredSize(new Dimension(160, 40));
            openCompareButton.setEnabled(false);
            openCompareButton.addActionListener(e -> openCompareWindow());

            JButton finishButton = new JButton("Finish (ESC)");
            finishButton.setPreferredSize(new Dimension(150, 40));
            finishButton.addActionListener(e -> finish());

            buttonPanel.add(prevButton);
            buttonPanel.add(toggleButton);
            buttonPanel.add(rotateButton);
            buttonPanel.add(resetZoomButton);
            buttonPanel.add(nextButton);
            buttonPanel.add(goToPanel);
            buttonPanel.add(reviewSelectedButton);
            buttonPanel.add(addToCompareButton);
            buttonPanel.add(openCompareButton);
            buttonPanel.add(finishButton);
            controlPanel.add(buttonPanel, BorderLayout.CENTER);

            add(controlPanel, BorderLayout.SOUTH);

            setupKeyBindings();

            setVisible(true);
            displayCurrentImage();
        }

        private void setupKeyBindings() {
            InputMap inputMap = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            ActionMap actionMap = getRootPane().getActionMap();

            bindKey(inputMap, actionMap, KeyEvent.VK_LEFT, "prevImage", e -> showPrevious());
            bindKey(inputMap, actionMap, KeyEvent.VK_RIGHT, "nextImage", e -> showNext());
            bindKey(inputMap, actionMap, KeyEvent.VK_Y, "toggleSelection", e -> toggleSelection());
            bindKey(inputMap, actionMap, KeyEvent.VK_R, "rotateImage", e -> rotateImage());
            bindKey(inputMap, actionMap, KeyEvent.VK_0, "resetZoom", e -> imagePanel.resetZoom());
            bindKey(inputMap, actionMap, KeyEvent.VK_PLUS, "zoomIn", e -> imagePanel.zoomIn());
            bindKey(inputMap, actionMap, KeyEvent.VK_EQUALS, "zoomInAlt", e -> imagePanel.zoomIn());
            bindKey(inputMap, actionMap, KeyEvent.VK_MINUS, "zoomOut", e -> imagePanel.zoomOut());
            bindKey(inputMap, actionMap, KeyEvent.VK_V, "reviewSelected", e -> reviewSelected());
            bindKey(inputMap, actionMap, KeyEvent.VK_C, "toggleAddToCompare", e -> toggleAddToCompare());
            bindKey(inputMap, actionMap, KeyEvent.VK_G, "openCompare", e -> openCompareWindow());
            bindKey(inputMap, actionMap, KeyEvent.VK_ESCAPE, "finishReview", e -> finish());
        }

        private void updateZoomLabel() {
            zoomLabel.setText("Zoom: " + imagePanel.getZoomPercent() + "%");
        }

        int getRotationDegrees(File file) {
            return manualRotations.getOrDefault(file, 0);
        }

        private void toggleAddToCompare() {
            File file = images.get(currentIndex);
            if (compareTray.contains(file)) {
                compareTray.remove(file);
            } else {
                compareTray.add(file);
            }
            updateCompareButtons();
        }

        private void updateCompareButtons() {
            File currentFile = images.get(currentIndex);
            boolean inTray = compareTray.contains(currentFile);
            addToCompareButton.setText(inTray ? "Remove from Compare (C)" : "Add to Compare (C)");
            addToCompareButton.setBackground(inTray ? new Color(100, 170, 220) : null);

            openCompareButton.setText("Compare Tray (" + compareTray.size() + ")");
            openCompareButton.setEnabled(!compareTray.isEmpty());
        }

        private void openCompareWindow() {
            if (compareTray.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                    "Add photos to the compare tray first (press C, or click 'Add to Compare').",
                    "Compare Tray Empty", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            new ComparisonWindow(this);
        }

        /** Snapshot of the files currently staged for comparison, in the order they were added. */
        List<File> getCompareTrayFiles() {
            return new ArrayList<>(compareTray);
        }

        /** Drops a file from the temporary comparison tray only; the source collection is untouched. */
        void removeFromCompareTray(File file) {
            compareTray.remove(file);
            updateCompareButtons();
        }

        boolean isSelectedForAlbum(File file) {
            return selectedImages.contains(file);
        }

        /** Toggles final-album membership from the comparison window and keeps this window's UI in sync. */
        void toggleSelectedForAlbum(File file) {
            if (selectedImages.contains(file)) {
                selectedImages.remove(file);
            } else {
                selectedImages.add(file);
            }
            if (currentImage != null && file.equals(images.get(currentIndex))) {
                imagePanel.setSelected(selectedImages.contains(file));
                updateToggleButton();
            }
            updateInfoLabel();
        }

        /** Called when the comparison window closes, to refresh anything it may have changed. */
        void refreshAfterCompare() {
            updateCompareButtons();
            updateInfoLabel();
        }

        private void bindKey(InputMap inputMap, ActionMap actionMap, int keyCode, String name, ActionListener listener) {
            inputMap.put(KeyStroke.getKeyStroke(keyCode, 0), name);
            actionMap.put(name, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    listener.actionPerformed(e);
                }
            });
        }

        private void displayCurrentImage() {
            if (currentIndex >= images.size()) {
                finish();
                return;
            }

            if (currentImage != null) {
                showLoadedImage(currentImage);
                return;
            }

            final File imageFile = images.get(currentIndex);
            final int requestToken = ++loadToken;
            infoLabel.setText("Loading " + imageFile.getName() + "...");

            loadImageAsync(imageFile,
                img -> {
                    if (requestToken != loadToken) return; // user navigated away, discard
                    if (img == null) {
                        infoLabel.setText("Error loading image: " + imageFile.getName());
                        return;
                    }
                    int degrees = manualRotations.getOrDefault(imageFile, 0);
                    currentImage = degrees == 0 ? img : applyRotationDegrees(img, degrees);
                    showLoadedImage(currentImage);
                },
                ex -> {
                    if (requestToken != loadToken) return;
                    infoLabel.setText("Error loading image: " + imageFile.getName());
                });
        }

        private void showLoadedImage(BufferedImage img) {
            File imageFile = images.get(currentIndex);
            imagePanel.setImage(img);
            updateZoomLabel();

            imagePanel.setSelected(selectedImages.contains(imageFile));
            updateToggleButton();
            updateCompareButtons();
            updateInfoLabel();
        }

        private void updateInfoLabel() {
            if (currentImage == null) return;
            File imageFile = images.get(currentIndex);
            boolean isSelected = selectedImages.contains(imageFile);
            infoLabel.setText(String.format("Image %d of %d: %s (%dx%d) | Selected: %d%s",
                currentIndex + 1, images.size(),
                imageFile.getName(), currentImage.getWidth(), currentImage.getHeight(),
                selectedImages.size(), isSelected ? " ✓" : ""));
        }

        private void goToImage() {
            String input = imageNumberField.getText().trim();
            if (input.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Please enter an image number.",
                    "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            try {
                int imageNumber = Integer.parseInt(input);
                if (imageNumber > 0 && imageNumber <= images.size()) {
                    currentIndex = imageNumber - 1;
                    currentImage = null;
                    displayCurrentImage();
                } else {
                    JOptionPane.showMessageDialog(this,
                        "Invalid number. Please enter a number between 1 and " + images.size() + ".",
                        "Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this,
                    "Invalid input. Please enter a valid number.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }

        private void rotateImage() {
            if (currentImage == null) return;

            File imageFile = images.get(currentIndex);
            int newDegrees = (manualRotations.getOrDefault(imageFile, 0) + 90) % 360;
            manualRotations.put(imageFile, newDegrees);

            currentImage = rotate90Clockwise(currentImage);
            showLoadedImage(currentImage);
        }

        private void toggleSelection() {
            File currentFile = images.get(currentIndex);
            if (selectedImages.contains(currentFile)) {
                selectedImages.remove(currentFile);
            } else {
                selectedImages.add(currentFile);
            }
            imagePanel.setSelected(selectedImages.contains(currentFile));
            updateToggleButton();
            updateInfoLabel();
        }

        private void updateToggleButton() {
            File currentFile = images.get(currentIndex);
            if (selectedImages.contains(currentFile)) {
                toggleButton.setText("💡 Deselect (Y)");
                toggleButton.setBackground(new Color(100, 200, 100));
            } else {
                toggleButton.setText("💡 Select (Y)");
                toggleButton.setBackground(null);
            }
        }

        private void showPrevious() {
            if (currentIndex > 0) {
                currentIndex--;
                currentImage = null;
                displayCurrentImage();
            }
        }

        private void showNext() {
            if (currentIndex < images.size() - 1) {
                currentIndex++;
                currentImage = null;
                displayCurrentImage();
            }
        }

        private void reviewSelected() {
            if (selectedImages.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No images selected yet.",
                    "Info", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            new SelectedImagesReviewer(selectedImages, this);
        }

        private void finish() {
            if (selectedImages.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No images selected.",
                    "Info", JOptionPane.INFORMATION_MESSAGE);
                parentFrame.setVisible(true);
                parentFrame.toFront();
                dispose();
                return;
            }

            int confirm = JOptionPane.showConfirmDialog(this,
                "Copy " + selectedImages.size() + " selected image(s) to target folder?",
                "Confirm Copy", JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                copySelectedImages();
            }
            parentFrame.setVisible(true);
            parentFrame.toFront();
            dispose();
        }

        private void copySelectedImages() {
            int successCount = 0;
            int failCount = 0;
            StringBuilder errors = new StringBuilder();

            for (File src : selectedImages) {
                try {
                    Path srcPath = src.toPath();
                    Path tgtPath = targetDir.toPath().resolve(src.getName());

                    if (Files.exists(tgtPath) && !Files.isSameFile(srcPath, tgtPath)) {
                        String baseName = src.getName();
                        String extension = "";
                        int dotIndex = baseName.lastIndexOf('.');
                        if (dotIndex > 0) {
                            extension = baseName.substring(dotIndex);
                            baseName = baseName.substring(0, dotIndex);
                        }

                        int counter = 1;
                        do {
                            tgtPath = targetDir.toPath().resolve(baseName + "_" + counter + extension);
                            counter++;
                        } while (Files.exists(tgtPath));
                    }

                    Files.copy(srcPath, tgtPath, StandardCopyOption.REPLACE_EXISTING);
                    successCount++;
                } catch (IOException e) {
                    failCount++;
                    errors.append(src.getName()).append(": ").append(e.getMessage()).append("\n");
                    e.printStackTrace();
                }
            }

            String message = "Successfully copied " + successCount + " of " + selectedImages.size() + " image(s).";
            if (failCount > 0) {
                message += "\n\nFailed to copy " + failCount + " image(s):\n" + errors.toString();
            }

            JOptionPane.showMessageDialog(this, message,
                "Complete", failCount > 0 ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * Temporary side-by-side comparison workspace for telling near-duplicate photos apart.
     * Shows every photo staged in the owner window's compare tray as a full ImagePanel (so each
     * one can be individually zoomed/panned for a close look), with a per-photo "Keep" toggle
     * that marks it for the final album and a "Remove" that only drops it from this comparison.
     * Nothing here ever touches the source files or the original photo list.
     */
    class ComparisonWindow extends JFrame {
        private final ImageReviewWindow owner;
        private final List<File> files;
        private final Map<File, ImagePanel> imagePanels = new LinkedHashMap<>();
        private final Map<File, JButton> keepButtons = new LinkedHashMap<>();
        private final Map<File, JPanel> cells = new LinkedHashMap<>();
        private JPanel gridPanel;
        private JLabel summaryLabel;

        ComparisonWindow(ImageReviewWindow owner) {
            this.owner = owner;
            this.files = owner.getCompareTrayFiles();

            setTitle("Compare Photos");
            setExtendedState(JFrame.MAXIMIZED_BOTH);
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setLayout(new BorderLayout());
            getContentPane().setBackground(Color.DARK_GRAY);

            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    returnToOwner();
                }
            });

            owner.setVisible(false);

            JLabel header = new JLabel(
                "<html>Click <b>Keep</b> to mark a photo for the final album &nbsp;|&nbsp; "
                + "<b>Remove</b> only takes it out of this comparison, your original photos are untouched "
                + "&nbsp;|&nbsp; scroll/drag on a photo to zoom in and inspect sharpness</html>",
                SwingConstants.CENTER);
            header.setForeground(Color.WHITE);
            header.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            add(header, BorderLayout.NORTH);

            gridPanel = new JPanel(new GridLayout(0, computeColumns(files.size()), 14, 14));
            gridPanel.setBackground(Color.DARK_GRAY);
            gridPanel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
            for (File file : files) {
                JPanel cell = buildCell(file);
                cells.put(file, cell);
                gridPanel.add(cell);
            }

            JPanel gridWrapper = new JPanel(new BorderLayout());
            gridWrapper.setBackground(Color.DARK_GRAY);
            gridWrapper.add(gridPanel, BorderLayout.NORTH);

            JScrollPane scrollPane = new JScrollPane(gridWrapper);
            scrollPane.getVerticalScrollBar().setUnitIncrement(24);
            scrollPane.setBorder(null);
            add(scrollPane, BorderLayout.CENTER);

            JPanel footer = new JPanel(new BorderLayout());
            footer.setBackground(Color.DARK_GRAY);

            summaryLabel = new JLabel();
            summaryLabel.setForeground(Color.WHITE);
            summaryLabel.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 10));
            footer.add(summaryLabel, BorderLayout.WEST);

            JButton closeButton = new JButton("Done Comparing - Back to Review (ESC)");
            closeButton.setPreferredSize(new Dimension(280, 40));
            closeButton.addActionListener(e -> returnToOwner());
            JPanel closeWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            closeWrap.setBackground(Color.DARK_GRAY);
            closeWrap.add(closeButton);
            footer.add(closeWrap, BorderLayout.EAST);

            add(footer, BorderLayout.SOUTH);

            InputMap inputMap = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            ActionMap actionMap = getRootPane().getActionMap();
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeCompare");
            actionMap.put("closeCompare", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    returnToOwner();
                }
            });

            updateSummary();
            setVisible(true);
            loadAllThumbnails();
        }

        private int computeColumns(int count) {
            if (count <= 1) return 1;
            return Math.max(2, Math.min(4, (int) Math.ceil(Math.sqrt(count))));
        }

        private JPanel buildCell(File file) {
            JPanel cell = new JPanel(new BorderLayout(0, 4));
            cell.setBackground(Color.BLACK);
            cell.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
            cell.setPreferredSize(new Dimension(400, 460));

            ImagePanel panel = new ImagePanel();
            panel.setPreferredSize(new Dimension(400, 400));
            panel.setSelected(owner.isSelectedForAlbum(file));
            imagePanels.put(file, panel);
            cell.add(panel, BorderLayout.CENTER);

            JLabel nameLabel = new JLabel(file.getName(), SwingConstants.CENTER);
            nameLabel.setForeground(Color.LIGHT_GRAY);
            nameLabel.setBorder(BorderFactory.createEmptyBorder(4, 4, 2, 4));

            JButton keepButton = new JButton();
            keepButton.setPreferredSize(new Dimension(120, 32));
            keepButtons.put(file, keepButton);
            styleKeepButton(keepButton, owner.isSelectedForAlbum(file));
            keepButton.addActionListener(e -> {
                owner.toggleSelectedForAlbum(file);
                boolean kept = owner.isSelectedForAlbum(file);
                panel.setSelected(kept);
                styleKeepButton(keepButton, kept);
                updateSummary();
            });

            JButton removeButton = new JButton("✕ Remove");
            removeButton.setPreferredSize(new Dimension(110, 32));
            removeButton.addActionListener(e -> removeFromComparison(file));

            JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 4));
            controls.setBackground(Color.BLACK);
            controls.add(keepButton);
            controls.add(removeButton);

            JPanel south = new JPanel(new BorderLayout());
            south.setBackground(Color.BLACK);
            south.add(nameLabel, BorderLayout.NORTH);
            south.add(controls, BorderLayout.CENTER);

            cell.add(south, BorderLayout.SOUTH);
            return cell;
        }

        private void styleKeepButton(JButton button, boolean kept) {
            if (kept) {
                button.setText("✓ Kept");
                button.setBackground(new Color(0, 170, 0));
                button.setForeground(Color.WHITE);
            } else {
                button.setText("Keep");
                button.setBackground(null);
                button.setForeground(Color.BLACK);
            }
        }

        private void loadAllThumbnails() {
            for (File file : new ArrayList<>(files)) {
                loadImageAsync(file,
                    img -> {
                        ImagePanel panel = imagePanels.get(file);
                        if (panel == null || img == null) return; // removed from tray, or failed to load
                        int degrees = owner.getRotationDegrees(file);
                        panel.setImage(degrees == 0 ? img : applyRotationDegrees(img, degrees));
                    },
                    ex -> { /* leave that cell's panel blank; the filename label still identifies it */ });
            }
        }

        private void removeFromComparison(File file) {
            owner.removeFromCompareTray(file);

            JPanel cell = cells.remove(file);
            if (cell != null) {
                gridPanel.remove(cell);
                gridPanel.revalidate();
                gridPanel.repaint();
            }
            imagePanels.remove(file);
            keepButtons.remove(file);
            files.remove(file);

            updateSummary();

            if (files.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Comparison tray is empty.",
                    "Info", JOptionPane.INFORMATION_MESSAGE);
                returnToOwner();
            }
        }

        private void updateSummary() {
            long keptCount = files.stream().filter(owner::isSelectedForAlbum).count();
            summaryLabel.setText(files.size() + " photo(s) in comparison, " + keptCount + " kept for the final album");
        }

        private void returnToOwner() {
            owner.refreshAfterCompare();
            owner.setVisible(true);
            owner.toFront();
            owner.requestFocus();
            dispose();
        }
    }

    // Inner class for reviewing selected images
    class SelectedImagesReviewer extends JFrame {
        private List<File> selectedImages;
        private int currentIndex;
        private ImagePanel imagePanel;
        private JLabel infoLabel;
        private JLabel zoomLabel;
        private JFrame parentWindow;
        private int loadToken = 0;

        public SelectedImagesReviewer(List<File> selectedImages, JFrame parentWindow) {
            this.selectedImages = new ArrayList<>(selectedImages);
            this.parentWindow = parentWindow;
            this.currentIndex = 0;

            setTitle("Review Selected Images");
            setExtendedState(JFrame.MAXIMIZED_BOTH);
            setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            setLayout(new BorderLayout());

            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    parentWindow.setVisible(true);
                    parentWindow.toFront();
                    parentWindow.requestFocus();
                }
            });

            parentWindow.setVisible(false);

            imagePanel = new ImagePanel();
            imagePanel.setOnZoomChanged(this::updateZoomLabel);
            add(imagePanel, BorderLayout.CENTER);

            JPanel controlPanel = new JPanel(new BorderLayout());
            controlPanel.setBackground(Color.DARK_GRAY);

            infoLabel = new JLabel();
            infoLabel.setForeground(Color.WHITE);
            infoLabel.setHorizontalAlignment(SwingConstants.CENTER);
            infoLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            zoomLabel = new JLabel();
            zoomLabel.setForeground(Color.LIGHT_GRAY);
            zoomLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 15));

            JPanel topPanel = new JPanel(new BorderLayout());
            topPanel.setBackground(Color.DARK_GRAY);
            topPanel.add(infoLabel, BorderLayout.CENTER);
            topPanel.add(zoomLabel, BorderLayout.EAST);
            controlPanel.add(topPanel, BorderLayout.NORTH);

            JPanel buttonPanel = new JPanel(new FlowLayout());
            buttonPanel.setBackground(Color.DARK_GRAY);

            JButton prevButton = new JButton("Previous (←)");
            prevButton.setPreferredSize(new Dimension(150, 40));
            prevButton.addActionListener(e -> showPrevious());

            JButton nextButton = new JButton("Next (→)");
            nextButton.setPreferredSize(new Dimension(150, 40));
            nextButton.addActionListener(e -> showNext());

            JButton removeButton = new JButton("Remove (D)");
            removeButton.setPreferredSize(new Dimension(150, 40));
            removeButton.addActionListener(e -> removeFromSelection());

            JButton closeButton = new JButton("Close (ESC)");
            closeButton.setPreferredSize(new Dimension(150, 40));
            closeButton.addActionListener(e -> closeReviewer());

            buttonPanel.add(prevButton);
            buttonPanel.add(nextButton);
            buttonPanel.add(removeButton);
            buttonPanel.add(closeButton);
            controlPanel.add(buttonPanel, BorderLayout.CENTER);

            add(controlPanel, BorderLayout.SOUTH);

            setupKeyBindings();

            setFocusable(true);
            setVisible(true);
            displayCurrentImage();
        }

        private void setupKeyBindings() {
            InputMap inputMap = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            ActionMap actionMap = getRootPane().getActionMap();

            bindKey(inputMap, actionMap, KeyEvent.VK_LEFT, "prevSelected", e -> showPrevious());
            bindKey(inputMap, actionMap, KeyEvent.VK_RIGHT, "nextSelected", e -> showNext());
            bindKey(inputMap, actionMap, KeyEvent.VK_D, "removeFromSelection", e -> removeFromSelection());
            bindKey(inputMap, actionMap, KeyEvent.VK_0, "resetZoom", e -> imagePanel.resetZoom());
            bindKey(inputMap, actionMap, KeyEvent.VK_PLUS, "zoomIn", e -> imagePanel.zoomIn());
            bindKey(inputMap, actionMap, KeyEvent.VK_EQUALS, "zoomInAlt", e -> imagePanel.zoomIn());
            bindKey(inputMap, actionMap, KeyEvent.VK_MINUS, "zoomOut", e -> imagePanel.zoomOut());
            bindKey(inputMap, actionMap, KeyEvent.VK_ESCAPE, "closeReviewer", e -> closeReviewer());
        }

        private void updateZoomLabel() {
            zoomLabel.setText("Zoom: " + imagePanel.getZoomPercent() + "%");
        }

        private void bindKey(InputMap inputMap, ActionMap actionMap, int keyCode, String name, ActionListener listener) {
            inputMap.put(KeyStroke.getKeyStroke(keyCode, 0), name);
            actionMap.put(name, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    listener.actionPerformed(e);
                }
            });
        }

        private void displayCurrentImage() {
            if (selectedImages.isEmpty()) {
                JOptionPane.showMessageDialog(this, "All images removed from selection.",
                    "Info", JOptionPane.INFORMATION_MESSAGE);
                closeReviewer();
                return;
            }

            if (currentIndex >= selectedImages.size()) {
                currentIndex = selectedImages.size() - 1;
            }
            if (currentIndex < 0) {
                currentIndex = 0;
            }

            final File imageFile = selectedImages.get(currentIndex);
            final int requestToken = ++loadToken;
            infoLabel.setText("Loading " + imageFile.getName() + "...");

            loadImageAsync(imageFile,
                img -> {
                    if (requestToken != loadToken) return;
                    if (img == null) {
                        infoLabel.setText("Error loading image: " + imageFile.getName());
                        return;
                    }
                    if (parentWindow instanceof ImageReviewWindow) {
                        int degrees = ((ImageReviewWindow) parentWindow).getRotationDegrees(imageFile);
                        if (degrees != 0) {
                            img = applyRotationDegrees(img, degrees);
                        }
                    }
                    imagePanel.setImage(img);
                    updateZoomLabel();
                    imagePanel.setSelected(true);
                    infoLabel.setText(String.format("Selected Image %d of %d: %s (%dx%d)",
                        currentIndex + 1, selectedImages.size(),
                        imageFile.getName(), img.getWidth(), img.getHeight()));
                },
                ex -> {
                    if (requestToken != loadToken) return;
                    infoLabel.setText("Error loading image: " + imageFile.getName());
                });
        }

        private void showPrevious() {
            if (currentIndex > 0) {
                currentIndex--;
                displayCurrentImage();
            }
        }

        private void showNext() {
            if (currentIndex < selectedImages.size() - 1) {
                currentIndex++;
                displayCurrentImage();
            }
        }

        private void removeFromSelection() {
            if (selectedImages.isEmpty()) return;

            int confirm = JOptionPane.showConfirmDialog(this,
                "Remove this image from selection?",
                "Confirm", JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                File removedFile = selectedImages.remove(currentIndex);

                if (parentWindow instanceof ImageReviewWindow) {
                    ((ImageReviewWindow) parentWindow).selectedImages.remove(removedFile);
                }

                if (currentIndex >= selectedImages.size() && currentIndex > 0) {
                    currentIndex--;
                }
                displayCurrentImage();
            }
        }

        private void closeReviewer() {
            parentWindow.setVisible(true);
            parentWindow.toFront();
            parentWindow.requestFocus();
            dispose();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                e.printStackTrace();
            }
            new PhotoCurateApp().setVisible(true);
        });
    }
}

