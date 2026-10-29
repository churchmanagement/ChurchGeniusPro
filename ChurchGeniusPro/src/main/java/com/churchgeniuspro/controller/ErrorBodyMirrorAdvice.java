package com.churchgeniuspro.controller;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error-handling audit 2026-10-28, L4 — additive unification of error bodies.
 *
 * <p>The app grew three error-JSON shapes ({@code {status, message}}, {@code {error, code}},
 * {@code {error, ref}}) and every page reads exactly one key. For a response with status
 * &ge; 400 and a Map body, this advice copies {@code message} into a missing {@code error}
 * or {@code error} into a missing {@code message}. Nothing is ever overwritten or removed,
 * so every page keeps reading the key it reads today, and new code can always read
 * {@code error || message}.
 *
 * <p>Package-scoped like {@link GlobalExceptionHandler}, so actuator and framework error
 * bodies are untouched. Filters that write JSON themselves include both keys directly.
 */
@ControllerAdvice(basePackages = "com.churchgeniuspro")
public class ErrorBodyMirrorAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof Map<?, ?> map)) return body;
        if (!(response instanceof ServletServerHttpResponse sr)) return body;
        if (sr.getServletResponse().getStatus() < 400) return body;
        return mirror(map);
    }

    /** The mirrored copy, or the same map when nothing is missing. Package-visible for tests. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object mirror(Map<?, ?> map) {
        Object error = map.get("error");
        Object message = map.get("message");
        boolean addError = error == null && message instanceof String;
        boolean addMessage = message == null && error instanceof String;
        if (!addError && !addMessage) return map;
        Map copy = new LinkedHashMap(map);
        if (addError) copy.put("error", message);
        if (addMessage) copy.put("message", error);
        return copy;
    }
}
