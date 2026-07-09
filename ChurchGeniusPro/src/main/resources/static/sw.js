/**
 * sw.js — Service Worker for Church Genius Pro
 *
 * Cache strategy per resource type:
 *  - HTML pages  → Network-first (always fetch fresh; fall back to cache if offline)
 *  - API calls   → Network-only  (never cache)
 *  - JS / CSS    → Network-first with 3s timeout (serve cache on slow/offline,
 *                   but always try network first so deployments land immediately)
 *  - Images/icons → Cache-first (background refresh)
 *
 * Auto-update on deployment
 * ─────────────────────────
 * CACHE_VERSION is bumped on every deploy.  The activate phase deletes all old
 * caches so users always get fresh files after the SW update cycle completes.
 *
 * Additionally, the SW polls /api/app-version every 5 minutes.  When the
 * version string changes it calls registration.update(), which fetches a new
 * sw.js.  The install/activate cycle then runs, skipWaiting fires, and
 * session.js's controllerchange listener reloads all open tabs.
 */

const CACHE_VERSION = 'cgp-v51';   // <- bump on every deployment
const CACHE_NAME    = CACHE_VERSION;

const NETWORK_TIMEOUT_MS      = 3000;
const VERSION_POLL_INTERVAL_MS = 5 * 60 * 1000;  // 5 minutes

const OFFLINE_PAGES = ['/login.html'];

/* ── Install ── */
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(OFFLINE_PAGES).catch(() => {}))
      .then(() => self.skipWaiting())
  );
});

/* ── Activate ──
 * On activation we delete EVERY existing cache bucket (including ones that
 * happen to share our CACHE_NAME). The previous implementation only deleted
 * buckets whose name didn't match CACHE_NAME, which meant that if a buggy
 * file ever landed in the current bucket, the SW would happily keep serving
 * it forever. Wiping unconditionally costs one network round-trip per asset
 * after each deploy — a fair trade for guaranteed freshness.
 */
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
      .then(() => startVersionPolling())
  );
});

/* ── Fetch ── */
self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);

  if (req.method !== 'GET' || url.origin !== self.location.origin) return;

  if (url.pathname.startsWith('/api/')) {
    event.respondWith(
      fetch(req).catch(() => new Response(
        '{"status":"error","message":"Network unavailable"}',
        { status: 503, headers: { 'Content-Type': 'application/json' } }
      ))
    );
    return;
  }

  if (req.headers.get('Accept')?.includes('text/html') ||
      url.pathname.endsWith('.html') || url.pathname === '/') {
    event.respondWith(networkFirstHtml(req));
    return;
  }

  // JS / CSS: ALWAYS fetch directly from the network and DO NOT cache.
  // Caching scripts here previously caused a broken build to persist across
  // deploys (cache returned the bad file even after the server was fixed).
  // The browser still has its own HTTP cache for these so the perf hit is
  // negligible. We DO NOT respondWith here so the browser uses its default
  // network handling (including its own HTTP cache). Returning early without
  // event.respondWith() is the safest pattern for "let the network handle it"
  // — any fetch() error inside respondWith would surface to the page as
  // net::ERR_FAILED, breaking script loading entirely.
  if (url.pathname.endsWith('.js') || url.pathname.endsWith('.css')) {
    return;
  }

  event.respondWith(cacheFirst(req));
});

/* ── Strategy helpers ── */

async function networkFirstHtml(req) {
  const cache = await caches.open(CACHE_NAME);
  try {
    const response = await fetch(req);
    if (response && response.status === 200) cache.put(req, response.clone());
    return response;
  } catch {
    const cached = await cache.match(req);
    return cached || new Response('Offline - please reconnect.', {
      status: 503, headers: { 'Content-Type': 'text/plain' }
    });
  }
}

