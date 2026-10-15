package com.churchgeniuspro.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * Mobile Phase 3: the two files the app stores read to verify that this site "owns"
 * the app, so emailed links open inside it (Android App Links, iOS Universal Links).
 *
 * <p>Both must be served from {@code /.well-known/} with {@code application/json}
 * and no redirect; iOS requires the file name WITHOUT an extension, which the static
 * handler would not type correctly, hence this controller. The files live under
 * {@code static/.well-known/} and carry placeholders until the signing certificate
 * fingerprint (Play Console → App integrity) and the Apple Team ID are known — see
 * the mobile project's README. Serving the placeholders is harmless: verification
 * simply fails and links keep opening in the browser.
 */
@RestController
public class WellKnownController {

    private static final CacheControl ONE_HOUR = CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic();

    @GetMapping(value = "/.well-known/assetlinks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ClassPathResource> assetLinks() {
        return ResponseEntity.ok().cacheControl(ONE_HOUR).contentType(MediaType.APPLICATION_JSON)
                .body(new ClassPathResource("static/.well-known/assetlinks.json"));
    }

    @GetMapping(value = {"/.well-known/apple-app-site-association", "/apple-app-site-association"},
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ClassPathResource> appleAppSiteAssociation() {
        return ResponseEntity.ok().cacheControl(ONE_HOUR).contentType(MediaType.APPLICATION_JSON)
                .body(new ClassPathResource("static/.well-known/apple-app-site-association"));
    }
}
