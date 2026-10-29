package com.churchgeniuspro.webfilter;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;

/** The request path for a log line, never throwing (filters log on their own failure path). */
final class RequestPathsSafe {
    private RequestPathsSafe() {}

    static String of(ServletRequest req) {
        try {
            return req instanceof HttpServletRequest h ? h.getRequestURI() : "?";
        } catch (Exception e) {
            return "?";
        }
    }
}
