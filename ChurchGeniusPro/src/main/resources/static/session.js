/**
 * session.js — Shared session management for all authenticated pages.
 *
 * Responsibilities
 * ─────────────────
 * 1. On page load: verify the server-side session via GET /api/session.
 *    If the session is invalid (HTTP 401 or authenticated=false), redirect
 *    immediately to the login page.
 *
 * 2. Refresh localStorage with the authoritative server-side values so that
 *    the rest of the page's JavaScript (which reads localStorage) always has
 *    up-to-date data.
 *
 * 3. Update the sidebar user block (#sidebarUserName / #sidebarUserRole):
 *    - church = true  → name = church_name from session, role = "Church"
 *    - church = false → name = first_name + last_name from session,
 *                       role = role value from session
 *
 * 4. 30-minute client-side idle timer (belt-and-suspenders alongside the
 *    server-side timeout). Any user interaction resets the timer. When it
 *    fires, the session is invalidated server-side and the user is sent to
 *    the login page.
 *
 * 5. Wire every element with class "tb-logout" so that clicking it calls
 *    POST /api/logout and redirects to /login instead of navigating directly.
 *
 * Include this script at the bottom of every authenticated page (just before
 * </body>):
 *
 *   <script src="/session.js"></script>
 */

/* ── Immediate brand-name init (synchronous, before async session fetch) ──────
 * session.js is placed at the bottom of <body>, so the DOM is already built
 * when this runs.  Reading churchName from localStorage is instant and ensures
 * the sidebar brand is correct on every authenticated page without a flash of
 * the hardcoded "Church Genius" fallback.
 * The async updateSidebarUser() call later in init() will overwrite this with
 * the authoritative session value (same data — localStorage is synced on login).
 */
(function () {
    var cn = localStorage.getItem('churchName');
    if (cn) {
        var el = document.querySelector('.brand-name');
        if (el) el.textContent = cn;
    }
})();

