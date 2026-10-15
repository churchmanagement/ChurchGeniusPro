package com.churchgeniuspro.mobile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mobile Phase 4: the web↔native bridge must stay loaded everywhere, stay inert in a
 * browser, and the PWA machinery must stay off inside the app shell.
 */
class NativeBridgeRatchetTest {

    private static String read(String name) throws Exception {
        return Files.readString(Path.of("src/main/resources/static").resolve(name), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("native-bridge.js is gated on window.Capacitor and covers downloads, window.open, print and PWA")
    void bridgeContract() throws Exception {
        String js = read("native-bridge.js");
        int gate = js.indexOf("if (!isNative) return;");
        assertThat(gate).as("everything after the gate is native-only").isPositive();
        String before = js.substring(0, gate);
        assertThat(before).doesNotContain("window.open =").doesNotContain("window.print =").doesNotContain("addEventListener('click'");
        assertThat(js).contains("a[download]").contains("P.Filesystem.writeFile").contains("P.Share.share")
                      .contains("window.open = function").contains("window.print = function")
                      .contains("P.Printer.printHtml").contains("P.Printer.print(")
                      .contains("r.unregister()").contains("window.CGP_SKIP_WEB_PWA = true");
    }

    @Test
    @DisplayName("loaded on every page: shell.js injects it; memberHome and login carry the tag")
    void loadedEverywhere() throws Exception {
        assertThat(read("shell.js")).contains("add('/native-bridge.js', 'native-bridge-script')");
        assertThat(read("memberHome.html")).contains("<script src=\"/native-bridge.js\"></script>");
        assertThat(read("login.html")).contains("<script src=\"/native-bridge.js\"></script>");
    }

    @Test
    @DisplayName("inside the shell: no service worker, install banner or Web Push")
    void pwaOffInApp() throws Exception {
        assertThat(read("session.js")).contains("if (window.Capacitor) { CGP_nativeRegisterPush(); return; }");
        assertThat(read("pwa.js")).contains("if (window.Capacitor) return;");
        assertThat(read("login.html")).contains("if (!window.Capacitor) navigator.serviceWorker.register('/sw.js')");
        assertThat(read("memberHome.html")).contains("if (window.Capacitor) {   /* Mobile Phase 4/5");
    }

    @Test
    @DisplayName("Phase 5: native push is registered by the bridge, keyed per user, and handed to logout")
    void nativePushContract() throws Exception {
        String js = read("native-bridge.js");
        int gate = js.indexOf("if (!isNative) return;");
        assertThat(js.substring(0, gate)).doesNotContain("PushNotifications");
        assertThat(js).contains("P.PushNotifications.checkPermissions()")
                      .contains("P.PushNotifications.requestPermissions()")
                      .contains("P.PushNotifications.register()")
                      .contains("addListener('registration'")
                      .contains("addListener('pushNotificationActionPerformed'")
                      .contains("fetch('/api/push/register-native'")
                      .contains("window.CGP_NATIVE.registerPush   = registerPush")
                      .contains("window.CGP_NATIVE.getPushToken   = getPushToken")
                      .contains("new CustomEvent('cgp:native-ready')");
        // Both logout paths resolve the app token as the "endpoint" the server deactivates.
        assertThat(read("session.js")).contains("if (window.Capacitor) return finish(window.CGP_NATIVE && window.CGP_NATIVE.getPushToken");
        assertThat(read("memberHome.html")).contains("if (window.Capacitor) return finish(window.CGP_NATIVE && window.CGP_NATIVE.getPushToken")
                                           .contains("window.CGP_NATIVE.registerPush()");
        // The web never calls the native endpoint: no page outside the bridge references it.
        for (String page : new String[] {"session.js", "memberHome.html", "login.html", "pwa.js", "sw.js", "shell.js"}) {
            assertThat(read(page)).as(page).doesNotContain("register-native");
        }
    }

    @Test
    @DisplayName("CSP lets the page read back its own blob:/data: objects (needed to hand a PDF to the share sheet)")
    void cspAllowsBlob() throws Exception {
        String csp = Files.readString(Path.of("src/main/java/com/churchgeniuspro/webfilter/CspFilter.java"), StandardCharsets.UTF_8);
        assertThat(csp).contains("connect-src 'self' blob: data: https://api.stripe.com");
    }
}
