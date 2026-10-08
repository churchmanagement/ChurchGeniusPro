package com.churchgeniuspro.controller;

import com.churchgeniuspro.util.ClientIpResolver;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Catches any unhandled exception thrown from a @Controller or @RestController,
 * logs the full stack trace, and returns a consistent JSON error response.
 *
 * <p>ERROR-level output from here lands in {@code error-YYYY-MM-DD.log} (see
 * {@code logback-spring.xml}) with the full stack trace attached, alongside who was signed in,
 * where they were, and which request failed — the context that turns "NullPointerException
 * at 03:12" into something diagnosable.
 *
 * <p>Each 500 is stamped with a short reference that is both logged and returned to the
 * caller, so a user reporting "it said error a1b2c3d4" points straight at one log entry.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Shared with the login-protection layer so the IP recorded against an error matches
     * the IP recorded against that user's sign-in. See LOGIN_SECURITY.md §8.
     */
    @Value("${security.login-protection.trust-forwarded-headers:true}")
    private boolean trustForwardedHeaders;

    /**
     * Browser closed the connection before the server finished streaming a response.
     * Normal browser behaviour — not a server error.
     */
    @ExceptionHandler({ClientAbortException.class, AsyncRequestNotUsableException.class})
    public ResponseEntity<Void> handleClientAbort(Exception ex) {
        log.debug("Client disconnected mid-response (ignored): {}", ex.getMessage());
        return null;   // connection is already closed — there is nothing to write back
    }

    /**
     * Silently swallow 404s for missing static resources (browser noise).
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResource(NoResourceFoundException ex) {
        log.debug("Static resource not found: {}", ex.getMessage());
        return ResponseEntity.notFound().build();
    }

    /**
     * File exceeds the configured spring.servlet.multipart.max-file-size limit.
     * Returns HTTP 413 with a user-friendly message instead of a generic 500.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("Upload rejected — file too large: {}", ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "File is too large. Maximum allowed size is 20 MB.");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * Multipart stream ended unexpectedly — typically the client disconnected
     * mid-upload (browser cancelled, network hiccup, or file too large for an
     * upstream proxy).  Log at WARN (not ERROR) since this is a client-side event.
     * Returns HTTP 400 so the frontend can show a meaningful message.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> handleMultipart(MultipartException ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        String msg = cause.getMessage() != null ? cause.getMessage() : ex.getMessage();
        if (msg != null && (msg.contains("Stream ended unexpectedly") || msg.contains("Connection reset"))) {
            log.warn("Upload stream cut short (client likely disconnected or file too large): {}", msg);
        } else {
            log.error("Multipart parse failure: {}", msg, ex);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Upload failed. The file may be too large or the connection was interrupted.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * Content-negotiation failure: the matched endpoint cannot produce any media
     * type the client said it would accept (its {@code Accept} header doesn't include
     * the endpoint's {@code produces} type or {@code *​/*}). This is a client-side /
     * 406 condition — not a server fault — so we log it quietly (with the URL and
     * Accept header for diagnosis) and return 406 with no body. Returning a JSON body
     * here would be pointless: the client already said it won't accept it.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Void> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex,
                                                    HttpServletRequest request) {
        log.warn("406 Not Acceptable for {} {} — client Accept: '{}', endpoint can produce: {}",
                request.getMethod(), requestPath(request), request.getHeader("Accept"),
                ex.getSupportedMediaTypes());
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    /**
     * Catches all other unhandled exceptions, logs the full stack trace, and
     * returns a JSON 500 response.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAll(Exception ex, HttpServletRequest request) {
        // A client disconnect (browser navigated away / refreshed / cancelled the
        // fetch while a large response was still streaming) surfaces here wrapped
        // inside HttpMessageNotWritableException, so the dedicated ClientAbort
        // handler never matches. Detect it in the cause chain and stay quiet —
        // the socket is already closed, so writing a 500 body would only fail again.
        if (isClientDisconnect(ex)) {
            log.debug("Client disconnected before the response finished (ignored): {}", ex.getMessage());
            return null;
        }
        // Short reference shared between the log entry and the response, so a user who
        // reports "it said error a1b2c3d4" leads straight to one line in error-*.log.
        String ref = UUID.randomUUID().toString().substring(0, 8);

        log.error("Unhandled exception [ref={}] for {} {} | user={} | church={} | role={} | ip={} : {}",
                ref, request.getMethod(), requestPath(request),
                sessionAttr(request, "username"), sessionAttr(request, "churchName"),
                sessionAttr(request, "role"), ClientIpResolver.resolve(request, trustForwardedHeaders),
                ex.getMessage(), ex);

        Map<String, Object> body = new LinkedHashMap<>();
        // The message stays in the log (with the stack trace and the ref); the caller gets
        // the ref only — exception text is SQL, class names and paths (security audit P8).
        body.put("error", "An unexpected error occurred. Please try again or contact support quoting reference " + ref + ".");
        body.put("ref", ref);
        return ResponseEntity.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * Session attribute for the error context, without ever creating a session — an
     * anonymous request that fails must not be handed one as a side effect of logging.
     * Only non-sensitive identity attributes are read; the session id itself is not logged.
     */
    private static String sessionAttr(HttpServletRequest request, String name) {
        if (request == null) return "-";
        HttpSession session = request.getSession(false);
        if (session == null) return "-";
        Object value = session.getAttribute(name);
        return value == null ? "-" : String.valueOf(value);
    }

    /** Request path plus query string, for diagnostic logging. */
    private static String requestPath(HttpServletRequest request) {
        if (request == null) return "(unknown)";
        String uri = request.getRequestURI();
        String qs  = request.getQueryString();
        return qs == null ? uri : uri + "?" + qs;
    }

    /**
     * True if anywhere in the cause chain is a client-side disconnect: a Tomcat
     * ClientAbortException, Spring's AsyncRequestNotUsableException, or an
     * IOException whose message indicates the connection was aborted/reset.
     */
    private static boolean isClientDisconnect(Throwable ex) {
        for (Throwable t = ex; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof ClientAbortException || t instanceof AsyncRequestNotUsableException) {
                return true;
            }
            if (t instanceof IOException && t.getMessage() != null) {
                String m = t.getMessage().toLowerCase();
                if (m.contains("aborted") || m.contains("connection reset")
                        || m.contains("broken pipe") || m.contains("connection was closed")) {
                    return true;
                }
            }
        }
        return false;
    }
}