(function () {
    'use strict';

    const LOGIN_URL          = '/';
    const SESSION_URL        = '/api/session';
    const LOGOUT_URL         = '/api/logout';
    const IDLE_TIMEOUT_MS    = 30 * 60 * 1000;   // 30 minutes
    const IDLE_WARN_MS       = 29 * 60 * 1000;   // warn 1 minute before expiry
    const SW_URL             = '/sw.js';

    /** Persists a session value to localStorage, skipping null/empty values. */
    function setLS(key, val) {
        // Always store booleans (even false); skip only undefined/null/empty-string
        if (typeof val === 'boolean') {
            localStorage.setItem(key, String(val));
        } else if (val !== undefined && val !== null && val !== '') {
            localStorage.setItem(key, val);
        }
    }

    // ── Service Worker + Push Notifications ──────────────────────────────────

    /**
     * Registers /sw.js and, once permission is granted, subscribes to push
     * notifications.  The subscription object is sent to /api/push/subscribe
     * so the server can store it and deliver pushes later.
     *
     * Permission is requested once (stored in localStorage as 'pushAsked').
     * If the user dismisses the browser prompt without choosing, we wait until
     * their next visit before asking again.
     */
    /* ── PWA meta tags + install banner ── */
    (function loadPwaAssets() {
        // Inject apple-touch-icon if not already present
        if (!document.querySelector('link[rel="apple-touch-icon"]')) {
            var icon = document.createElement('link');
            icon.rel = 'apple-touch-icon';
            icon.href = '/logo.png';
            document.head.appendChild(icon);
        }
        // Load PWA install banner script
        var s = document.createElement('script');
        s.src = '/pwa.js';
        s.async = true;
        document.head.appendChild(s);
    })();

    (function setupPushNotifications() {
        if (!('serviceWorker' in navigator) || !('PushManager' in window)) return;

        navigator.serviceWorker.register(SW_URL)
            .then(function (reg) {
                /* Only request permission if we haven't asked before */
                var alreadyAsked = localStorage.getItem('pushAsked');
                var currentPerm  = Notification.permission;

                if (currentPerm === 'granted') {
                    _subscribePush(reg);
                } else if (currentPerm === 'default' && !alreadyAsked) {
                    /* Slight delay so the page settles first */
                    setTimeout(function () {
                        Notification.requestPermission().then(function (perm) {
                            localStorage.setItem('pushAsked', '1');
                            if (perm === 'granted') _subscribePush(reg);
                        });
                    }, 3000);
                }

                /* ── Auto-update on deployment ──────────────────────────────────
                 * When the browser finds a new service worker (new CACHE_VERSION
                 * on the server), we tell it to skip waiting and take control
                 * immediately.  Once it activates, we reload all tabs so users
                 * get the latest HTML/JS/CSS without any manual hard-refresh.
                 *
                 * Flow:
                 *  1. Browser detects sw.js changed → installs new SW in "waiting"
                 *  2. We send SKIP_WAITING → new SW activates immediately
                 *  3. controllerchange fires → we reload the page
                 *  4. New SW serves fresh files from the updated cache
                 * ──────────────────────────────────────────────────────────── */
                function _activateWaiting(worker) {
                    if (worker && worker.state === 'installed') {
                        worker.postMessage({ type: 'SKIP_WAITING' });
                    }
                }

                /* New SW found while page is open */
                reg.addEventListener('updatefound', function () {
                    var newWorker = reg.installing;
                    if (!newWorker) return;
                    newWorker.addEventListener('statechange', function () {
                        if (newWorker.state === 'installed' && navigator.serviceWorker.controller) {
                            /* A new version is ready — activate it now */
                            _activateWaiting(newWorker);
                        }
                    });
                });

                /* SW already waiting when page loaded (e.g. user had tab open during deploy) */
                if (reg.waiting) {
                    _activateWaiting(reg.waiting);
                }
            })
            .catch(function (err) {
                console.warn('[SW] Registration failed:', err);
            });

        /* When the SW controller changes (new SW activated), reload once to
         * serve the latest files.  A flag prevents reload loops. */
        var _swReloading = false;
        navigator.serviceWorker.addEventListener('controllerchange', function () {
            if (_swReloading) return;
            _swReloading = true;
            window.location.reload();
        });

        /**
         * Subscribe this browser to push and send the subscription to the
         * server.  No-ops silently if subscription already exists.
         */
        function _subscribePush(reg) {
            reg.pushManager.getSubscription().then(function (existing) {
                if (existing) return; /* already subscribed */

                /* Retrieve VAPID public key from the server */
                fetch('/api/push/vapid-public-key')
                    .then(function (r) { return r.ok ? r.text() : null; })
                    .then(function (vapidKey) {
                        if (!vapidKey) return;
                        var appKey = _urlBase64ToUint8Array(vapidKey.trim());
                        return reg.pushManager.subscribe({
                            userVisibleOnly:      true,
                            applicationServerKey: appKey
                        });
                    })
                    .then(function (sub) {
                        if (!sub) return;
                        return fetch('/api/push/subscribe', {
                            method:  'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body:    JSON.stringify(sub)
                        });
                    })
                    .catch(function (err) {
                        console.warn('[Push] Subscription failed:', err);
                    });
            });
        }

        /** Convert URL-safe Base64 VAPID key to Uint8Array (required by the API). */
        function _urlBase64ToUint8Array(base64String) {
            var padding = '='.repeat((4 - (base64String.length % 4)) % 4);
            var base64  = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
            var raw     = atob(base64);
            var output  = new Uint8Array(raw.length);
            for (var i = 0; i < raw.length; i++) output[i] = raw.charCodeAt(i);
            return output;
        }
    })();

    // ── Mobile setup — runs immediately so styles apply before paint ──────────

    /**
     * Injects /mobile.css as a <link> element if it hasn't been added yet.
     * This lets every authenticated page get mobile styles without editing
     * each HTML file individually.
     */
    (function injectMobileCss() {
        if (document.querySelector('link[href="/mobile.css"]')) return;
        var link = document.createElement('link');
        link.rel  = 'stylesheet';
        link.href = '/mobile.css';
        document.head.appendChild(link);
    })();

    /**
     * Injects a full-screen backdrop div used on mobile to close the sidebar
     * when the user taps outside it.  Also overrides window.toggleSidebar so
     * that all pages get mobile-aware open/close behaviour regardless of
     * whatever page-level toggleSidebar function was defined inline.
     *
     * Desktop (> 768px): toggles .collapsed on the sidebar (original behaviour).
     * Mobile  (≤ 768px): toggles .mobile-open + shows/hides the backdrop.
     */
    (function setupMobileSidebar() {
        // Inject backdrop node
        var backdrop = document.getElementById('mobileNavBackdrop');
        if (!backdrop) {
            backdrop = document.createElement('div');
            backdrop.id = 'mobileNavBackdrop';
            document.body.appendChild(backdrop);
        }

        function isMobile() { return window.innerWidth <= 768; }

        function getSidebar() { return document.getElementById('sidebar'); }

        function closeMobileSidebar() {
            var sb = getSidebar();
            if (sb) sb.classList.remove('mobile-open');
            backdrop.classList.remove('visible');
            document.body.style.overflow = '';
        }

        function openMobileSidebar() {
            var sb = getSidebar();
            if (sb) sb.classList.add('mobile-open');
            backdrop.classList.add('visible');
            document.body.style.overflow = 'hidden';   // prevent background scroll
        }

        backdrop.addEventListener('click', function (e) {
            e.stopPropagation();
            closeMobileSidebar();
        });

        // Close sidebar on any nav link click (mobile UX: navigate + close)
        document.addEventListener('click', function (e) {
            if (!isMobile()) return;
            var sb = getSidebar();
            if (!sb || !sb.classList.contains('mobile-open')) return;
            // If click was on a nav button or sub-button inside the sidebar, close it
            if (e.target && sb.contains(e.target)) {
                var isNavBtn = e.target.closest('.nav-sub-btn, .nav-item-btn');
                if (isNavBtn && !isNavBtn.querySelector('.nav-arrow')) {
                    // leaf nav item (no sub-menu arrow) — close after short delay
                    setTimeout(closeMobileSidebar, 180);
                }
            }
        });

        // Close sidebar when screen resizes past the mobile breakpoint
        window.addEventListener('resize', function () {
            if (!isMobile()) {
                closeMobileSidebar();
                document.body.style.overflow = '';
            }
        });

        /**
         * Mobile-aware toggleSidebar.  Overrides any inline definition on the page
         * because session.js is loaded after the page's own <script> block.
         */
        window.toggleSidebar = function () {
            if (isMobile()) {
                var sb = getSidebar();
                if (!sb) return;
                if (sb.classList.contains('mobile-open')) {
                    closeMobileSidebar();
                } else {
                    openMobileSidebar();
                }
            } else {
                // Desktop: original collapse behaviour
                var sb = getSidebar();
                if (sb) sb.classList.toggle('collapsed');
            }
        };
    })();

    let idleTimer;
    let warnTimer;

    // ── Logout helper ──────────────────────────────────────────────────────────

    /**
     * Calls POST /api/auth/logout (which invalidates the session AND clears the
     * remember-me cookie from both the DB and the browser), then clears
     * localStorage and redirects to the login page.
     *
     * We set sessionStorage.cgpExplicitLogout = '1' before navigating so that
     * login.html's checkRemember() knows not to auto-login immediately after an
     * intentional logout.  sessionStorage survives same-tab navigation but is
     * cleared when the tab is closed, so a fresh open will behave normally.
     *
     * Safe to call at any time (including when the server session is already
     * gone — the endpoint handles that gracefully).
     */
    function logout() {
        clearTimeout(idleTimer);
        clearTimeout(warnTimer);
        // Signal to login.html that this is an explicit logout (skip auto-login)
        try { sessionStorage.setItem('cgpExplicitLogout', '1'); } catch (_) {}
        // Call the full logout endpoint that also clears the remember-me cookie
        fetch('/api/auth/logout', { method: 'POST' })
            .catch(function () { /* ignore network errors on logout */ })
            .finally(function () {
                localStorage.clear();
                window.location.href = LOGIN_URL;
            });
    }
    window.logout = logout;   // expose globally so inline onclick="logout()" works

    // ── Sidebar user display ──────────────────────────────────────────────────

    /**
     * Populates #sidebarUserName and #sidebarUserRole from session data.
     * Also updates .brand-name with the church name when available.
     *
     * Rules (from session data, which is the authoritative source):
     *   church = true  → name = churchName,          role = "Church"
     *   church = false → name = firstName + lastName, role = role value
     *
     * Falls back to localStorage values in case the element renders before
     * this script has run (e.g. page-specific init functions that execute
     * concurrently).  Both sources contain identical data after localStorage
     * sync, so the fallback is always consistent.
     */
    /* ── Trial notice (Trial-plan accounts registered as regular clients) ──────
       Sample-data trials and demo tenants get the blocking Trial Account
       agreement (demo-trial.js), which shows the same facts; this covers every
       other account on the Trial plan. Shown once per browser session as a card,
       and again on each page in the last 10 days as a slim reminder. */
    function showTrialNotice(trial) {
        try {
            if (!trial || trial.managedTenant || !trial.endDate) return;
            if (document.getElementById('cgpTrialNotice')) return;
            var KEY = 'cgpTrialNoticeShown';
            var seen = false;
            try { seen = sessionStorage.getItem(KEY) === '1'; } catch (_) { }
            var remaining = Number(trial.daysRemaining);
            if (seen && !(remaining <= 10)) return;
            try { sessionStorage.setItem(KEY, '1'); } catch (_) { }

            function pretty(iso) {
                var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || ''));
                if (!m) return String(iso || '');
                var months = ['January','February','March','April','May','June','July',
                              'August','September','October','November','December'];
                return months[parseInt(m[2], 10) - 1] + ' ' + parseInt(m[3], 10) + ', ' + m[1];
            }
            function esc(v) {
                return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
                    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
                });
            }
            var box = document.createElement('div');
            box.id = 'cgpTrialNotice';
            box.setAttribute('role', 'status');
            box.style.cssText = 'position:fixed;right:16px;bottom:16px;z-index:2147483000;max-width:360px;' +
                'background:#fff;border:1px solid #fed7aa;border-left:4px solid #f39c12;border-radius:10px;' +
                'box-shadow:0 10px 30px rgba(0,0,0,.18);padding:14px 16px 14px 16px;font:14px/1.6 system-ui,' +
                '-apple-system,"Segoe UI",Roboto,sans-serif;color:#1f2937;';
            var lines = seen
                ? '<strong>Your trial ends ' + esc(pretty(trial.endDate)) + '</strong> — ' +
                  esc(remaining) + ' day' + (remaining === 1 ? '' : 's') + ' remaining.'
                : (trial.days != null ? '<strong>Your Trial Account is active for ' + esc(trial.days) + ' days.</strong><br>' : '') +
                  (trial.startDate ? 'Trial Start Date: ' + esc(pretty(trial.startDate)) + '<br>' : '') +
                  'Trial End Date: ' + esc(pretty(trial.endDate)) + '<br>' +
                  'Days remaining: ' + esc(remaining);
            box.innerHTML = '<button type="button" aria-label="Dismiss" style="float:right;border:0;background:none;' +
                'font-size:18px;line-height:1;cursor:pointer;color:#9ca3af;margin:-4px -6px 0 8px;">&times;</button>' + lines +
                '<div style="margin-top:8px;"><a href="/subscriptionReq.html" style="color:#673147;font-weight:600;">' +
                'Request a subscription &rarr;</a></div>';
            box.querySelector('button').addEventListener('click', function () { box.remove(); });
            (document.body || document.documentElement).appendChild(box);
        } catch (_) { /* a notice must never break the page */ }
    }

    function updateSidebarUser(sd) {
        // Use the live session value as the authoritative source.
        // Only fall back to localStorage when the session gives no church field.
        var churchFromSession = sd.church;
        var isChurch = (churchFromSession !== undefined && churchFromSession !== null)
            ? (churchFromSession === true || churchFromSession === 'true')
            : (localStorage.getItem('church') === 'true');

        // For church accounts the church name already appears in the top brand
        // section of the sidebar, so the user block below it is redundant — hide it.
        var sidebarUserBlock = document.querySelector('.sidebar-user');
        if (sidebarUserBlock) {
            sidebarUserBlock.style.display = isChurch ? 'none' : '';
        }

        if (!isChurch) {
            var nameEl = document.getElementById('sidebarUserName');
            var roleEl = document.getElementById('sidebarUserRole');

            if (nameEl) {
                var first = (sd.firstName || localStorage.getItem('firstName') || '').trim();
                var last  = (sd.lastName  || localStorage.getItem('lastName')  || '').trim();
                nameEl.textContent = last ? (first + ' ' + last).trim() : first;
            }

            if (roleEl) {
                roleEl.textContent = (sd.role || localStorage.getItem('role') || '').trim();
            }
        }

        // Update the sidebar brand name with the actual church name
        var churchName = (sd.churchName || localStorage.getItem('churchName') || '').trim();
        if (churchName) {
            var brandNameEl = document.querySelector('.brand-name');
            if (brandNameEl) brandNameEl.textContent = churchName;
        }
    }

    // ── Sidebar brand logo ────────────────────────────────────────────────────

    /**
     * Attempts to load the organization logo from /api/logo/image and, if found,
     * replaces the "CG" text in .brand-icon with the uploaded image.
     * Silently no-ops when no logo has been uploaded.
     */
    function loadBrandLogo() {
        fetch('/api/logo/image')
            .then(function (r) {
                if (!r.ok) return null;
                return r.blob();
            })
            .then(function (blob) {
                var brandIcon = document.querySelector('.brand-icon');
                if (!brandIcon) return;
                if (blob) {
                    var url = URL.createObjectURL(blob);
                    brandIcon.style.background = 'transparent';
                    brandIcon.style.padding    = '2px';
                    brandIcon.innerHTML =
                        '<img src="' + url + '" alt="Logo" ' +
                        'style="width:100%;height:100%;object-fit:contain;border-radius:6px;" />';
                }
                // Make brand icon clickable → logo management page
                if (!brandIcon.dataset.logoBound) {
                    brandIcon.dataset.logoBound = 'true';
                    brandIcon.style.cursor = 'pointer';
                    brandIcon.title = 'Add / Edit / Upload Logo';
                    brandIcon.addEventListener('click', function (e) {
                        e.stopPropagation();
                        window.location.href = '/logo';
                    });
                }
            })
            .catch(function () {
                // No logo uploaded — still make CG icon clickable so user can upload
                var brandIcon = document.querySelector('.brand-icon');
                if (brandIcon && !brandIcon.dataset.logoBound) {
                    brandIcon.dataset.logoBound = 'true';
                    brandIcon.style.cursor = 'pointer';
                    brandIcon.title = 'Upload Logo';
                    brandIcon.addEventListener('click', function (e) {
                        e.stopPropagation();
                        window.location.href = '/logo';
                    });
                }
            });
    }

    // ── Role-based nav filter ─────────────────────────────────────────────────

    /**
     * Hides sidebar nav sections and specific sub-items based on the user's role.
     *
     * Section filtering — each top-level .nav-item has a button with
     * onclick="onNavClick('<sectionId>', ...)" — the sectionId is parsed so
     * that no HTML changes are needed across all 25+ pages.
     *
     * Role → allowed sections:
     *   SuperAdmin : admin, admin-settings, accounting, account-settings, general, reminders
     *   Admin      : admin, admin-settings, general, reminders
     *   Accountant : accounting, account-settings, general, admin
     *   User       : general
     *
     * Church users (church = true) are handled separately by applyChurchNavFilter —
     * they see only the "Users" sub-item inside the admin section.
     */
    function applyRoleNavFilter(role) {
        var ALLOWED = {
            'SuperAdmin': ['admin', 'admin-settings', 'accounting', 'account-settings', 'general', 'more', 'reminders', 'help'],
            'Admin':      ['admin', 'admin-settings', 'accounting', 'general', 'more', 'reminders', 'help'],
            'Accountant': ['accounting', 'account-settings', 'general', 'more', 'admin', 'help'],
            'User':       ['general', 'more', 'reminders', 'help'],
            'Member':     ['general', 'more', 'reminders', 'members', 'help'],
            'Limited':    ['general', 'reminders', 'help']
        };

        var allowed = ALLOWED[role];
        if (allowed === undefined) allowed = ['general', 'help'];

        // ── Section-level filtering ───────────────────────────────────────────
        if (allowed) {
            // Each top-level nav section is a .nav-item whose first child button has
            // an onclick handler that passes the section id as its first string argument,
            // e.g. onNavClick('admin', true), toggleNavL('admin-settings'), etc.
            // The regex matches ANY such function call regardless of name.
            document.querySelectorAll('#sidebarNav > .nav-item').forEach(function (navItem) {
                var btn = navItem.querySelector('.nav-item-btn');
                if (!btn) return;
                var onclick   = btn.getAttribute('onclick') || '';
                var match     = onclick.match(/\w+\('([^']+)'/);
                if (!match) return;
                var sectionId = match[1];
                navItem.style.display = allowed.indexOf(sectionId) !== -1 ? '' : 'none';
            });
        }

        // ── Church-only pages: hide their nav items for ALL non-church roles ──
        // Users (/viewusers), Stripe (/stripeIntegration) and WhatsApp
        // (/whatsappIntegration) are owner-only. Church users are handled
        // separately by applyChurchNavFilter (which shows ONLY these).
        // (/temporaryAccess is not a nav item.)
        document.querySelectorAll('.nav-sub-btn').forEach(function (btn) {
            var onclick = btn.getAttribute('onclick') || '';
            if (onclick.indexOf('/viewusers') !== -1 ||
                onclick.indexOf('/stripeIntegration') !== -1 ||
                onclick.indexOf('/whatsappIntegration') !== -1) {
                btn.style.display = 'none';
            }
        });

        // ── Sub-item filtering: Accountant sees ONLY Family (/viewfamily) inside admin ──
        if (role === 'Accountant') {
            var adminSubmenu = document.getElementById('sub-admin');
            if (adminSubmenu) {
                adminSubmenu.querySelectorAll('.nav-sub-btn').forEach(function (btn) {
                    var onclick = btn.getAttribute('onclick') || '';
                    btn.style.display = onclick.indexOf('/viewfamily') !== -1 ? '' : 'none';
                });
            }
        }

        // ── Sub-item filtering: Admin reaches Payroll via Accounting but is not a
        // full accounting user → show ONLY the Payroll item inside Accounting. ──
        if (role === 'Admin') {
            var acctSubmenu = document.getElementById('sub-accounting');
            if (acctSubmenu) {
                acctSubmenu.querySelectorAll('.nav-sub-btn').forEach(function (btn) {
                    var onclick = btn.getAttribute('onclick') || '';
                    btn.style.display = onclick.indexOf('/payroll') !== -1 ? '' : 'none';
                });
            }
        }
    }

    /**
     * Church-user nav filter (church = true in session).
     *
     * Shows ONLY the admin section, and within that section hides every
     * sub-item except the "Users" link (/viewusers).  All other top-level
     * sections (admin-settings, accounting, general, reminders …) are hidden.
     */
    function applyChurchNavFilter() {
        var activePage  = (window.CGP_PAGE && window.CGP_PAGE.activePage) || '';
        var onUsersPage = activePage === 'admin/users';

        // Hide every top-level nav item except the admin section.
        // Extract the sectionId from onNavClick('sectionId', ...) and
        // match it exactly against 'admin' — not as a substring.
        document.querySelectorAll('#sidebarNav > .nav-item').forEach(function (navItem) {
            var btn = navItem.querySelector('.nav-item-btn');
            if (!btn) { navItem.style.display = 'none'; return; }
            var onclick = btn.getAttribute('onclick') || '';
            var match   = onclick.match(/onNavClick\('([^']+)'/);
            // Church accounts use ONLY the Admin section. Accounting is intentionally
            // hidden for church logins.
            var show = match && match[1] === 'admin';
            navItem.style.display = show ? '' : 'none';
        });

        // Within admin: church accounts get Users and the integration sub-items
        // (Stripe / WhatsApp) only. Membership Requests is intentionally hidden for
        // church logins — that page is not available to the Church role.
        document.querySelectorAll('.nav-sub-btn').forEach(function (btn) {
            var onclick    = btn.getAttribute('onclick') || '';
            var isUsers    = onclick.indexOf('/viewusers') !== -1;
            var isStripe   = onclick.indexOf('/stripeIntegration')   !== -1;
            var isWhatsapp = onclick.indexOf('/whatsappIntegration') !== -1;
            // Ticketing and the AI Assistant are available to the Church role (2026-10-01).
            var isTickets  = onclick.indexOf('/tickets') !== -1;
            var isAi       = onclick.indexOf('/ai-assistant') !== -1;
            var show = isUsers || isStripe || isWhatsapp || isTickets || isAi;
            btn.style.display = show ? '' : 'none';
        });
    }

    // ── Role-based nav section ordering ──────────────────────────────────

    /**
     * Reorders visible top-level nav sections to match the role-specific
     * preferred order defined in ORDER below.
     *
     * Role ordering:
     *   Admin      : admin → admin-settings → general → reminders
     *   Accountant : accounting → account-settings → general → admin
     *   SuperAdmin : admin → admin-settings → accounting → account-settings → general → reminders
     *   (other roles: no reordering applied)
     */
    function reorderNav(role) {
        var ORDER = {
            // MY PROFILE → ADMIN → ACCOUNTING → GENERAL → ACTIVITY CORNER → MORE
            'SuperAdmin': ['members', 'admin', 'accounting', 'general', 'activity', 'more'],
            'Admin':      ['members', 'admin', 'accounting', 'general', 'activity', 'more'],
            'Accountant': ['members', 'accounting', 'general', 'admin', 'activity', 'more'],
            'User':       ['members', 'general', 'activity', 'more'],
            'Member':     ['members', 'general', 'activity', 'more'],
            'Limited':    ['general']
        };
        var order = ORDER[role];
        if (!order) return;

        var navEl = document.getElementById('sidebarNav');
        if (!navEl) return;

        var items = Array.prototype.slice.call(navEl.querySelectorAll(':scope > .nav-item'));
        items.sort(function (a, b) {
            var aBtn = a.querySelector('.nav-item-btn');
            var bBtn = b.querySelector('.nav-item-btn');
            var aMatch = aBtn && (aBtn.getAttribute('onclick') || '').match(/\w+\('([^']+)'/);
            var bMatch = bBtn && (bBtn.getAttribute('onclick') || '').match(/\w+\('([^']+)'/);
            var aIdx = aMatch ? order.indexOf(aMatch[1]) : -1;
            var bIdx = bMatch ? order.indexOf(bMatch[1]) : -1;
            if (aIdx === -1) aIdx = order.length;
            if (bIdx === -1) bIdx = order.length;
            return aIdx - bIdx;
        });
        items.forEach(function (item) { navEl.appendChild(item); });
    }

    /**
     * Injects a "Help" nav section at the bottom of the sidebar for all roles.
     * The section contains a "How to use?" link that opens a help page.
     */
    /**
     * Standalone Help submenu toggle — does NOT call the page's renderNav()
     * so the injected #helpNavItem is never wiped when the section is opened/closed.
     */
    window.__toggleHelpMenu = function () {
        var submenu = document.getElementById('help-submenu');
        var arrow   = document.getElementById('help-arrow');
        if (!submenu) return;
        var isOpen = submenu.style.display !== 'none' && submenu.style.display !== '';
        submenu.style.display = isOpen ? 'none' : 'block';
        if (arrow) arrow.style.transform = isOpen ? '' : 'rotate(90deg)';
    };

    function injectHelpNav() {
        // The standalone "Help" section was removed — Help is reachable via the
        // "Help Center" sub-item under the More section, so this no longer injects
        // anything. It also clears any previously-rendered Help section.
        var stale = document.getElementById('helpNavItem');
        if (stale) stale.remove();
    }

    // ── Sidebar user navigation ───────────────────────────────────────────────

    /**
     * Makes the sidebar user block (avatar + name + role) navigate to /home
     * when clicked.  Uses data-nav-bound to prevent duplicate bindings if
     * session.js is somehow included more than once.
     */
    function bindSidebarUserNav() {
        document.querySelectorAll('.sidebar-user').forEach(function (el) {
            if (!el.dataset.navBound) {
                el.dataset.navBound = 'true';
                el.style.cursor = 'pointer';
                el.addEventListener('click', function () {
                    window.location.href = '/home';
                });
            }
        });
    }

    // ── Idle timer ────────────────────────────────────────────────────────────

    function resetIdleTimer() {
        clearTimeout(idleTimer);
        clearTimeout(warnTimer);

        // 1-minute warning before session expires
        warnTimer = setTimeout(function () {
            if (document.visibilityState === 'visible') {
                var stay = window.confirm(
                    'Your session will expire in 1 minute due to inactivity.\n' +
                    'Click OK to stay logged in, or Cancel to log out now.'
                );
                if (stay) {
                    // Touch the session to reset the server-side idle clock
                    fetch(SESSION_URL).catch(function () {});
                    resetIdleTimer();
                } else {
                    logout();
                }
            }
        }, IDLE_WARN_MS);

        idleTimer = setTimeout(function () {
            logout();
        }, IDLE_TIMEOUT_MS);
    }

    var ACTIVITY_EVENTS = ['mousedown', 'mousemove', 'keydown', 'scroll', 'touchstart', 'click'];

    function attachActivityListeners() {
        ACTIVITY_EVENTS.forEach(function (evt) {
            document.addEventListener(evt, resetIdleTimer, { passive: true });
        });
    }

    // ── Logout button wiring ──────────────────────────────────────────────────

    function wireLogoutButtons() {
        document.querySelectorAll('.tb-logout').forEach(function (btn) {
            // Replace any inline onclick with the proper server-side logout
            btn.removeAttribute('onclick');
            btn.addEventListener('click', logout);
        });
    }

    // ── 401 interceptor for subsequent fetch calls ────────────────────────────

    /**
     * Monkey-patches window.fetch so that any 401 response from /api/* causes
     * an automatic logout + redirect.  This catches server-side session
     * expiry that occurs between the initial page-load check and a later
     * API call.
     */
    (function patchFetch() {
        var _origFetch = window.fetch.bind(window);
        window.fetch = function (input, init) {
            return _origFetch(input, init).then(function (response) {
                var url = typeof input === 'string' ? input : (input.url || '');
                if (response.status === 401 && url.indexOf('/api/') !== -1 &&
                        url.indexOf('/api/session') === -1 &&
                        url.indexOf('/api/logout')  === -1) {
                    logout();
                }
                // The server can also end access mid-session: a subscription that
                // lapses, or a demo login a Service Admin blocks. Without this the
                // page just stops filling in, which reads as a broken application
                // rather than an account that has ended.
                if (response.status === 403 && url.indexOf('/api/') !== -1
                        && !isQuiet(init)) {
                    response.clone().json().then(function (body) {
                        if (!body) return;
                        if (body.code === 'SUBSCRIPTION_EXPIRED'
                                || body.code === 'DEMO_ACCESS_ENDED') {
                            showAccessEnded(body.error, body.code);
                        } else if (body.code === 'SUBSCRIPTION_FEATURE_DISABLED') {
                            showFeatureUnavailable(body.error, body.feature);
                        }
                    }).catch(function () { /* not JSON — nothing to say */ });
                }
                return response;
            });
        };
    })();

    /**
     * True when the caller asked not to be told about a plan refusal.
     *
     * <p>Some pages PROBE an endpoint to decide whether to draw something — the
     * events page asks for the Midwest Meet configuration to know whether to add
     * its card. A refusal there is the expected answer, not news, and announcing it
     * on every visit would be noise. Such a call sends {@code X-CGP-Quiet: 1}.
     */
    function isQuiet(init) {
        try {
            var h = init && init.headers;
            if (!h) return false;
            if (typeof h.get === 'function') return !!h.get('X-CGP-Quiet');
            return !!(h['X-CGP-Quiet'] || h['x-cgp-quiet']);
        } catch (e) { return false; }
    }

    /**
     * Explains a plan refusal, once per feature, without taking over the page.
     *
     * <p>The server answers 403 with {@code SUBSCRIPTION_FEATURE_DISABLED} for a
     * feature the church's plan does not include. Nothing read that code, so the
     * dozens of pages behind gated APIs showed an empty table or a generic "failed
     * to load" — the same thing a broken application looks like. Handled here, in
     * the one interceptor every page already loads, rather than in each of them.
     */
    function showFeatureUnavailable(message, feature) {
        var id = 'cgpFeatureNotice';
        var bar = document.getElementById(id);
        if (!bar) {
            bar = document.createElement('div');
            bar.id = id;
            bar.setAttribute('role', 'status');
            bar.dataset.features = '';
            bar.style.cssText = 'position:fixed;left:0;right:0;top:0;z-index:99998;'
                + 'background:#fff7ed;border-bottom:1px solid #fed7aa;color:#7c2d12;'
                + 'padding:12px 46px 12px 16px;font:14px/1.5 -apple-system,Segoe UI,Roboto,sans-serif;'
                + 'box-shadow:0 2px 10px rgba(0,0,0,.06);';
            var close = document.createElement('button');
            close.type = 'button';
            close.setAttribute('aria-label', 'Dismiss');
            close.textContent = '\u00D7';
            close.style.cssText = 'position:absolute;right:10px;top:6px;border:0;background:none;'
                + 'font-size:22px;line-height:1;color:#7c2d12;cursor:pointer;';
            close.addEventListener('click', function () { bar.remove(); });
            bar.appendChild(document.createElement('span'));
            bar.appendChild(close);
            document.body.appendChild(bar);
        }
        // One line per feature, however many of its endpoints the page called.
        var seen = (bar.dataset.features || '').split('|');
        var key  = feature || 'plan';
        if (seen.indexOf(key) !== -1) return;
        bar.dataset.features = (bar.dataset.features ? bar.dataset.features + '|' : '') + key;
        var span = bar.firstChild;
        span.textContent = (span.textContent ? span.textContent + '  ' : '')
            + (message || 'This feature is not included in your church\u2019s subscription plan.');
    }

    /**
     * Explains, once, why the application stopped answering.
     *
     * <p>Deliberately not a redirect to the login page: signing in again would be
     * refused for the same reason, and the person would learn nothing from it.
     */
    function showAccessEnded(message, code) {
        if (document.getElementById('cgpAccessEnded')) return;      // already shown
        var wrap = document.createElement('div');
        wrap.id = 'cgpAccessEnded';
        wrap.setAttribute('role', 'alertdialog');
        wrap.style.cssText = 'position:fixed;inset:0;z-index:99999;background:rgba(17,17,17,.55);'
            + 'display:flex;align-items:center;justify-content:center;padding:20px;'
            + 'font-family:-apple-system,Segoe UI,Roboto,sans-serif;';
        var card = document.createElement('div');
        card.style.cssText = 'background:#fff;border-radius:14px;padding:32px;max-width:460px;'
            + 'width:100%;text-align:center;box-shadow:0 10px 40px rgba(0,0,0,.25);';
        var icon = document.createElement('div');
        icon.textContent = code === 'DEMO_ACCESS_ENDED' ? '\u23F3' : '\uD83D\uDD12';
        icon.style.cssText = 'font-size:40px;margin-bottom:10px;';
        var title = document.createElement('h2');
        title.textContent = code === 'DEMO_ACCESS_ENDED' ? 'Your access has ended'
                                                         : 'This subscription has ended';
        title.style.cssText = 'color:#673147;margin:0 0 10px;font-size:20px;';
        var text = document.createElement('p');
        text.textContent = message || 'Please contact your administrator.';
        text.style.cssText = 'color:#555;font-size:14px;line-height:1.7;margin:0 0 20px;';
        var out = document.createElement('button');
        out.type = 'button';
        out.textContent = 'Sign out';
        out.style.cssText = 'background:#673147;color:#fff;border:0;padding:10px 26px;'
            + 'border-radius:8px;font-weight:600;font-size:14px;cursor:pointer;';
        out.addEventListener('click', function () { logout(); });
        card.appendChild(icon); card.appendChild(title); card.appendChild(text); card.appendChild(out);
        if (code !== 'DEMO_ACCESS_ENDED') {
            // An ended trial or subscription can still ask for a plan (the page checks
            // that this sign-in is the church's owner or an admin).
            var req = document.createElement('a');
            req.href = '/subscriptionReq.html';
            req.textContent = 'Request a subscription';
            req.style.cssText = 'display:block;margin-top:14px;color:#673147;font-weight:600;font-size:14px;';
            card.appendChild(req);
        }
        wrap.appendChild(card);
        document.body.appendChild(wrap);
    }

    // ── Topbar dropdown buttons ───────────────────────────────────────────────

    /**
     * Sets up the ADMIN SETTINGS and ACCOUNT SETTINGS topbar dropdown buttons.
     *
     * ADMIN SETTINGS  — visible for Admin and SuperAdmin only.
     *                   Drops down: Member Type, Meeting Type, Promise Verse,
     *                   Email Settings, Logo.
     *
     * ACCOUNT SETTINGS — visible for Accountant and SuperAdmin only.
     *                    Injected dynamically before the Log-out button.
     *                    Drops down: Fund, Purpose, Transaction Type.
     *
     * For all other roles the .btn-admin-settings button is hidden entirely.
     * Church users also get the button hidden.
     */
    function setupTopbarDropdowns(role, isChurch) {
        // Inject dropdown-related CSS once
        if (!document.getElementById('topbar-dd-style')) {
            var style       = document.createElement('style');
            style.id        = 'topbar-dd-style';
            style.textContent =
                '.btn-admin-settings::after { content: " ▾"; font-size:11px; opacity:.8; }' +
                '.topbar-dd-menu a:hover { background:#f5f6fb !important; color:#673147 !important; }';
            document.head.appendChild(style);
        }

        var DROPDOWN_STYLES =
            'display:none;position:absolute;right:0;top:calc(100% + 8px);' +
            'background:#fff;border-radius:10px;' +
            'box-shadow:0 6px 28px rgba(0,0,0,.16);' +
            'min-width:190px;z-index:2000;overflow:hidden;' +
            'border:1px solid #e8eaf0;';

        var ITEM_STYLES =
            'display:block;padding:10px 18px;font-size:13px;color:#333;' +
            'text-decoration:none;white-space:nowrap;cursor:pointer;' +
            'transition:background .15s;';

        /** Build a dropdown <div> from an array of {label, href} objects */
        function buildDropdown(id, items) {
            var menu = document.createElement('div');
            menu.id             = id;
            menu.className      = 'topbar-dd-menu';
            menu.style.cssText  = DROPDOWN_STYLES;
            items.forEach(function (item) {
                var a           = document.createElement('a');
                a.href          = item.href;
                a.textContent   = item.label;
                a.style.cssText = ITEM_STYLES;
                a.addEventListener('mouseover', function () { a.style.background = '#f5f6fb'; });
                a.addEventListener('mouseout',  function () { a.style.background = ''; });
                menu.appendChild(a);
            });
            return menu;
        }

        /** Wrap btn in a relative-positioned container and append the dropdown */
        function wrapWithDropdown(btn, menu) {
            var wrapper           = document.createElement('div');
            wrapper.style.cssText = 'position:relative;display:inline-flex;';
            btn.parentNode.insertBefore(wrapper, btn);
            wrapper.appendChild(btn);
            wrapper.appendChild(menu);

            btn.removeAttribute('onclick');
            btn.addEventListener('click', function (e) {
                e.stopPropagation();
                var isOpen = menu.style.display !== 'none';
                // Close all open topbar dropdowns first
                document.querySelectorAll('.topbar-dd-menu').forEach(function (d) {
                    d.style.display = 'none';
                });
                menu.style.display = isOpen ? 'none' : 'block';
            });
        }

        // Close all dropdowns on outside click
        document.addEventListener('click', function () {
            document.querySelectorAll('.topbar-dd-menu').forEach(function (d) {
                d.style.display = 'none';
            });
        });

        var showAdmin = !isChurch && (role === 'Admin' || role === 'SuperAdmin');
        var showAcct  = !isChurch && (role === 'Accountant' || role === 'SuperAdmin');

        var adminItems = [
            { label: 'Member Type',    href: '/membertype'    },
            { label: 'Meeting Type',   href: '/meetingtype'   },
            { label: 'Promise Verse',  href: '/promiseVerse'  },
            { label: 'Email Settings', href: '/emailSettings' },
            { label: 'Logo',           href: '/logo'          }
        ];

        var acctItems = [
            { label: 'Fund',             href: '/fund'            },
            { label: 'Purpose',          href: '/purpose'         },
            { label: 'Transaction Type', href: '/transactiontype' }
        ];

        // ── ADMIN SETTINGS button ──────────────────────────────────────────
        document.querySelectorAll('.btn-admin-settings').forEach(function (btn) {
            if (!showAdmin) {
                btn.style.display = 'none';
                return;
            }
            var menu = buildDropdown('adminSettingsDropdown', adminItems);
            wrapWithDropdown(btn, menu);
        });

        // ── ACCOUNT SETTINGS button — inject before each logout button ─────
        if (showAcct) {
            document.querySelectorAll('.topbar-right').forEach(function (topbarRight) {
                // Avoid double-injection
                if (topbarRight.querySelector('.btn-acct-settings')) return;
                var logoutBtn = topbarRight.querySelector('.tb-logout');
                if (!logoutBtn) return;

                var acctBtn           = document.createElement('button');
                acctBtn.className     = 'btn-admin-settings btn-acct-settings';
                acctBtn.textContent   = '💼 ACCOUNT SETTINGS';
                topbarRight.insertBefore(acctBtn, logoutBtn);

                var menu = buildDropdown('acctSettingsDropdown', acctItems);
                wrapWithDropdown(acctBtn, menu);
            });
        }
    }

    // ── Account Switcher ──────────────────────────────────────────────────────

    /**
     * Fetches all accounts in the current user's link-group and, if there are
     * 2+ accounts, attaches a switcher dropdown to the sidebar user block.
     *
     * <p>Works for ALL account types: church, staff, and member.
     */
    async function setupRoleSwitcher(sessionData) {
        // ── Fetch linked accounts (universal endpoint) ────────────────────────
        var linkedRoles;
        try {
            var r = await fetch('/api/auth/linked-accounts');
            if (!r.ok) return;
            linkedRoles = await r.json();
        } catch (e) {
            return;
        }

        if (!linkedRoles || linkedRoles.length < 2) return;

        var isChurch = sessionData.church === true || sessionData.church === 'true';
        var currentClientId  = sessionData.clientId  || '';
        var currentAppUserId = sessionData.appUserId;

        // ── Inject CSS once ───────────────────────────────────────────────────
        if (!document.getElementById('rs-style')) {
            var rsStyle = document.createElement('style');
            rsStyle.id  = 'rs-style';
            rsStyle.textContent = [
                '#rsSwitcherMenu {',
                '  position:fixed; z-index:9999;',
                '  background:#fff; border-radius:12px;',
                '  box-shadow:0 8px 32px rgba(0,0,0,.28);',
                '  border:1px solid #e0e0e0; overflow:hidden;',
                '  min-width:240px; display:none;',
                '}',
                '#rsSwitcherMenu .rs-header {',
                '  padding:11px 16px 9px; font-size:10px; font-weight:700;',
                '  color:#9e9e9e; text-transform:uppercase; letter-spacing:.6px;',
                '  border-bottom:1px solid #f0f0f0; background:#fafafa;',
                '}',
                '#rsSwitcherMenu .rs-item {',
                '  display:flex; align-items:center; gap:12px;',
                '  padding:11px 16px; font-size:13px; color:#333;',
                '  cursor:pointer; transition:background .15s; border:none;',
                '  width:100%; text-align:left; background:transparent;',
                '}',
                '#rsSwitcherMenu .rs-item:hover:not(.rs-current):not(.rs-no-login) { background:#f5f6fb; }',
                '#rsSwitcherMenu .rs-item.rs-current {',
                '  background:#f0f2ff; color:#673147; font-weight:600; cursor:default;',
                '}',
                '#rsSwitcherMenu .rs-item.rs-no-login { opacity:.4; cursor:not-allowed; }',
                '#rsSwitcherMenu .rs-avatar {',
                '  width:30px; height:30px; border-radius:50%; flex-shrink:0;',
                '  background:#e8eaf6; color:#673147;',
                '  display:flex; align-items:center; justify-content:center;',
                '  font-size:13px; font-weight:700;',
                '}',
                '#rsSwitcherMenu .rs-item.rs-current .rs-avatar { background:#c5cae9; }',
                '#rsSwitcherMenu .rs-info { flex:1; min-width:0; }',
                '#rsSwitcherMenu .rs-name { font-size:13px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }',
                '#rsSwitcherMenu .rs-role { font-size:11px; color:#888; margin-top:1px; }',
                '#rsSwitcherMenu .rs-item.rs-current .rs-role { color:#9fa8da; }',
                '#rsSwitcherMenu .rs-tick { font-size:14px; color:#673147; flex-shrink:0; }',
                '#rsSwitcherMenu .rs-switching {',
                '  padding:12px 16px; font-size:12px; color:#888;',
                '  text-align:center; font-style:italic;',
                '}',
                '.sidebar-user[data-role-switcher] { cursor:pointer; }',
                '.sidebar-user[data-role-switcher] .user-chevron {',
                '  transition:transform .22s ease, color .22s ease;',
                '}',
                '.sidebar-user[data-role-switcher].rs-open .user-chevron {',
                '  transform:rotate(180deg); color:#c5cae9;',
                '}'
            ].join('\n');
            document.head.appendChild(rsStyle);
        }

        // ── Build dropdown ────────────────────────────────────────────────────
        var old = document.getElementById('rsSwitcherMenu');
        if (old) old.remove();

        var menu = document.createElement('div');
        menu.id  = 'rsSwitcherMenu';

        // Home link at the top of the switcher
        var homeBtn = document.createElement('button');
        homeBtn.type = 'button';
        homeBtn.className = 'rs-item';
        homeBtn.style.cssText = 'border-bottom:1px solid #f0f0f0;';
        homeBtn.innerHTML = '<div class="rs-avatar" style="background:#e8f5e9;color:#2e7d32;">🏠</div>' +
            '<div class="rs-info"><div class="rs-name">Go to Home</div></div>';
        homeBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            window.location.href = '/home';
        });
        menu.appendChild(homeBtn);

        var hdr = document.createElement('div');
        hdr.className   = 'rs-header';
        hdr.textContent = 'Switch Account';
        menu.appendChild(hdr);

        linkedRoles.forEach(function (lr) {
            // Determine if this entry is the currently active account
            var isCurrent;
            if (lr.type === 'staff') {
                isCurrent = lr.appUserId !== undefined && String(lr.appUserId) === String(currentAppUserId);
            } else {
                // member or church: match by clientId / memberRef
                isCurrent = lr.isCurrent === true ||
                            (lr.memberRef && lr.memberRef === currentClientId);
            }
            // Disabled accounts (lr.enabled === false) are still switchable — they retain
            // their signup record and the switch-account endpoint allows them through.
            // Only accounts with no active login credentials at all are non-switchable.
            var isDisabled = lr.enabled === false && !isCurrent;
            var canSwitch  = !isCurrent && lr.hasActiveLogin;

            var item = document.createElement('button');
            item.type = 'button';
            item.className = 'rs-item' +
                (isCurrent ? ' rs-current' : '') +
                (!lr.hasActiveLogin && !isCurrent ? ' rs-no-login' : '');

            // Avatar initials
            var av = document.createElement('div');
            av.className   = 'rs-avatar';
            av.textContent = ((lr.firstName || '').charAt(0) + (lr.lastName || '').charAt(0)).toUpperCase()
                             || (lr.type === 'member' ? 'M' : lr.type === 'church' ? 'C' : '?');

            // Name + role text
            var info = document.createElement('div');
            info.className = 'rs-info';
            var nm = document.createElement('div');
            nm.className   = 'rs-name';
            nm.textContent = ((lr.firstName || '') + ' ' + (lr.lastName || '')).trim()
                             || (lr.type === 'church' ? 'Church Account' : 'Member Account');
            var rl = document.createElement('div');
            rl.className   = 'rs-role';
            // Show "(Disabled)" hint alongside the role for disabled accounts
            rl.textContent = (lr.role || '') + (isDisabled ? ' · Disabled' : '');
            info.appendChild(nm);
            info.appendChild(rl);

            item.appendChild(av);
            item.appendChild(info);

            // Checkmark for current account
            if (isCurrent) {
                var tick = document.createElement('span');
                tick.className   = 'rs-tick';
                tick.textContent = '✓';
                item.appendChild(tick);
            }

            if (canSwitch) {
                (function (entry) {
                    item.addEventListener('click', function (e) {
                        e.stopPropagation();
                        // Show switching state
                        var msg = document.createElement('div');
                        msg.className   = 'rs-switching';
                        msg.textContent = 'Switching to ' + (entry.role || 'account') + '…';
                        menu.innerHTML  = '';
                        menu.appendChild(msg);
                        switchToAccount(entry);
                    });
                })(lr);
            }

            menu.appendChild(item);
        });

        document.body.appendChild(menu);

        // ── Wire sidebar-user block ───────────────────────────────────────────
        // Re-query after potential DOM updates from applyPermissions/reorderNav
        var sidebarUser = document.querySelector('.sidebar-user');
        if (!sidebarUser) return;

        // Mark it so CSS chevron kicks in
        sidebarUser.setAttribute('data-role-switcher', 'true');

        // Remove the existing onclick attr (set in shell.js) so it doesn't
        // navigate to /home when the user clicks the user block
        sidebarUser.removeAttribute('onclick');

        // Strip any listeners added by bindSidebarUserNav by replacing the node
        var fresh = sidebarUser.cloneNode(true);
        sidebarUser.parentNode.replaceChild(fresh, sidebarUser);
        sidebarUser = fresh;
        sidebarUser.setAttribute('data-role-switcher', 'true');

        function openMenu() {
            var rect = sidebarUser.getBoundingClientRect();
            menu.style.left    = rect.left + 'px';
            menu.style.top     = rect.bottom + 8 + 'px';
            menu.style.width   = rect.width + 'px';
            menu.style.display = 'block';
            sidebarUser.classList.add('rs-open');
        }
        function closeMenu() {
            menu.style.display = 'none';
            sidebarUser.classList.remove('rs-open');
        }

        sidebarUser.addEventListener('click', function (e) {
            e.stopPropagation();
            if (menu.style.display === 'none' || menu.style.display === '') {
                openMenu();
            } else {
                closeMenu();
            }
        });

        // Close on any outside click
        document.addEventListener('click', function () { closeMenu(); });
        // Prevent clicks inside the menu from closing it
        menu.addEventListener('click', function (e) { e.stopPropagation(); });
    }

    /**
     * Universal account switch — works for staff (appUserId), member and church (signupId).
     * Calls POST /api/auth/switch-account, syncs localStorage, then navigates to
     * the appropriate home page for the target account type.
     */
    async function switchToAccount(entry) {
        try {
            var payload;
            if (entry.type === 'staff') {
                payload = { targetAppUserId: entry.appUserId };
            } else {
                payload = { targetSignupId: entry.signupId };
            }

            var r = await fetch('/api/auth/switch-account', {
                method:  'POST',
                headers: { 'Content-Type': 'application/json' },
                body:    JSON.stringify(payload)
            });
            var data = await r.json();
            if (r.ok && data.status === 'success') {
                // Clear stale localStorage keys before reload so nothing lingers
                ['clientId','username','church','churchId','firstName',
                 'lastName','role','churchName','appUserId','memberId',
                 'memberRole','appClientId'].forEach(function(k) {
                    localStorage.removeItem(k);
                });
                setLS('clientId',    data.clientId);
                setLS('username',    data.username);
                setLS('church',      data.church);
                setLS('churchId',    data.churchId);
                setLS('firstName',   data.firstName);
                setLS('lastName',    data.lastName);
                setLS('role',        data.role);
                setLS('churchName',  data.churchName);
                setLS('appClientId', data.appClientId);
                if (data.appUserId !== undefined && data.appUserId !== null) {
                    setLS('appUserId', String(data.appUserId));
                }
                if (data.memberId !== undefined && data.memberId !== null) {
                    setLS('memberId',   String(data.memberId));
                    setLS('memberRole', data.memberRole || '');
                }
                // Navigate to the correct home for the target account type
                var redirect = data.redirect || '/home';
                window.location.replace(redirect);
            } else {
                var menu2 = document.getElementById('rsSwitcherMenu');
                if (menu2) menu2.style.display = 'none';
                window.alert('Could not switch account: ' + (data.error || 'Please try again.'));
            }
        } catch (e) {
            var menu3 = document.getElementById('rsSwitcherMenu');
            if (menu3) menu3.style.display = 'none';
            window.alert('Network error. Please try again.');
        }
    }

    // ── Bootstrap ─────────────────────────────────────────────────────────────

    /**
     * Main entry point.  Verifies the session, syncs localStorage, updates
     * the sidebar, then activates the idle timer and logout-button wiring.
     */
    // ═══════════════════════════════════════════════════════════════════════
    // Subscription-plan feature gating (parallel to the permission system).
    // window.CGP_FEATURES holds explicit flags from /api/subscription/features;
    // only an explicit false disables a feature (opt-in denial, like perms).
    // ═══════════════════════════════════════════════════════════════════════
    window.CGP_FEATURES = null;
    window.CGP_hasFeature = function (key) {
        return !(window.CGP_FEATURES && window.CGP_FEATURES[key] === false);
    };

    /** Sidebar href → subscription feature key. */
    var FEATURE_NAV = {
        '/income': 'accounting', '/expense': 'accounting', '/accountingReports': 'accounting',
        '/donation-review': 'accounting', '/fund': 'accounting', '/purpose': 'accounting',
        '/transactiontype': 'accounting', '/income-report': 'accounting', '/expense-report': 'accounting',
        '/transactions-report': 'accounting', '/tax-report': 'accounting', '/financial-report': 'accounting',
        '/bank-import': 'bankImport', '/bankSync': 'bankSync', '/pledges': 'pledges', '/payroll': 'payroll',
        '/attendance': 'attendance', '/event': 'eventRegistration', '/events': 'eventRegistration',
        '/kidsMinistry': 'kidsMinistry',
        '/notifyEmail': 'composeEmail',
        '/reminders': 'reminders', '/eventReminders': 'reminders',
        '/autoReminders': 'reminders', '/oneReminders': 'reminders',
        '/groups': 'groups', '/certificates': 'certificates', '/publicScreens': 'publicScreens',
        '/followups': 'followUps', '/songbook': 'songbook', '/admin/songbook-access': 'songbook',
        '/private-access-settings': 'privatePages', '/ntagAccess': 'ntag',
        '/worshipPlanning': 'worship', '/event-volunteers': 'volunteers', '/volunteers': 'volunteers',
        '/guessIt': 'activityCorner', '/memberHome?tab=guessit': 'activityCorner'
    };

    /**
     * Hides nav items (and [data-feature] elements) whose subscription feature
     * is disabled. Safe to call repeatedly — runs after each nav rebuild.
     */
    function applySubscriptionNav() {
        if (!window.CGP_FEATURES) return;   // not loaded → everything enabled

        // Sub-nav items
        document.querySelectorAll('.nav-sub-btn').forEach(function (btn) {
            var onclick = btn.getAttribute('onclick') || '';
            Object.keys(FEATURE_NAV).forEach(function (href) {
                if (onclick.indexOf("'" + href + "'") !== -1 ||
                    onclick.indexOf('"' + href + '"') !== -1) {
                    if (!window.CGP_hasFeature(FEATURE_NAV[href])) btn.style.display = 'none';
                }
            });
        });

        // Whole Accounting section header when the accounting feature is off
        // AND every accounting-area sub-feature is also off.
        if (!window.CGP_hasFeature('accounting')
                && !window.CGP_hasFeature('bankImport') && !window.CGP_hasFeature('bankSync')
                && !window.CGP_hasFeature('pledges') && !window.CGP_hasFeature('payroll')) {
            document.querySelectorAll('.nav-item-btn').forEach(function (btn) {
                var oc = btn.getAttribute('onclick') || '';
                if (oc.indexOf("'accounting'") !== -1) btn.style.display = 'none';
            });
        }

        // The Ministry hub holds Kids, Worship and Prayer, so it goes only when all
        // of them are off — hiding it with Kids Ministry alone took the other two
        // with it. The cards inside carry their own data-feature.
        if (!window.CGP_hasFeature('kidsMinistry') && !window.CGP_hasFeature('worship')
                && !window.CGP_hasFeature('prayer')) {
            document.querySelectorAll('.nav-sub-btn').forEach(function (btn) {
                var oc = btn.getAttribute('onclick') || '';
                if (oc.indexOf("'/ministry'") !== -1 || oc.indexOf('"/ministry"') !== -1) {
                    btn.style.display = 'none';
                }
            });
        }

        // Whole Activity Corner section when its feature is off. The entire
        // .nav-item is hidden rather than just the header button, so the submenu
        // goes with it — and so any Activity Corner item added later is covered
        // without having to be listed in FEATURE_NAV.
        if (!window.CGP_hasFeature('activityCorner')) {
            document.querySelectorAll('.nav-item-btn').forEach(function (btn) {
                var oc = btn.getAttribute('onclick') || '';
                if (oc.indexOf("'activity'") !== -1) {
                    var section = btn.closest('.nav-item');
                    (section || btn).style.display = 'none';
                }
            });
        }

        // Page-level gates: any element marked data-feature="key" (or a
        // comma-separated list) is hidden when a listed feature is disabled.
        document.querySelectorAll('[data-feature]').forEach(function (el) {
            var keys = (el.getAttribute('data-feature') || '').split(',');
            var denied = keys.some(function (k) { return k && !window.CGP_hasFeature(k.trim()); });
            el.style.display = denied ? 'none' : '';
        });
    }
    window.CGP_applySubscriptionNav = applySubscriptionNav;

    async function init() {
        var sessionData;

        try {
            var res = await fetch(SESSION_URL);
            if (res.status === 401) {
                window.location.replace(LOGIN_URL);
                return;
            }
            sessionData = await res.json();
            if (!sessionData.authenticated) {
                window.location.replace(LOGIN_URL);
                return;
            }
        } catch (err) {
            // Network error — allow the page to continue; AuthFilter will reject
            // subsequent /api/* calls if the session is truly gone.
            console.warn('[session.js] Could not reach /api/session:', err);
            sessionData = {};
        }

        // ── Sync localStorage ──────────────────────────────────────────────────
        setLS('clientId',   sessionData.clientId);
        setLS('username',   sessionData.username);
        setLS('church',     sessionData.church);
        setLS('churchId',   sessionData.churchId);
        setLS('firstName',  sessionData.firstName);
        setLS('lastName',   sessionData.lastName);
        setLS('role',       sessionData.role);
        setLS('churchName', sessionData.churchName);
        setLS('appUserId',  sessionData.appUserId);

        // ── Subscription plan features ────────────────────────────────────────
        // Fetched once per page load; exposes window.CGP_FEATURES (explicit
        // flags — a missing key means enabled) and window.CGP_hasFeature(key).
        // Loaded BEFORE the nav filters/permission pass so pages that re-render
        // on CGP_applyPerms (e.g. events.html) already see the feature map.
        try {
            var featRes = await fetch('/api/subscription/features');
            if (featRes.ok) {
                var featData = await featRes.json();
                window.CGP_SUBSCRIPTION = featData;
                window.CGP_FEATURES     = featData.features || {};
                showTrialNotice(featData.trial);
            }
        } catch (_) { /* fail-open: features stay enabled */ }

        // ── Update sidebar user block ─────────────────────────────────────────
        updateSidebarUser(sessionData);

        // ── Load church logo into sidebar brand area ──────────────────────────
        loadBrandLogo();

        // ── Role-based nav filter ─────────────────────────────────────────────
        // Church users (church = true) get a dedicated filter that shows only
        // the "Users" sub-item.  All other users are filtered by their role.
        var isChurch = sessionData.church === true || sessionData.church === 'true';
        var isTemp   = sessionData.temporary === true;
        var isNtag   = sessionData.ntag === true;
        var role = isChurch ? 'Church' : (sessionData.role || localStorage.getItem('role') || 'User');

        // ── Topbar dropdown buttons ────────────────────────────────────────
        setupTopbarDropdowns(role, isChurch);

        // ── Temporary-access sessions ──────────────────────────────────────
        // temp-session.js builds the sidebar from the EXACT permitted pages.
        // session.js's role/permission nav filtering (and its MutationObserver
        // re-render) would fight that and collapse the nav to a subset — which is
        // why a temp user with several granted pages could end up seeing only one.
        // Stand down completely: leave the temp nav untouched so ALL permitted
        // pages stay visible and clickable.
        //
        // NTAG-login sessions are restricted the same way: ntag-session.js builds
        // the sidebar from the tag's exact Allowed Access Pages, so session.js must
        // not re-render the role/permission menu over it (which would re-expose
        // unselected modules). Stand down for those too.
        if (isTemp || isNtag) return;

        if (isChurch) {
            applyChurchNavFilter();
        } else {
            applyRoleNavFilter(role);
            reorderNav(role);
        }
        if (window.CGP_syncFavUI) window.CGP_syncFavUI();   // re-filter Favorites to visible items

        // ── MutationObserver: re-apply nav filter after any nav rebuild ───────
        // Pages like members.html have their own renderNav() that replaces
        // #sidebarNav innerHTML on each toggle, wiping out the display:none
        // overrides set above.  Watching childList changes re-applies the filter
        // automatically so role-restricted sections always stay hidden.
        //
        // IMPORTANT: the observer is DISCONNECTED before filterFn() runs so that
        // the appendChild calls inside reorderNav() do not re-trigger it (which
        // would create an infinite loop).  It is reconnected immediately after.
        (function attachNavObserver() {
            var navEl = document.getElementById('sidebarNav');
            if (!navEl) return;
            var filterFn = isChurch
                ? function () { applyChurchNavFilter(); applySubscriptionNav(); if (window.CGP_syncFavUI) window.CGP_syncFavUI(); }
                : function () {
                    applyRoleNavFilter(role);
                    reorderNav(role);
                    // Member portal sessions store their permission map under
                    // memberPrivileges rather than privileges, so prefer that when
                    // role === 'Member' so PERM_TREE-gated buttons get hidden too.
                    var permsJson = (role === 'Member' ? sessionData.memberPrivileges : sessionData.privileges) || null;
                    applyPermissions(permsJson, role, isChurch);
                    applySubscriptionNav();   // subscription-plan features on top of perms
                    if (window.CGP_syncFavUI) window.CGP_syncFavUI();   // re-filter Favorites
                  };
            var obs = new MutationObserver(function () {
                obs.disconnect();
                filterFn();
                injectHelpNav();   // re-inject Help after any renderNav() rebuild
                obs.observe(navEl, { childList: true });
            });
            obs.observe(navEl, { childList: true });
        })();

        // ── Sidebar user → navigate home (default) ────────────────────────────
        bindSidebarUserNav();

        // ── Role switcher (overrides click-to-home when linked accounts exist) ─
        await setupRoleSwitcher(sessionData);
        injectHelpNav();

        // ── Activate timers & wiring ──────────────────────────────────────────
        // Check if auto-logout is disabled for this user before starting the idle timer
        var skipIdleTimer = false;
        var appUserId = sessionData.appUserId;
        if (appUserId) {
            try {
                var permRes = await fetch('/api/users/' + appUserId + '/permissions');
                if (permRes.ok) {
                    var permData = await permRes.json();
                    if (permData.permissions) {
                        var permMap = JSON.parse(permData.permissions);
                        // _noAutoLogout defaults to true if not set (new users get no auto-logout by default)
                        skipIdleTimer = permMap.hasOwnProperty('_noAutoLogout')
                            ? permMap['_noAutoLogout'] === true
                            : true;
                    }
                }
            } catch (_) { /* ignore — fall back to starting the timer */ }
        }

        if (!skipIdleTimer) {
            attachActivityListeners();
            resetIdleTimer();
        } else {
            // Keep the server-side session alive with a periodic ping every 20 minutes
            setInterval(function () {
                fetch(SESSION_URL).catch(function () {});
            }, 20 * 60 * 1000);
        }

        wireLogoutButtons();
        if (document.readyState !== 'complete' && document.readyState !== 'interactive') {
            document.addEventListener('DOMContentLoaded', wireLogoutButtons);
        }

        // ── Notification bell (staff pages) ──────────────────────────────────
        if (!isChurch) {
            injectNotificationBell();
            startNotificationPolling();
            startPermissionPolling(sessionData, role, isChurch);
        }

        // ── Permission enforcement ────────────────────────────────────────────
        // Member portal sessions store their permission map under memberPrivileges;
        // staff sessions use privileges. Pick whichever applies to this role so the
        // frontend perm checks (e.g. window.CGP_PERMS['general.events.edit']) work
        // consistently for both account types.
        var permsJson = (role === 'Member' ? sessionData.memberPrivileges : sessionData.privileges) || null;
        applyPermissions(permsJson, role, isChurch);
        applySubscriptionNav();   // subscription-plan features on top of perms
        if (window.CGP_syncFavUI) window.CGP_syncFavUI();   // Favorites reflect final visible nav
    }

    // ── Member nav section helper ─────────────────────────────────────────────

    /**
     * Un-hides the MY PROFILE (members) and Activity Corner nav sections for
     * staff users who have member.* permissions enabled.
     *
     * Called from applyPermissions() in two places:
     *   1. Early-return path (no saved JSON / SuperAdmin) — pass null for full access.
     *   2. Normal path after sub-item enforcement — pass the parsed perms object.
     *
     * @param {Object|null} perms  Parsed permissions map, or null for full access.
     */
    function applyMemberNavSections(perms, role) {
        // Church accounts have an admin-only nav governed entirely by
        // applyChurchNavFilter. Do NOT un-hide MY PROFILE / Activity Corner here,
        // or they'd reappear (null perms = full access) after the church filter ran.
        if (role === 'Church') return;
        // Maps the showMemberTab(tabKey, ...) keys used by shell.js MENU entries
        // (#mbr-family / #mbr-give / etc.) to the permission keys defined in
        // PERM_TREE inside viewusers.html.
        //
        // Two tab keys do NOT match the perm key (legacy naming):
        //   give        → member.contributions   (not member.give)
        //   sundaySchool→ member.classes         (not member.sundayschool)
        // The non-PERM_TREE entries below (member.upcoming / member.worship)
        // are kept for backward compatibility with older member-portal tabs.
        var MEMBER_TAB_PERM = {
            'family':       'member.family',
            'give':         'member.contributions',
            'groups':       'member.groups',
            'sundaySchool': 'member.classes',
            'volunteer':    'member.volunteer',
            'directory':    'member.directory',
            'upcoming':     'member.upcoming',
            'worship':      'member.worship',
        };
        // PERM_TREE in viewusers.html exposes the GuessIt action as
        // 'activity.guessit'. Some legacy code paths also looked at
        // 'member.upcoming' for member-portal users, so both are accepted.
        var ACTIVITY_TAB_PERM = {
            'guessit': 'activity.guessit',
        };

        // Staff roles (non-Member) must have at least one member.* permission
        // explicitly saved as true before MY PROFILE is shown. Member portal
        // users (role === 'Member') always get MY PROFILE — their perm JSON
        // only ever contains member.* keys and controls sub-tab visibility.
        var isMemberRole = (role === 'Member');

        // null = full access (no saved JSON).
        //   - Member portal users: show MY PROFILE (their default).
        //   - Staff users:         show MY PROFILE only if the role ALLOWED list
        //     includes 'members' (i.e. they have never had perms configured yet
        //     and their role grants it by default — handled by applyRoleNavFilter).
        //     We do NOT force-show here; we leave whatever applyRoleNavFilter set.
        var anyMemberEnabled;
        if (!perms) {
            // No saved perms JSON at all — defer to role-based filter result.
            // For Member portal users this always means show; for staff we leave
            // the section in whatever state applyRoleNavFilter left it.
            anyMemberEnabled = isMemberRole ? true : null;   // null = don't touch
        } else {
            // Did the saved JSON include any of the shell-menu-backed keys at all?
            var sawAnyShellKey = Object.keys(MEMBER_TAB_PERM).some(function (tabKey) {
                return MEMBER_TAB_PERM[tabKey] in perms;
            });
            if (sawAnyShellKey) {
                // Keys are present → MY PROFILE is enabled only when at least one is true.
                anyMemberEnabled = Object.keys(MEMBER_TAB_PERM).some(function (tabKey) {
                    return perms[MEMBER_TAB_PERM[tabKey]] === true;
                });
            } else {
                // No shell-menu member.* keys were ever saved in the JSON.
                // For Member portal users this is unusual but still means full access.
                // For staff users this means MY PROFILE was never explicitly granted —
                // default to hidden so unconfigured accounts don't leak the section.
                anyMemberEnabled = isMemberRole ? true : false;
            }
        }

        // ── MY PROFILE (members) section ─────────────────────────────────────
        var membersNavItem = null;
        document.querySelectorAll('#sidebarNav > .nav-item').forEach(function (navItem) {
            var btn = navItem.querySelector('.nav-item-btn');
            if (!btn) return;
            var m = (btn.getAttribute('onclick') || '').match(/\w+\('([^']+)'/);
            if (m && m[1] === 'members') membersNavItem = navItem;
        });

        if (membersNavItem) {
            if (anyMemberEnabled === null) {
                // null = defer to whatever applyRoleNavFilter already set (no-op).
                // This path is taken for staff users with no saved perms JSON,
                // so we don't fight applyRoleNavFilter's role-based decision.
            } else if (anyMemberEnabled) {
                membersNavItem.style.display = '';
                // Gate individual sub-items only when we have a specific perms object
                if (perms) {
                    membersNavItem.querySelectorAll('.nav-sub-btn').forEach(function (btn) {
                        var onclick = btn.getAttribute('onclick') || '';
                        var tabMatch = onclick.match(/showMemberTab\(['"]([^'"]+)['"]/);
                        if (!tabMatch) return;
                        var tabKey  = tabMatch[1];
                        var permKey = MEMBER_TAB_PERM[tabKey];
                        if (permKey && perms[permKey] !== true) btn.style.display = 'none';
                    });
                }
                // Collapse entire section if all sub-items ended up hidden
                var allSubs  = Array.prototype.slice.call(membersNavItem.querySelectorAll('.nav-sub-btn'));
                var anyShown = allSubs.some(function (b) { return b.style.display !== 'none'; });
                if (!anyShown) membersNavItem.style.display = 'none';
            } else {
                // anyMemberEnabled === false: every member.* perm is denied.
                // Explicitly hide regardless of what applyRoleNavFilter set.
                membersNavItem.style.display = 'none';
            }
        }

        // ── Activity Corner section ───────────────────────────────────────────
        var activityNavItem = null;
        document.querySelectorAll('#sidebarNav > .nav-item').forEach(function (navItem) {
            var btn = navItem.querySelector('.nav-item-btn');
            if (!btn) return;
            var m = (btn.getAttribute('onclick') || '').match(/\w+\('([^']+)'/);
            if (m && m[1] === 'activity') activityNavItem = navItem;
        });

        if (activityNavItem) {
            var anyActivityEnabled = !perms ||
                Object.keys(ACTIVITY_TAB_PERM).some(function (tabKey) {
                    return perms[ACTIVITY_TAB_PERM[tabKey]] === true;
                });
            if (anyActivityEnabled) {
                activityNavItem.style.display = '';
                if (perms) {
                    activityNavItem.querySelectorAll('.nav-sub-btn').forEach(function (btn) {
                        var onclick = btn.getAttribute('onclick') || '';
                        var tabMatch = onclick.match(/showMemberTab\(['"]([^'"]+)['"]/);
                        if (!tabMatch) return;
                        var tabKey  = tabMatch[1];
                        var permKey = ACTIVITY_TAB_PERM[tabKey];
                        if (permKey && perms[permKey] !== true) btn.style.display = 'none';
                    });
                }
            } else {
                // No activity tab is enabled — hide the section entirely.
                activityNavItem.style.display = 'none';
            }
        }
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    /* ── Action-permission engine (Phase 2) ───────────────────────────────────
     * Any element tagged with data-perm="<key>" is hidden when that permission is
     * denied for the current user. Multiple comma-separated keys = AND (hidden if
     * ANY is denied). Works for static markup and JS-rendered buttons: a debounced
     * MutationObserver re-runs the gate whenever page content changes, and pages
     * can call window.CGP_gateActions() directly after rendering.
     *
     * Opt-in denial: CGP_PERMS null (full access / no saved perms / church) → never
     * hides anything. A key missing from the saved map → treated as allowed.
     * This pass never force-SHOWS elements (only hides), so it can't override a
     * page hiding a button for other reasons. */
    /* Legacy key names earlier versions of the Permissions screen saved, per current
       key. Mirrors RoleGuard.PERMISSION_ALIASES: an explicit false stored under an
       old name still hides the menu item / button, so nav and page guard agree. */
    window.CGP_PERM_ALIASES = {
        'accounting.reports':      ['reports.income','reports.expense','reports.daterange','reports.taxreport','reports.financial',
                                    'accountingReports','accountingReports.income','accountingReports.expense',
                                    'accountingReports.dateRange','accountingReports.taxReport','accountingReports.financial'],
        'general.reminders':       ['reminders','reminders.event','reminders.auto','reminders.onetime',
                                    'reminders.eventReminders','reminders.autoReminders','reminders.oneTimeReminders'],
        'general.ministry.kids':   ['general.kidsministry','general.sundayschool'],
        'general.ministry.worship':['general.worshipplanning'],
        'general.ministry.prayer': ['general.prayer','general.prayerRequests'],
        'general.emailsettings':   ['general.email.settings'],
        'general.events':          ['general.event'],
        'admin.membership':        ['admin.membershipRequests'],
        'admin.unsubscribed':      ['admin.unsubscribedList'],
        'admin.email':             ['admin.email.delete','admin.groups.email'],
        'accounting.donation':     ['accounting.donationReview'],
        'accounting.settings':     ['accountSettings'],
        'more.certificates':       ['general.certificates'],
        'more.publicscreens':      ['general.publicScreens'],
        'member.classes':          ['member.sundayschool']
    };
    /* true unless the key — or one of its legacy names — is explicitly false. */
    window.CGP_permAllowed = function (p, key) {
        if (!p || !key) return true;
        if (p[key] === false) return false;
        var legacy = window.CGP_PERM_ALIASES[key];
        if (legacy) for (var i = 0; i < legacy.length; i++) { if (p[legacy[i]] === false) return false; }
        return true;
    };
    window.CGP_can = function (key) {
        return window.CGP_permAllowed(window.CGP_PERMS, key);   // null perms = full access; missing key = allowed
    };
    window.CGP_gateActions = function (root) {
        if (!window.CGP_PERMS) return;             // full access → nothing to hide
        var scope = root && root.querySelectorAll ? root : document;
        scope.querySelectorAll('[data-perm]').forEach(function (el) {
            var keys = (el.getAttribute('data-perm') || '').split(',')
                .map(function (s) { return s.trim(); }).filter(Boolean);
            if (!keys.length) return;
            var denied = keys.some(function (k) { return !window.CGP_can(k); });
            if (denied) el.style.setProperty('display', 'none', 'important');
        });
    };
    (function startActionGateObserver() {
        if (window.__cgpActionGateObs) return;
        var t = null;
        var obs = new MutationObserver(function () {
            if (!window.CGP_PERMS) return;
            clearTimeout(t);
            t = setTimeout(function () { window.CGP_gateActions(); }, 60);
        });
        function attach() {
            var target = document.body;
            if (!target) { setTimeout(attach, 50); return; }
            obs.observe(target, { childList: true, subtree: true });
            window.__cgpActionGateObs = obs;
        }
        attach();
    })();

    /**
     * Parses the JSON privileges string from the session, builds a flat
     * window.CGP_PERMS map, hides permission-gated sidebar sub-items, then
     * calls window.CGP_applyPerms() if the page has defined one.
     *
     * Church accounts bypass granular checks (they have a dedicated filter —
     * applyChurchNavFilter). All other roles, including SuperAdmin, are
     * subject to whatever is saved in their permissions JSON. If no perms
     * JSON is saved at all, the user is treated as having full access
     * (opt-in denial — matches historical behavior for new users).
     *
     * @param {string|null} privilegesJson  JSON string from session.privileges
     * @param {string}      role            e.g. 'Admin', 'Accountant', 'SuperAdmin'
     * @param {boolean}     isChurch        true for church accounts
     */
    // viewUsers.html calls this after saving the signed-in user's own permissions so
    // the nav updates at once (the hook was referenced there but never defined).
    window._cgpApplyPermissions = function (privilegesJson, role, isChurch) {
        applyPermissions(privilegesJson, role, isChurch);
        if (typeof applySubscriptionNav === 'function') applySubscriptionNav();
        if (window.CGP_syncFavUI) window.CGP_syncFavUI();
    };

    function applyPermissions(privilegesJson, role, isChurch) {
        // Church accounts bypass granular checks (they have a separate filter —
        // applyChurchNavFilter — which already gates their nav).
        if (isChurch) {
            window.CGP_PERMS = null;   // null = full access
            window.CGP_FAV_ENABLED = true;   // church favorites are governed by the church nav filter
            if (typeof window.CGP_applyPerms === 'function') window.CGP_applyPerms(null);
            applyMemberNavSections(null, 'Church');
            return;
        }

        var perms = null;
        if (privilegesJson) {
            try { perms = JSON.parse(privilegesJson); } catch (e) { perms = null; }
        }
        // No saved permissions JSON → treat as full access for that user (this
        // is the historical default for staff accounts that have never had perms
        // configured, and matches opt-in-denial semantics). SuperAdmin used to
        // unconditionally bypass enforcement here; now that saved perms are
        // honored for SuperAdmin too, we only bypass when there are literally
        // no perms saved.
        if (!perms || typeof perms !== 'object') {
            window.CGP_PERMS = null;
            window.CGP_FAV_ENABLED = true;   // no saved perms → full access (favorites on)
            if (typeof window.CGP_applyPerms === 'function') window.CGP_applyPerms(null);
            // Pass role so staff users without any saved perms defer to the
            // role-based filter rather than unconditionally showing MY PROFILE.
            applyMemberNavSections(null, role);
            hideOptInItemsForMember(role, null);
            return;
        }

        window.CGP_PERMS = perms;

        // Accounting (Income/Expense/Bank Import/Pledges/Reports/Donation/Payroll) is
        // ROLE-gated, not key-gated — only Accountant/Admin/SuperAdmin (or church) may
        // see it. Members/Users have no accounting.* keys, so opt-in denial would leave
        // those items "allowed". Force-hide accounting for non-eligible roles.
        var acctEligible = isChurch || ['SuperAdmin', 'Admin', 'Accountant'].indexOf(role) !== -1;

        // Helper: is a permission key enabled? (missing key → true; legacy alias false → false)
        function perm(key) {
            return window.CGP_permAllowed(perms, key);
        }

        // Favorites feature flag — drives the Favorites side-nav section, the hover
        // "add to favorites" stars, and the toggle action (see shell.js).
        window.CGP_FAV_ENABLED = perm('favorites');

        // Opt-in features (2026-10-01): for a member-portal session these nav items
        // appear only when the saved permission is explicitly true; staff keep the
        // usual "missing = allowed" rule through NAV_PERM below.
        var OPT_IN_NAV = { '/tickets': 'more.ticketing', '/ai-assistant': 'more.aiassistant' };
        function hideOptInItemsForMember(role, perms) {
            if (role !== 'Member') return;
            document.querySelectorAll('.nav-sub-btn').forEach(function (btn) {
                var onclick = btn.getAttribute('onclick') || '';
                Object.keys(OPT_IN_NAV).forEach(function (href) {
                    if (onclick.indexOf("'" + href + "'") !== -1 || onclick.indexOf('"' + href + '"') !== -1) {
                        if (!perms || perms[OPT_IN_NAV[href]] !== true) btn.style.display = 'none';
                    }
                });
            });
        }

        // ── Sidebar sub-item visibility ───────────────────────────────────────
        // Maps nav href patterns → permission key that controls them.
        var NAV_PERM = {
            // Admin
            '/viewfamily':          'admin.family',
            '/groups':              'admin.groups',
            '/membershipRequests':  'admin.membership',
            '/unsubscribed-list':   'admin.unsubscribed',
            '/stripeIntegration':   'admin.stripe',
            '/whatsappIntegration': 'admin.whatsapp',
            // Accounting
            '/income':              'accounting.income',
            '/expense':             'accounting.expense',
            '/bank-import':         'accounting.bankimport',
            '/pledges':             'accounting.pledges',
            '/donation-review':     'accounting.donation',
            '/accountingReports':   'accounting.reports',
            '/payroll':             'accounting.payroll',
            // Report sub-pages are governed by the single 'Report' checkbox
            // (accounting.reports); legacy reports.* keys are honoured via CGP_PERM_ALIASES.
            '/income-report':       'accounting.reports',
            '/expense-report':      'accounting.reports',
            '/transactions-report': 'accounting.reports',
            '/tax-report':          'accounting.reports',
            '/financial-report':    'accounting.reports',
            '/fund':                'accounting.settings',
            '/purpose':             'accounting.settings',
            '/transactiontype':     'accounting.settings',
            // General — /meetings moved from admin.* to general.* per product change.
            '/meetings':            'general.meetings',
            '/attendance':          'general.attendance',
            '/event':               'general.events',
            '/events':              'general.events',
            '/ministry':            'general.ministry',
            '/notifyEmail':         'general.email',
            '/reminders':           'general.reminders',
            // More
            '/certificates':        'more.certificates',
            '/publicScreens':       'more.publicscreens',
            '/followups':           'more.followups',
            '/helpCenter':          'more.helpcenter',
            '/tickets':             'more.ticketing',
            '/ai-assistant':        'more.aiassistant',
            // Reminders — all three sub-pages sit behind the single 'Reminders'
            // checkbox (general.reminders); legacy reminders.* keys via CGP_PERM_ALIASES.
            '/eventReminders':      'general.reminders',
            '/autoReminders':       'general.reminders',
            '/oneReminders':        'general.reminders',
        };

        // Top-level section keys: if all children of a section are hidden, hide the section header too.
        // Only include keys that are actually saved by the permissions modal (i.e. present in PERM_TREE).
        // admin.stripe / admin.whatsapp are nav items but NOT in PERM_TREE, so they are never saved
        // as false — including them here caused allDenied to always be false when admin was unchecked.
        var SECTION_CHILDREN = {
            'admin':      ['admin.family','admin.groups','admin.email',
                           'admin.membership','admin.unsubscribed'],
            'accounting': ['accounting.income','accounting.expense','accounting.bankimport',
                           'accounting.pledges','accounting.donation','accounting.reports',
                           'accounting.payroll','accounting.settings'],
            // 'general.meetings' moved here from admin per product change.
            'general':    ['general.meetings','general.attendance','general.events','general.ministry',
                           'general.email','general.emailsettings','general.reminders'],
            'more':       ['more.certificates','more.publicscreens','more.followups','more.helpcenter',
                           'more.ticketing','more.aiassistant'],
            'reminders':  ['general.reminders']
        };

        // Maps top-level section id → the section-level key saved by the permissions modal.
        // onSectionCbClick() saves _permMap[sec.key] = checked, so when the whole section
        // is unchecked, the section key itself is explicitly false in the saved JSON.
        // Checking this first is faster and more reliable than inspecting all child keys.
        var SECTION_KEY = {
            'admin':      'admin',
            'accounting': 'accounting',
            'general':    'general',
            'more':       'more',
            'reminders':  'reminders',
            'activity':   'activity'
        };

        // Ticketing / AI Assistant are opt-in for member-portal users: shown only when
        // the key is explicitly true (the server applies the same rule, RoleGuard.requireFeature).
        hideOptInItemsForMember(role, perms);

        // Hide individual sub-nav items
        document.querySelectorAll('.nav-sub-btn').forEach(function (btn) {
            var onclick = btn.getAttribute('onclick') || '';
            Object.keys(NAV_PERM).forEach(function (href) {
                if (onclick.indexOf("'" + href + "'") !== -1 ||
                    onclick.indexOf('"' + href  + '"') !== -1) {
                    if (!perm(NAV_PERM[href])) btn.style.display = 'none';
                }
            });
        });

        // Hard role gate: non-accounting roles never see ANY accounting sub-item
        // (Income/Expense/Bank Import/Pledges/Reports/Donation/Payroll), regardless
        // of saved keys — so the Payroll "keep section alive" exception can't apply.
        if (!acctEligible) {
            var ACCT_HREFS = ['/income', '/expense', '/bank-import', '/pledges',
                              '/accountingReports', '/donation-review', '/payroll'];
            document.querySelectorAll('#sidebarNav .nav-sub-btn').forEach(function (btn) {
                var oc = btn.getAttribute('onclick') || '';
                if (ACCT_HREFS.some(function (h) { return oc.indexOf(h) !== -1; })) {
                    btn.style.display = 'none';
                }
            });
        }

        // Show/hide top-level sections based on saved permissions.
        // applyRoleNavFilter() runs before this and hides sections the role
        // doesn't get by default (e.g. 'accounting' is hidden for Admin).
        // This pass runs AFTER and can override that decision in both directions:
        //
        //   SHOW: section-level key is explicitly true in saved JSON
        //         → the admin granted this section to the user via the permissions
        //           modal; override the role-based hide.
        //
        //   HIDE: (a) section-level key is explicitly false, OR
        //         (b) every PERM_TREE child key is false (all individually denied).
        document.querySelectorAll('#sidebarNav > .nav-item').forEach(function (navItem) {
            var btn = navItem.querySelector('.nav-item-btn');
            if (!btn) return;
            var onclick   = btn.getAttribute('onclick') || '';
            var match     = onclick.match(/\w+\('([^']+)'/);
            if (!match) return;
            var sectionId = match[1];
            var sectionKey = SECTION_KEY[sectionId];

            // Hard role gate: the Accounting section is role-restricted. Non-eligible
            // roles (Member/User/Limited) never see it — overriding any saved keys and
            // the Payroll exception below. (Backend RoleGuard already blocks the routes.)
            if (sectionId === 'accounting' && !acctEligible) {
                navItem.style.display = 'none';
                return;
            }

            // Payroll is now governed by the accounting.payroll checkbox like every
            // other Accounting item (the /payroll page routes check the same key), so
            // the former "keep the section alive for Payroll" exception is gone: when
            // accounting.payroll is allowed the section is not all-denied and stays.

            if (sectionKey && perms[sectionKey] === true) {
                // Explicitly granted — show regardless of role-based filter.
                // Also un-hide any sub-items that the role filter may have hidden.
                navItem.style.display = '';
                return;
            }

            // (a) Section-level key explicitly false → hide
            if (sectionKey && perms[sectionKey] === false) {
                navItem.style.display = 'none';
                return;
            }

            // (b) All PERM_TREE children explicitly denied → hide
            var children = SECTION_CHILDREN[sectionId];
            if (!children) return;
            var allDenied = children.every(function (k) { return !perm(k); });
            if (allDenied) navItem.style.display = 'none';
        });

        // ── Account Settings topbar button ────────────────────────────────────
        if (!perm('accountsettings')) {
            document.querySelectorAll('.btn-acct-settings').forEach(function (btn) {
                btn.style.display = 'none';
            });
        }

        // ── Accounting sub-settings (Fund / Purpose / Transaction Type nav) ──
        // accounting.settings controls the Account Settings button in accounting
        // sections as well as the /fund, /purpose, /transactiontype sub-pages.
        // These sub-nav items are handled by NAV_PERM above; this block hides any
        // standalone Account Settings buttons that appear inside accounting pages.
        if (!perm('accounting.settings')) {
            document.querySelectorAll('.btn-acct-settings, [data-perm="accounting.settings"]').forEach(function (btn) {
                btn.style.display = 'none';
            });
        }

        // ── Member Portal sections for staff users ────────────────────────────
        applyMemberNavSections(perms, role);

        // ── Action-level button/icon gating (data-perm) ───────────────────────
        window.CGP_gateActions();

        // ── Notify page callback ──────────────────────────────────────────────────────
        if (typeof window.CGP_applyPerms === 'function') window.CGP_applyPerms(perms);
    }

    // ── Notification bell — staff topbar ─────────────────────────────────────

    /**
     * Injects a 🔔 notification bell button into every .topbar-right before
     * the logout button.  Clicking it opens a small dropdown showing counts.
     * A red badge shows the total unread/upcoming count.
     */
    function injectNotificationBell() {
        document.querySelectorAll('.topbar-right').forEach(function (topbarRight) {
            if (topbarRight.querySelector('.cgp-notif-bell')) return; // already injected

            // ── Inject stylesheet once ────────────────────────────────────────
            if (!document.getElementById('cgp-notif-style')) {
                var style = document.createElement('style');
                style.id  = 'cgp-notif-style';
                style.textContent = [
                    '.cgp-notif-wrap{position:relative;display:inline-flex;align-items:center;}',
                    '.cgp-notif-bell{background:none;border:none;cursor:pointer;font-size:20px;',
                    '  padding:4px 8px;color:#555;border-radius:6px;transition:background .15s;line-height:1;',
                    '  position:relative;display:inline-flex;align-items:center;}',
                    '.cgp-notif-bell:hover{background:rgba(103,49,71,.08);}',
                    '.cgp-notif-count{position:absolute;top:0;right:0;',
                    '  background:#e53935;color:#fff;border-radius:10px;',
                    '  font-size:10px;font-weight:700;padding:1px 5px;',
                    '  min-width:16px;text-align:center;line-height:16px;',
                    '  display:none;pointer-events:none;}',
                    '.cgp-notif-panel{display:none;position:absolute;top:calc(100% + 6px);right:0;',
                    '  background:#fff;border:1px solid #e8e8e8;border-radius:10px;',
                    '  box-shadow:0 4px 20px rgba(0,0,0,.12);min-width:300px;max-width:360px;z-index:9999;',
                    '  overflow:hidden;}',
                    '.cgp-notif-panel.open{display:block;}',
                    '.cgp-notif-hdr{padding:10px 14px 8px;font-size:12px;font-weight:700;',
                    '  color:#673147;border-bottom:1px solid #f0f0f0;letter-spacing:.3px;}',
                    '.cgp-notif-list{max-height:340px;overflow-y:auto;}',
                    '.cgp-notif-row{display:flex;align-items:flex-start;gap:8px;',
                    '  padding:10px 14px;font-size:13px;color:#333;border-bottom:1px solid #f4f4f4;',
                    '  cursor:pointer;transition:background .12s;}',
                    '.cgp-notif-row:last-child{border-bottom:none;}',
                    '.cgp-notif-row:hover{background:#fdf6f9;}',
                    '.cgp-notif-row.unread{background:#fff8fb;}',
                    '.cgp-notif-row .cgp-ni-icon{font-size:18px;flex-shrink:0;margin-top:1px;}',
                    '.cgp-notif-row .cgp-ni-content{flex:1;min-width:0;}',
                    '.cgp-notif-row .cgp-ni-title{font-weight:600;white-space:nowrap;',
                    '  overflow:hidden;text-overflow:ellipsis;color:#222;}',
                    '.cgp-notif-row .cgp-ni-body{font-size:12px;color:#666;margin-top:2px;',
                    '  white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}',
                    '.cgp-notif-row .cgp-ni-time{font-size:11px;color:#aaa;margin-top:3px;}',
                    '.cgp-notif-unread-dot{width:7px;height:7px;border-radius:50%;',
                    '  background:#e53935;flex-shrink:0;margin-top:5px;}',
                    '.cgp-notif-empty{padding:20px 14px;font-size:13px;color:#aaa;text-align:center;}',
                    '.cgp-notif-hdr{display:flex;align-items:center;justify-content:space-between;}',
                    '.cgp-notif-clear{font-size:11px;font-weight:600;color:#888;cursor:pointer;background:none;',
                    '  border:none;padding:0;display:none;}',
                    '.cgp-notif-clear:hover{color:#673147;text-decoration:underline;}',
                    '.cgp-ni-dismiss{background:none;border:none;color:#bbb;font-size:15px;line-height:1;',
                    '  cursor:pointer;padding:0 2px;flex-shrink:0;}',
                    '.cgp-ni-dismiss:hover{color:#e53935;}',
                    '.cgp-ni-open{font-size:11px;font-weight:600;color:#673147;margin-top:3px;}',
                ].join('');
                document.head.appendChild(style);
            }

            var logoutBtn = topbarRight.querySelector('.tb-logout');

            // Wrapper
            var wrap = document.createElement('div');
            wrap.className = 'cgp-notif-wrap';

            // Bell button
            var bell = document.createElement('button');
            bell.className  = 'cgp-notif-bell';
            bell.setAttribute('aria-label', 'Notifications');
            bell.innerHTML  = '&#128276;'; // 🔔

            // Badge
            var badge = document.createElement('span');
            badge.className = 'cgp-notif-count';
            badge.id        = 'cgpNotifCount';
            bell.appendChild(badge);

            // Panel
            var panel = document.createElement('div');
            panel.className = 'cgp-notif-panel';
            panel.id        = 'cgpNotifPanel';
            panel.innerHTML = '<div class="cgp-notif-hdr"><span>Notifications</span>' +
                              '<button type="button" class="cgp-notif-clear" id="cgpNotifClearAll" ' +
                              'title="Clear submission notifications">Clear all</button></div>' +
                              '<div class="cgp-notif-list" id="cgpNotifBody">' +
                              '<div class="cgp-notif-empty">Loading…</div></div>';

            wrap.appendChild(bell);
            wrap.appendChild(panel);

            // ✕ / Clear all stop their own click from reaching the document, so the
            // panel stays open for them; every other click behaves as before.
            var clearAll = panel.querySelector('#cgpNotifClearAll');
            if (clearAll) clearAll.addEventListener('click', function (e) {
                e.stopPropagation();
                fetch('/api/push/dismiss-all', { method: 'POST' })
                    .then(function () { refreshNotifications(); })
                    .catch(function () { /* non-critical */ });
            });

            // Toggle on click — mark all as read when opening
            bell.addEventListener('click', function (e) {
                e.stopPropagation();
                var wasOpen = panel.classList.contains('open');
                panel.classList.toggle('open');
                if (!wasOpen) {
                    // Mark all unread notifications as read and clear badge
                    var badgeEl = document.getElementById('cgpNotifCount');
                    if (badgeEl && badgeEl.style.display !== 'none') {
                        fetch('/api/push/mark-read', { method: 'POST' })
                            .then(function () {
                                if (badgeEl) { badgeEl.style.display = 'none'; }
                                // Mark all rows as read visually
                                document.querySelectorAll('.cgp-notif-row.unread').forEach(function (r) {
                                    r.classList.remove('unread');
                                    var dot = r.querySelector('.cgp-notif-unread-dot');
                                    if (dot) dot.remove();
                                });
                                // Clear the app-icon badge
                                if ('clearAppBadge' in navigator) navigator.clearAppBadge().catch(function(){});
                            })
                            .catch(function () { /* non-critical */ });
                    }
                }
            });
            // Close when clicking outside
            document.addEventListener('click', function () {
                panel.classList.remove('open');
            });

            if (logoutBtn) {
                topbarRight.insertBefore(wrap, logoutBtn);
            } else {
                topbarRight.appendChild(wrap);
            }
        });
    }

    /**
     * Polls /api/push/notifications every 60 seconds and updates
     * the bell badge count + panel content.  Runs immediately on first call.
     */
    /**
     * Every 15 s, re-reads the session's permission map and re-applies the nav and
     * button gates if it changed. The server refreshes the session copy from the
     * database on the same cadence (PermissionRefresher), so a permission removed
     * in viewUsers disappears from this user's menu within about 15 s without a
     * page reload or a new sign-in. Church sessions are exempt from permissions and
     * are not polled.
     */
    function startPermissionPolling(sessionData, role, isChurch) {
        if (isChurch) return;
        var lastPerms = (role === 'Member' ? sessionData.memberPrivileges : sessionData.privileges) || null;
        setInterval(function () {
            fetch(SESSION_URL, { cache: 'no-store' })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (sd) {
                    if (!sd || sd.authenticated === false) return;
                    var now = (role === 'Member' ? sd.memberPrivileges : sd.privileges) || null;
                    if (now === lastPerms) return;
                    lastPerms = now;
                    applyPermissions(now, role, isChurch);
                    if (typeof applySubscriptionNav === 'function') applySubscriptionNav();
                    if (window.CGP_syncFavUI) window.CGP_syncFavUI();
                })
                .catch(function () { /* non-critical */ });
        }, 15 * 1000);
    }

    /** Re-fetches the panel now (used after a dismiss). */
    function refreshNotifications() {
        fetch('/api/push/notifications')
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (data) { if (data) updateNotificationUI(data); })
            .catch(function () { /* silent */ });
    }

    function startNotificationPolling() {
        function poll() {
            fetch('/api/push/notifications')
                .then(function (r) {
                    if (r.status === 401 || r.status === 403 || r.status === 503) return null;
                    return r.ok ? r.json() : null;
                })
                .then(function (data) {
                    if (!data) return;
                    updateNotificationUI(data);
                })
                .catch(function () { /* silent — non-critical */ });
        }

        poll();
        setInterval(poll, 60 * 1000);
    }

    function updateNotificationUI(data) {
        var unread   = data.unreadCount || 0;
        var items    = data.notifications || [];
        var badgeEl  = document.getElementById('cgpNotifCount');
        var body     = document.getElementById('cgpNotifBody');

        // Update badge
        if (badgeEl) {
            badgeEl.textContent   = unread > 99 ? '99+' : String(unread);
            badgeEl.style.display = unread > 0 ? 'block' : 'none';
        }

        if (!body) return;

        // Update app-icon badge (PWA)
        if ('setAppBadge' in navigator) {
            if (unread > 0) {
                navigator.setAppBadge(unread).catch(function(){});
            } else {
                navigator.clearAppBadge().catch(function(){});
            }
        }

        var clearAllBtn = document.getElementById('cgpNotifClearAll');
        if (clearAllBtn) {
            clearAllBtn.style.display = items.some(function (n) { return n.dismissible; }) ? 'inline' : 'none';
        }

        if (items.length === 0) {
            body.innerHTML = '<div class="cgp-notif-empty">You\'re all caught up! ✅</div>';
            return;
        }

        var html = '';
        for (var i = 0; i < items.length; i++) {
            html += buildNotifRow(items[i]);
        }
        body.innerHTML = html;

        // ✕ clears one submission notification (server checks it is the user's own).
        body.querySelectorAll('.cgp-ni-dismiss[data-id]').forEach(function (btn) {
            btn.addEventListener('click', function (e) {
                e.stopPropagation();
                var id  = btn.getAttribute('data-id');
                var row = btn.closest('.cgp-notif-row');
                fetch('/api/push/dismiss/' + encodeURIComponent(id), { method: 'POST' })
                    .then(function () { if (row) row.remove(); refreshNotifications(); })
                    .catch(function () { /* non-critical */ });
            });
        });

        // Attach click handlers to navigate to notification URL
        var rows = body.querySelectorAll('.cgp-notif-row[data-url]');
        rows.forEach(function (row) {
            var url = row.getAttribute('data-url');
            if (url) {
                row.style.cursor = 'pointer';
                row.addEventListener('click', function () {
                    window.location.href = url;
                });
            }
        });
    }

    function cgpEscHtml(v) {
        return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    }

    function buildNotifRow(n) {
        var unreadClass = n.read ? '' : ' unread';
        var dot = n.read ? '' : '<span class="cgp-notif-unread-dot" style="display:inline-block;width:8px;height:8px;border-radius:50%;background:#e74c3c;margin-right:6px;flex-shrink:0;"></span>';
        var icon = '🔔';
        if (n.tag) {
            if      (n.tag.indexOf('meeting')  !== -1) icon = '📅';
            else if (n.tag.indexOf('event')    !== -1) icon = '🎉';
            else if (n.tag.indexOf('birthday') !== -1) icon = '🎂';
            else if (n.tag.indexOf('reminder') !== -1) icon = '⏰';
        }
        if (n.icon) icon = n.icon;
        var timeStr = '';
        if (n.sentAt) {
            try { var d = new Date(n.sentAt); timeStr = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }); } catch (e) { }
        }
        // Only same-site relative links are followed.
        var safeUrl = (n.url && n.url.charAt(0) === '/' && n.url.charAt(1) !== '/') ? n.url : '';
        var dataUrl = safeUrl ? ' data-url="' + cgpEscHtml(safeUrl) + '"' : '';
        var dismiss = n.dismissible
            ? '<button type="button" class="cgp-ni-dismiss" data-id="' + cgpEscHtml(n.id) + '" title="Clear" aria-label="Clear notification">✕</button>'
            : '';
        return '<div class="cgp-notif-row' + unreadClass + '"' + dataUrl + '>' + dot +
               '<span class="cgp-ni-icon">' + icon + '</span>' +
               '<div class="cgp-ni-content">' +
               '<div class="cgp-ni-title">' + cgpEscHtml(n.title) + '</div>' +
               (n.body ? '<div class="cgp-ni-body">' + cgpEscHtml(n.body) + '</div>' : '') +
               (timeStr ? '<div class="cgp-ni-time">' + timeStr + '</div>' : '') +
               (n.dismissible && safeUrl ? '<div class="cgp-ni-open">Open →</div>' : '') +
               '</div>' + dismiss + '</div>';
    }

    init();
})();
