package com.churchgeniuspro.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Lightweight endpoint that returns the application build version.
 *
 * <p>The service worker polls {@code /api/app-version} periodically and
 * compares the returned {@code version} string to the one it saw at install
 * time.  When the string changes (i.e. a new build was deployed), the SW
 * calls {@code registration.update()} to fetch the latest {@code sw.js},
 * which in turn triggers the full skipWaiting → controllerchange → reload
 * cycle so all clients receive fresh assets automatically.
 *
 * <p>The version is the JVM startup timestamp (epoch ms), which changes on
 * every deployment restart.  No build-system integration is required.
 */
@RestController
public class AppVersionController {

    /** Captured once when the JVM starts — changes on every deploy/restart. */
    private static final String BUILD_VERSION = String.valueOf(System.currentTimeMillis());

    @GetMapping("/api/app-version")
    public Map<String, String> getVersion() {
        return Map.of("version", BUILD_VERSION);
    }
}
