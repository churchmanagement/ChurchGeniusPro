package com.churchgeniuspro.ai.consent;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Reads a small JSON body once so the consent gate can inspect it and the controller still can. */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    static final int MAX_BYTES = 256 * 1024;

    private final byte[] body;

    private CachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    /** Null when the body is larger than {@link #MAX_BYTES} (the gate then refuses). */
    static CachedBodyRequest of(HttpServletRequest request) throws IOException {
        try (InputStream in = request.getInputStream()) {
            byte[] b = in.readNBytes(MAX_BYTES + 1);
            if (b.length > MAX_BYTES) return null;
            return new CachedBodyRequest(request, b);
        }
    }

    String text() { return new String(body, StandardCharsets.UTF_8); }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public boolean isFinished() { return in.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener l) { throw new UnsupportedOperationException(); }
            @Override public int read() { return in.read(); }
            @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() { return body.length; }

    @Override
    public long getContentLengthLong() { return body.length; }
}
