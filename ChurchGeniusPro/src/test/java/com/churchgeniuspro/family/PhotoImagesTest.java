package com.churchgeniuspro.family;

import com.churchgeniuspro.util.PhotoImages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** PhotoImages — the one place member photos are sized (full ≤ 800 px, thumbnail ≤ 60 px). */
@DisplayName("PhotoImages — full-photo normalisation and thumbnails")
class PhotoImagesTest {

    static String png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) img.setRGB(x, y, (x + y) % 2 == 0 ? 0xFF3366CC : 0x00000000);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ImageIO.write(img, "png", b);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(b.toByteArray());
    }

    static BufferedImage decode(String dataUrl) {
        return PhotoImages.decode(dataUrl);
    }

    @Test
    @DisplayName("null / blank / not-an-image → null thumbnail, unchanged full photo")
    void nullAndUndecodable() {
        assertThat(PhotoImages.thumbnail(null)).isNull();
        assertThat(PhotoImages.thumbnail("  ")).isNull();
        assertThat(PhotoImages.normalise(null)).isNull();
        String garbage = "data:image/webp;base64,AAAA";
        assertThat(PhotoImages.thumbnail(garbage)).as("undecodable → no thumbnail, never the original").isNull();
        assertThat(PhotoImages.normalise(garbage)).as("undecodable full photo stored as submitted").isEqualTo(garbage);
        assertThat(PhotoImages.thumbnail("not a data url")).isNull();
    }

    @Test
    @DisplayName("small images are stored untouched and never enlarged")
    void smallPassThrough() throws Exception {
        String small = png(40, 30);
        assertThat(PhotoImages.normalise(small)).isSameAs(small);
        assertThat(PhotoImages.thumbnail(small)).isSameAs(small);
        String mid = png(300, 200);
        assertThat(PhotoImages.normalise(mid)).as("≤ 800 px: no recompression").isSameAs(mid);
        BufferedImage t = decode(PhotoImages.thumbnail(mid));
        assertThat(Math.max(t.getWidth(), t.getHeight())).isEqualTo(60);
    }

    @Test
    @DisplayName("a large upload becomes an 800 px JPEG; its thumbnail a 60 px JPEG; aspect ratio kept")
    void largeIsDownscaled() throws Exception {
        String big = png(2000, 1000);
        String full = PhotoImages.normalise(big);
        assertThat(full).startsWith("data:image/jpeg;base64,");
        BufferedImage f = decode(full);
        assertThat(f.getWidth()).isEqualTo(800);
        assertThat(f.getHeight()).isEqualTo(400);

        String thumb = PhotoImages.thumbnail(full);
        assertThat(thumb).startsWith("data:image/jpeg;base64,");
        BufferedImage t = decode(thumb);
        assertThat(t.getWidth()).isEqualTo(60);
        assertThat(t.getHeight()).isEqualTo(30);
        assertThat(thumb.length()).as("thumbnail is tiny").isLessThan(6_000);
    }

    @Test
    @DisplayName("portrait images scale on the longest side")
    void portrait() throws Exception {
        BufferedImage f = decode(PhotoImages.normalise(png(900, 1800)));
        assertThat(f.getWidth()).isEqualTo(400);
        assertThat(f.getHeight()).isEqualTo(800);
    }

    @Test
    @DisplayName("constants match the browser-side cap in family.html (800 px, q 0.82) and the approved thumbnail spec (60 px, q 0.85)")
    void constants() throws Exception {
        assertThat(PhotoImages.MAX_FULL_PX).isEqualTo(800);
        assertThat(PhotoImages.FULL_QUALITY).isEqualTo(0.82f);
        assertThat(PhotoImages.THUMB_PX).isEqualTo(60);
        assertThat(PhotoImages.THUMB_QUALITY).isEqualTo(0.85f);
        String page = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/static/family.html"));
        assertThat(page).contains("const MAX = 800;").contains("toDataURL('image/jpeg', 0.82)");
    }
}