async function networkFirstWithTimeout(req, timeoutMs) {
  const cache = await caches.open(CACHE_NAME);
  let networkResponse;
  try {
    const timeout = new Promise((_, reject) =>
      setTimeout(() => reject(new Error('timeout')), timeoutMs)
    );
    networkResponse = await Promise.race([fetch(req), timeout]);
    if (networkResponse && networkResponse.status === 200) {
      cache.put(req, networkResponse.clone());
      return networkResponse;
    }
  } catch {
    /* network slow or offline */
  }
  const cached = await cache.match(req);
  if (cached) {
    fetch(req).then((res) => {
      if (res && res.status === 200) cache.put(req, res);
    }).catch(() => {});
    return cached;
  }
  return networkResponse || new Response('', { status: 503 });
}

async function cacheFirst(req) {
  const cache = await caches.open(CACHE_NAME);
  const cached = await cache.match(req);
  if (cached) return cached;
  try {
    const response = await fetch(req);
    if (response && response.status === 200) cache.put(req, response.clone());
    return response;
  } catch {
    return new Response('', { status: 503 });
  }
}

/* ── Version polling ── */

let _knownVersion = null;

function startVersionPolling() {
  fetch('/api/app-version', { cache: 'no-store' })
    .then((r) => r.ok ? r.json() : null)
    .then((data) => { if (data && data.version) _knownVersion = data.version; })
    .catch(() => {});
  setInterval(pollVersion, VERSION_POLL_INTERVAL_MS);
}

async function pollVersion() {
  try {
    const r = await fetch('/api/app-version', { cache: 'no-store' });
    if (!r.ok) return;
    const data = await r.json();
    if (!data || !data.version) return;
    if (_knownVersion === null) { _knownVersion = data.version; return; }
    if (data.version !== _knownVersion) {
      _knownVersion = data.version;
      self.registration.update().catch(() => {});
    }
  } catch { /* offline - retry next interval */ }
}

/* ── Push notifications ── */

self.addEventListener('push', (event) => {
  let data = {};
  try { data = event.data ? event.data.json() : {}; }
  catch (_) { data = { body: event.data ? event.data.text() : 'New notification' }; }

  const title = data.title || 'Church Genius Pro';
  const body  = data.body  || 'You have a new notification.';
  const url   = data.url   || '/';
  const tag   = data.tag   || 'cgp-push';
  const icon  = data.icon  || '/logo.png';
  const badge = data.badge || '/badge.png';

  event.waitUntil(
    self.registration.showNotification(title, {
      body, icon, badge, tag, renotify: true,
      vibrate: [200, 100, 200], data: { url },
      actions: [{ action: 'view', title: 'View' }, { action: 'dismiss', title: 'Dismiss' }],
    }).then(() =>
      fetch('/api/push/badge', { credentials: 'include' })
        .then((r) => r.ok ? r.json() : null)
        .then((json) => {
          if (json && typeof json.count === 'number' && 'setAppBadge' in navigator)
            return navigator.setAppBadge(json.count);
        })
        .catch(() => {})
    )
  );
});

/* ── Notification click ── */

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const action    = event.action;
  const targetUrl = event.notification.data?.url || '/';
  if (action === 'dismiss') return;

  event.waitUntil(
    fetch('/api/push/mark-read', { method: 'POST', credentials: 'include' })
      .then(() => { if ('setAppBadge' in navigator) navigator.clearAppBadge().catch(() => {}); })
      .catch(() => {})
      .then(() =>
        clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windowClients) => {
          for (const client of windowClients) {
            if ('focus' in client) { client.focus(); if (client.navigate) client.navigate(targetUrl); return; }
          }
          return clients.openWindow(targetUrl);
        })
      )
  );
});

/* ── Background sync (placeholder) ── */
self.addEventListener('sync', (event) => {
  if (event.tag === 'cgp-sync') { /* placeholder */ }
});

/* ── Skip-waiting message from session.js ── */
self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SKIP_WAITING') self.skipWaiting();
});
