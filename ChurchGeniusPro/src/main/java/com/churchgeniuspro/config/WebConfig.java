package com.churchgeniuspro.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.xml.MappingJackson2XmlHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * HTTP-level cache headers for static resources.
 *
 * <p>These headers work alongside the service worker (sw.js) to ensure that
 * browsers never serve stale HTML or service-worker scripts after a deployment,
 * even on mobile browsers or platforms where the SW may not yet be active.
 *
 * <ul>
 *   <li><strong>HTML / root</strong> — {@code no-store}: browser must revalidate
 *       every navigation; never serves a cached copy.</li>
 *   <li><strong>sw.js</strong> — {@code no-store}: the browser checks for a new
 *       service worker on every page load as required by the SW spec.</li>
 *   <li><strong>JS / CSS bundles (non-fingerprinted)</strong> —
 *       {@code no-cache} (must-revalidate): browser must revalidate with the
 *       server on every request (sends If-None-Match / If-Modified-Since).
 *       If the file hasn't changed the server returns 304 Not Modified — no
 *       bandwidth wasted, but the browser can never serve a stale version.
 *       This is belt-and-suspenders alongside the SW's network-first strategy
 *       for JS/CSS: even on browsers without an active SW, users always run
 *       the latest code after a deployment.</li>
 *   <li><strong>Fingerprinted assets (/assets/**)</strong> — {@code immutable,
 *       max-age=1 year}: content-hashed filenames mean a new hash = new URL,
 *       so these are safe to cache forever.</li>
 *   <li><strong>Images / icons</strong> — {@code public, max-age=7 days}:
 *       long-lived cache with periodic refresh.</li>
 * </ul>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * REST endpoints must always return JSON. jackson-dataformat-xml is on the
     * classpath (pulled in transitively), so without this Spring would happily
     * serve XML to clients whose Accept header prefers it — e.g. a browser hitting
     * an API URL directly, or a fetch() without an explicit Accept — which then
     * fails {@code response.json()} on the frontend. Default to JSON for
     * unspecified/"*&#47;*" requests.
     */
    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        configurer.defaultContentType(MediaType.APPLICATION_JSON).favorParameter(false);
    }

    /**
     * Remove the XML message converter entirely so API responses are never
     * serialized as XML, regardless of the request's Accept header.
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.removeIf(c -> c instanceof MappingJackson2XmlHttpMessageConverter);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {

        // ── sw.js: never cache (browser must check for updates on every load) ──
        registry.addResourceHandler("/sw.js")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noStore());

        // ── Fingerprinted assets: immutable (content-addressed, safe forever) ──
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(
                        CacheControl.maxAge(365, TimeUnit.DAYS)
                                    .cachePublic()
                                    .immutable()
                );

        // ── JS / CSS bundles (non-fingerprinted): no-cache (must-revalidate) ──
        // Browser revalidates with server on every request (304 if unchanged).
        // Ensures new deployments are picked up immediately on all platforms,
        // including mobile browsers and PWA webviews without an active SW.
        registry.addResourceHandler("/*.js", "/*.css")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());

        // ── Images and icons: 7-day cache ──
        registry.addResourceHandler("/*.png", "/*.jpg", "/*.jpeg",
                                     "/*.gif", "/*.svg", "/*.ico",
                                     "/*.webp")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(
                        CacheControl.maxAge(7, TimeUnit.DAYS)
                                    .cachePublic()
                );

        // ── HTML pages: no-store (always fetch fresh from server) ──
        registry.addResourceHandler("/*.html")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noStore());
    }
}
