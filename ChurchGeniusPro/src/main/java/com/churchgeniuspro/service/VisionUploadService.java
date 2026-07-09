package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.OpenAiUsage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Set;

/**
 * Cost-protection gateway for OpenAI Vision uploads.
 *
 * <p>Before any image/PDF is sent to the Vision model this service:
 * <ol>
 *   <li>checks the church's Vision upload quota (and enable flag),</li>
 *   <li>enforces the per-church file-size, type and PDF page-count limits, and</li>
 *   <li>down-scales images (longest side → configured pixels, re-encoded as JPEG
 *       at the configured quality) so only a small image is sent to OpenAI.</li>
 * </ol>
 *
 * <p>These limits apply ONLY to uploads that go to OpenAI Vision — callers use
 * {@link #available(String)} to decide whether to take the Vision path at all,
 * leaving non-Vision uploads (browser OCR, plain image storage) untouched.
 */
@Service
public class VisionUploadService {

    private static final Logger log = LoggerFactory.getLogger(VisionUploadService.class);
    private static final Set<String> ALLOWED = Set.of("jpg", "jpeg", "png", "pdf");

    private final OpenAiUsageService usage;

    public VisionUploadService(OpenAiUsageService usage) {
        this.usage = usage;
    }

    /** Thrown when an upload is rejected by the per-church limits. */
    public static class VisionRejectedException extends RuntimeException {
        public VisionRejectedException(String message) { super(message); }
    }

    /** A processed upload ready to be sent to OpenAI Vision. */
    public static class Prepared {
        public final byte[] bytes;
        public final String contentType;
        public Prepared(byte[] bytes, String contentType) { this.bytes = bytes; this.contentType = contentType; }
    }

    /** {@code true} when this church may still use OpenAI Vision (enabled + under limit). */
    public boolean available(String clientId) {
        return usage.visionAvailable(clientId);
    }

    /**
     * Validates an upload against the church's limits and returns a (possibly
     * down-scaled) copy ready for OpenAI Vision. Throws {@link VisionRejectedException}
     * with a user-facing message when the file is the wrong type, too large, or has
     * too many pages.
     */
    public Prepared prepare(String clientId, byte[] data, String contentType, String filename) {
        OpenAiUsage s = usage.getOrCreate(clientId);

        String ext = ext(filename, contentType);
        if (!ALLOWED.contains(ext)) {
            throw new VisionRejectedException("Unsupported file type for AI scanning. Allowed: JPG, JPEG, PNG, PDF.");
        }

        long maxBytes = (long) Math.max(1, s.getMaxFileSizeMb()) * 1024L * 1024L;
        if (data.length > maxBytes) {
            throw new VisionRejectedException("File is too large for AI scanning (max " + s.getMaxFileSizeMb()
                    + " MB). Please upload a smaller or lower-resolution scan.");
        }

        if ("pdf".equals(ext)) {
            int pages = pdfPageCount(data);
            if (pages > s.getMaxPagesPerUpload()) {
                throw new VisionRejectedException("This PDF has " + pages + " page(s); the AI-scan limit is "
                        + s.getMaxPagesPerUpload() + ". Please upload a single-page document.");
            }
            return new Prepared(data, contentType);   // PDFs are sent / parsed as-is
        }

        // Image: optionally down-scale + re-encode as JPEG to cut OpenAI cost.
        if (Boolean.TRUE.equals(s.getAutoResizeImages())) {
            byte[] resized = resizeJpeg(data, s.getMaxImageResolutionPx(), s.getJpegQuality());
            if (resized != null) return new Prepared(resized, "image/jpeg");
        }
        return new Prepared(data, contentType);
    }

    /** Records one consumed Vision upload against the church quota. */
    public void recordUse(String clientId) {
        usage.incrementVisionUploads(clientId, 1);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String ext(String filename, String contentType) {
        String f = filename == null ? "" : filename.toLowerCase();
        int dot = f.lastIndexOf('.');
        if (dot >= 0 && dot < f.length() - 1) return f.substring(dot + 1);
        String ct = contentType == null ? "" : contentType.toLowerCase();
        if (ct.contains("pdf"))  return "pdf";
        if (ct.contains("png"))  return "png";
        if (ct.contains("jpeg") || ct.contains("jpg")) return "jpg";
        return "";
    }

    private static int pdfPageCount(byte[] data) {
        try (PDDocument doc = Loader.loadPDF(data)) {
            return doc.getNumberOfPages();
        } catch (Exception e) {
            log.warn("[Vision] could not read PDF page count: {}", e.getMessage());
            return 1;   // be lenient — treat unreadable PDFs as single-page
        }
    }

    /**
     * Scales an image so its longest side is at most {@code maxSide} pixels and
     * re-encodes it as JPEG at {@code quality}% — returning {@code null} on any
     * failure so callers fall back to the original bytes.
     */
    private static byte[] resizeJpeg(byte[] data, int maxSide, int quality) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
            if (src == null) return null;
            int w = src.getWidth(), h = src.getHeight();
            if (w <= 0 || h <= 0) return null;

            int longSide = Math.max(w, h);
            double scale = (maxSide > 0 && longSide > maxSide) ? ((double) maxSide / longSide) : 1.0;
            int nw = Math.max(1, (int) Math.round(w * scale));
            int nh = Math.max(1, (int) Math.round(h * scale));

            BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);          // flatten any alpha onto white
            g.fillRect(0, 0, nw, nh);
            g.drawImage(src, 0, 0, nw, nh, null);
            g.dispose();

            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            float q = Math.max(0.4f, Math.min(1f, quality / 100f));
            p.setCompressionQuality(q);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(dst, null, null), p);
            } finally {
                writer.dispose();
            }
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("[Vision] image resize failed, sending original: {}", e.getMessage());
            return null;
        }
    }
}
