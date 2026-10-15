package com.churchgeniuspro.util;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * Member-photo sizing, shared by every writer of {@code family_member.photo_data}.
 *
 * <p>Two sizes are stored per member:
 * <ul>
 *   <li>the <b>full photo</b> — {@link #normalise(String)} caps the longest side at
 *       {@value #MAX_FULL_PX} px as a JPEG at quality {@value #FULL_QUALITY} (the same
 *       limit family.html applies in the browser); smaller images are stored untouched
 *       and are never enlarged;</li>
 *   <li>the <b>thumbnail</b> — {@link #thumbnail(String)} is a {@value #THUMB_PX} px
 *       longest-side JPEG at quality {@value #THUMB_QUALITY}, read by the View Family
 *       list, the sidebar avatar, attendance and badges.</li>
 * </ul>
 * The thumbnail is what those pages show first ({@code COALESCE(photo_thumbnail, photo_data)}),
 * so any code that sets {@code photoData} must set the thumbnail in the same write —
 * approval of a membership request forgot to, which left View Family showing the old
 * picture while the Family page showed the new one.
 *
 * <p>A photo that ImageIO cannot decode (WebP, HEIC, …) is stored as submitted, but its
 * thumbnail is {@code null} rather than the multi-megabyte original.
 */
public final class PhotoImages {

    public static final int MAX_FULL_PX = 800;
    public static final float FULL_QUALITY = 0.82f;
    public static final int THUMB_PX = 60;
    public static final float THUMB_QUALITY = 0.85f;

    private PhotoImages() {}

    /** Full-size photo: downscaled to {@value #MAX_FULL_PX} px when larger, otherwise returned as-is. */
    public static String normalise(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) return null;
        BufferedImage img = decode(dataUrl);
        if (img == null) return dataUrl;
        if (img.getWidth() <= MAX_FULL_PX && img.getHeight() <= MAX_FULL_PX) return dataUrl;
        String out = scaleToJpeg(img, MAX_FULL_PX, FULL_QUALITY);
        return out != null ? out : dataUrl;
    }

    /** {@value #THUMB_PX} px thumbnail, or {@code null} when there is no decodable photo. */
    public static String thumbnail(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) return null;
        BufferedImage img = decode(dataUrl);
        if (img == null) return null;
        if (img.getWidth() <= THUMB_PX && img.getHeight() <= THUMB_PX) return dataUrl;   // never enlarge
        return scaleToJpeg(img, THUMB_PX, THUMB_QUALITY);
    }

    /**
     * True when the data URL is an image this application can actually use — decodable by
     * ImageIO (JPEG, PNG, GIF, BMP) and therefore renderable by browsers. A HEIC/HEIF
     * iPhone upload or WebP carries the {@code data:image/} prefix but decodes to nothing,
     * and must be treated as "no photo" rather than shown as a broken image.
     */
    public static boolean isUsable(String dataUrl) {
        return dataUrl != null && !dataUrl.isBlank() && decode(dataUrl) != null;
    }

    /** Decodes a {@code data:image/...;base64,} URL; {@code null} when it is not one or cannot be read. */
    public static BufferedImage decode(String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0 || !dataUrl.regionMatches(true, 0, "data:image/", 0, 11)) return null;
            byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1).trim());
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    /** Longest side → {@code maxPx}, aspect kept, transparency flattened on white, JPEG at {@code quality}. */
    static String scaleToJpeg(BufferedImage src, int maxPx, float quality) {
        try {
            double scale = (double) maxPx / Math.max(src.getWidth(), src.getHeight());
            int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();

            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(out, null, null), p);
            } finally {
                writer.dispose();
            }
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }
}
