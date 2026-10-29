/**
 * cgp-api.js - the one way a page talks to /api.
 *
 * Error-handling audit 2026-10-28 (M4 / M5 / M6): ~290 fetch sites parsed the
 * body without looking at the status, so a 401/403/500 JSON body was rendered as
 * data ("0 Members", "undefined results", a thrown list.slice), optimistic
 * mutations announced success whatever the server said, and the reference id
 * that every 500 carries never reached the person who would quote it.
 *
 *   CGP_api(url, opts)        -> Promise<data>  (JSON body, text, or null when empty)
 *                               rejects with a CgpApiError {cgp:true, status, message,
 *                               ref, code, body, url} on !res.ok, on a network
 *                               failure (status 0) and on an unreadable body.
 *                               opts.raw === true resolves with the Response itself
 *                               (after the status check) for blobs/downloads.
 *   CGP_apiJson(url, opts, fallback) -> Promise<data|null>: shows the error
 *                               (CGP_showError) and resolves null instead of rejecting;
 *                               opts.quiet === true shows nothing.
 *   CGP_errorMessage(err, fallback) -> the sentence to show for any thrown value.
 *   CGP_showError(err, fallback)    -> shows it with the page's own toast when it
 *                               has one (window.CGP_notify, toast, showToast,
 *                               showMessage) and a small built-in toast otherwise.
 *
 * Rules: credentials same-origin; the body is parsed as JSON only when the
 * server says it is JSON (a proxy's HTML 502 or an empty 406 never becomes a
 * SyntaxError -> "Network error"); message = body.error || body.message ||
 * a sentence for the status; a reference ("ref") is appended so it can be
 * quoted to support. The session.js / memberHome.html fetch interceptors still
 * run underneath (401 -> sign-in page with a reason, plan/subscription 403s),
 * because this calls window.fetch at call time.
 */
(function () {
    'use strict';
    if (window.CGP_api) return;

    var MAX_UPLOAD = '15 MB';

    function CgpApiError(props) {
        var e = new Error(props.message);
        e.name = 'CgpApiError';
        e.cgp = true;
        e.status = props.status;
        e.message = props.message;
        e.ref = props.ref || null;
        e.code = props.code || null;
        e.body = props.body === undefined ? null : props.body;
        e.url = props.url || '';
        return e;
    }

    function sentenceFor(status) {
        if (status === 0)   return 'Network error \u2014 check your connection and try again.';
        if (status === 400) return 'The request was not accepted. Please check the values and try again.';
        if (status === 401) return 'Your session has expired. Please sign in again.';
        if (status === 403) return 'You don\u2019t have permission to do that.';
        if (status === 404) return 'Not found.';
        if (status === 405) return 'That action is not available.';
        if (status === 409) return 'This conflicts with an existing record.';
        if (status === 410) return 'This is no longer available.';
        if (status === 413) return 'File too large (max ' + MAX_UPLOAD + ').';
        if (status === 429) return 'Too many requests. Please wait a moment and try again.';
        if (status === 503) return 'The service is temporarily unavailable. Please try again in a moment.';
        if (status >= 500)  return 'The server could not complete the request. Please try again.';
        return 'The request failed (HTTP ' + status + ').';
    }

    function messageFrom(status, body) {
        var msg = null;
        if (body && typeof body === 'object') {
            if (typeof body.error === 'string' && body.error.trim())        msg = body.error.trim();
            else if (typeof body.message === 'string' && body.message.trim()) msg = body.message.trim();
        }
        if (!msg) msg = sentenceFor(status);
        var ref = body && typeof body === 'object' && typeof body.ref === 'string' ? body.ref : null;
        if (ref && msg.indexOf(ref) === -1) msg += ' (ref ' + ref + ')';
        return msg;
    }

    function isJson(res) {
        var ct = '';
        try { ct = res.headers.get('content-type') || ''; } catch (e) { ct = ''; }
        return /json/i.test(ct);
    }

    function readBody(res) {
        if (res.status === 204 || res.status === 205) return Promise.resolve({ json: false, body: null });
        if (isJson(res)) {
            return res.json().then(function (b) { return { json: true, body: b }; },
                                   function ()  { return { json: false, body: null, unreadable: true }; });
        }
        return res.text().then(function (t) { return { json: false, body: t === '' ? null : t }; },
                               function ()  { return { json: false, body: null, unreadable: true }; });
    }

    function CGP_api(url, opts) {
        opts = opts ? Object.assign({}, opts) : {};
        var raw = opts.raw === true;
        delete opts.raw;
        if (opts.credentials === undefined) opts.credentials = 'same-origin';
        var p;
        try { p = window.fetch(url, opts); } catch (e) { p = Promise.reject(e); }
        return p.then(function (res) {
            if (raw) {
                if (res.ok) return res;
                return readBody(res).then(function (r) {
                    throw CgpApiError({ status: res.status, message: messageFrom(res.status, r.body),
                                        ref: r.body && r.body.ref, code: r.body && r.body.code, body: r.body, url: url });
                });
            }
            return readBody(res).then(function (r) {
                if (res.ok && !r.unreadable) return r.body;
                if (res.ok) {
                    throw CgpApiError({ status: res.status, url: url, body: null,
                                        message: 'The server\u2019s reply could not be read. Please try again.' });
                }
                throw CgpApiError({ status: res.status, message: messageFrom(res.status, r.body),
                                    ref: r.body && r.body.ref, code: r.body && r.body.code, body: r.body, url: url });
            });
        }, function (err) {
            if (err && err.cgp) throw err;
            throw CgpApiError({ status: 0, message: sentenceFor(0), body: null, url: url });
        });
    }

    function CGP_errorMessage(err, fallback) {
        if (err && err.cgp && err.message) return err.message;
        if (fallback) return fallback;
        return sentenceFor(0);
    }

    function CGP_showError(err, fallback) {
        var msg = CGP_errorMessage(err, fallback);
        try {
            if (typeof window.CGP_notify === 'function') { window.CGP_notify(msg, true); return msg; }
            if (typeof window.toast === 'function')      { window.toast(msg, true); return msg; }
            if (typeof window.showToast === 'function')  { window.showToast(msg, 'error'); return msg; }
            if (typeof window.showMessage === 'function'){ window.showMessage(msg, 'error'); return msg; }
        } catch (e) { /* fall through to the built-in toast */ }
        builtInToast(msg);
        return msg;
    }

    /** Small fixed toast for pages that have no message area of their own (no blocking dialog). */
    function builtInToast(msg) {
        try {
            var id = 'cgpApiToast';
            var t = document.getElementById(id);
            if (!t) {
                t = document.createElement('div');
                t.id = id;
                t.setAttribute('role', 'alert');
                t.style.cssText = 'position:fixed;left:50%;bottom:28px;transform:translateX(-50%);z-index:100000;'
                    + 'max-width:min(92vw,560px);background:#b91c1c;color:#fff;padding:10px 16px;border-radius:8px;'
                    + 'font:14px/1.4 system-ui,Segoe UI,Arial,sans-serif;box-shadow:0 6px 20px rgba(0,0,0,.25);display:none;';
                (document.body || document.documentElement).appendChild(t);
            }
            t.textContent = msg;
            t.style.display = 'block';
            clearTimeout(t._cgpTimer);
            t._cgpTimer = setTimeout(function () { t.style.display = 'none'; }, 6000);
        } catch (e) { /* no DOM */ }
    }

    function CGP_apiJson(url, opts, fallback) {
        var quiet = !!(opts && opts.quiet);
        if (opts) { opts = Object.assign({}, opts); delete opts.quiet; }
        return CGP_api(url, opts).then(null, function (err) {
            if (!quiet) CGP_showError(err, fallback);
            return null;
        });
    }

    /**
     * The sentence for a bulk send answer ({sent, failed:[{name|to, reason}]}),
     * e.g. "SMS sent to 3 volunteers; not delivered to 1 (Ann Lee - not opted in)".
     * Returns {message, partial}; partial is true when anyone was not reached.
     */
    function CGP_sendOutcome(d, what, noun) {
        what = what || 'Message';
        noun = noun || 'recipient';
        var sent = d && typeof d.sent === 'number' ? d.sent : null;
        var failed = d && Array.isArray(d.failed) ? d.failed : [];
        var plural = function (n) { return n + ' ' + noun + (n === 1 ? '' : 's'); };
        var msg = sent === null ? (what + ' sent') : (what + ' sent to ' + plural(sent));
        if (failed.length) {
            var names = failed.slice(0, 5).map(function (f) {
                var who = (f && (f.name || f.to)) || '?';
                return f && f.reason ? who + ' \u2014 ' + f.reason : who;
            }).join('; ');
            msg += '; not delivered to ' + failed.length + ' (' + names + (failed.length > 5 ? '; \u2026' : '') + ')';
        } else {
            msg += ' \u2713';
        }
        return { message: msg, partial: failed.length > 0 };
    }

    window.CGP_sendOutcome = CGP_sendOutcome;
    window.CGP_api = CGP_api;
    window.CGP_apiJson = CGP_apiJson;
    window.CGP_errorMessage = CGP_errorMessage;
    window.CGP_showError = CGP_showError;
    window.CGP_API_MAX_UPLOAD = MAX_UPLOAD;
})();
