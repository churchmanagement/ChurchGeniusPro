/**
 * shell.js — Shared sidebar / topbar / footer shell for all authenticated pages.
 *
 * Reads window.CGP_PAGE = { activePage, pageTitle, openSection, customTopbar }
 * before doing anything. Must be included BEFORE session.js.
 *
 * Exposes globals:
 *   window.toggleSidebar   — toggled by session.js (mobile-aware override)
 *   window.onNavClick      — used by rendered nav buttons
 *   window.CGP_renderNav   — render / re-render the sidebar nav
 *   window.renderNav       — alias for CGP_renderNav (backward compat)
 *   window.CGP_MENU        — canonical menu definition
 *   window.CGP_ROLE_SECTIONS — role → allowed section IDs
 *   window.postNavigate    — POST-based page navigation (keeps IDs out of URL)
 */

/**
 * Navigates to `path` via a POST form submission so that record IDs are sent
 * in the request body rather than exposed in the browser URL / history.
 *
 * @param {string} path   - destination URL (e.g. '/family')
 * @param {Object} params - key/value pairs to send as hidden form fields
 * @param {string} [target='_self'] - optional link target ('_blank' etc.)
 */
window.postNavigate = function (path, params, target) {
  var form = document.createElement('form');
  form.method = 'POST';
  form.action = path;
  form.target = target || '_self';
  Object.keys(params).forEach(function (key) {
    var inp = document.createElement('input');
    inp.type  = 'hidden';
    inp.name  = key;
    inp.value = params[key];
    form.appendChild(inp);
  });
  document.body.appendChild(form);
  form.submit();
};
/* ── Member name formatting (nickname-aware) ─────────────────────────────
 * When a member has a nickname, names display as "First Last (Nickname)"
 * everywhere — EXCEPT legal documents (tax reports, certificates).
 *   CGP_memberName(first, last, nick)      → plain text  "John Smith (Johnny)"
 *   CGP_memberNameHtml(first, last, nick)  → HTML with the nickname in a
 *                                            smaller, lighter font.
 * Both accept a single member-object argument too:
 *   CGP_memberName(m)  where m has {firstName,lastName,nickname} (or displayName).
 */
window.CGP_memberName = function (first, last, nick) {
  if (first && typeof first === 'object') {
    var m = first;
    if (m.displayName) return String(m.displayName);
    nick = m.nickname; last = m.lastName; first = m.firstName;
  }
  var base = [first, last].filter(function (s) { return s && String(s).trim(); })
                          .map(function (s) { return String(s).trim(); }).join(' ');
  var n = (nick == null) ? '' : String(nick).trim();
  return n ? (base ? base + ' (' + n + ')' : '(' + n + ')') : base;
};
window.CGP_memberNameHtml = function (first, last, nick) {
  var fO = first, lO = last, nO = nick;
  if (first && typeof first === 'object') {
    fO = first.firstName; lO = first.lastName; nO = first.nickname;
  }
  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }
  var base = [fO, lO].filter(function (s) { return s && String(s).trim(); })
                     .map(function (s) { return String(s).trim(); }).join(' ');
  var n = (nO == null) ? '' : String(nO).trim();
  if (!n) return esc(base);
  return esc(base) + ' <span class="cgp-nickname">(' + esc(n) + ')</span>';
};

(function () {
  'use strict';

  // Load the unified button design system on every app-shell page (one place,
  // no per-page edits). Injected immediately so it applies before paint.
  (function injectButtonsCss() {
    if (document.getElementById('cgp-buttons-css')) return;
    var l = document.createElement('link');
    l.rel = 'stylesheet';
    l.href = '/buttons.css';
    l.id = 'cgp-buttons-css';
    (document.head || document.documentElement).appendChild(l);
  })();

  // Styling for the nickname suffix produced by CGP_memberNameHtml.
  (function injectNicknameCss() {
    if (document.getElementById('cgp-nickname-css')) return;
    var s = document.createElement('style');
    s.id = 'cgp-nickname-css';
    s.textContent = '.cgp-nickname{font-size:.85em;color:#6b7280;font-weight:400;}'
      // Favorites permission OFF → hide every add/remove-favorite star anywhere.
      + '.cgp-fav-off .fav-star{display:none !important;}'
      // Inline favorite star for in-page subsections / cards / tabs.
      + '.cgp-fav-able{position:relative;}'
      + '.cgp-inline-fav{position:absolute;top:10px;right:12px;margin:0;flex:none;'
      + 'opacity:.45;color:#b0a3ab;font-size:18px;line-height:1;cursor:pointer;z-index:3;}'
      + '.cgp-inline-fav:hover{opacity:1;color:#f5b301;}'
      + '.cgp-inline-fav.on{opacity:1;color:#f5b301;}';
    (document.head || document.documentElement).appendChild(s);
  })();

  // Per-account sidebar favorites (loaded from /api/favorites after bootstrap).
  window.CGP_FAVORITES = window.CGP_FAVORITES || [];

  /* ── Canonical menu ──────────────────────────────────────────────── */
  // Order: MY PROFILE → ADMIN → ACCOUNTING → GENERAL → ACTIVITY CORNER → MORE
  // The top-level REMINDERS section is kept for active-page detection but is
  // no longer rendered in the sidebar — Reminders are now a sub-item of GENERAL.
  var MENU = [
    { id: 'members', label: 'My Profile', icon: '&#x1F465;', children: [
        { id: 'members/family',    label: 'Family',        icon: '&#x1F46A;', href: '#mbr-family'       },
        { id: 'members/give',      label: 'Contributions', icon: '&#x1F4B0;', href: '#mbr-give'         },
        { id: 'members/groups',    label: 'Groups',        icon: '&#x1F465;', href: '#mbr-groups'       },
        { id: 'members/classes',   label: 'Classes',       icon: '&#x1F4DA;', href: '#mbr-sundaySchool' },
        { id: 'members/volunteer', label: 'Volunteer',     icon: '&#x1F64B;', href: '#mbr-volunteer'    },
        { id: 'members/directory', label: 'Directory',     icon: '&#x1F4D2;', href: '#mbr-directory'    },
        { id: 'members/songbook',  label: 'Song Book',     icon: '&#x1F3B5;', href: '/songbook'         }
    ]},
    { id: 'admin', label: 'Admin', icon: '&#x1F451;', children: [
        { id: 'admin/users',               label: 'Users',               icon: '&#x1F464;', href: '/viewusers'            },
        // Temporary Access is reachable from the Users page (button next to "Add User"),
        // so it is intentionally NOT a side-nav item.
        // Meetings moved to General (general/meetings) per product change.
        { id: 'admin/family',              label: 'Families',            icon: '&#x1F46A;', href: '/viewfamily'           },
        { id: 'admin/groups',              label: 'Groups',              icon: '&#x1F465;', href: '/groups'               },
        { id: 'admin/membership-requests', label: 'Membership Requests', icon: '&#x1F4E8;', href: '/membershipRequests'   },
        { id: 'admin/connect-submissions', label: 'Connect Submissions', icon: '&#x1F91D;', href: '/connectAdmin'         },
        { id: 'admin/unsubscribed-list',   label: 'Unsubscribed List',   icon: '&#x1F6AB;', href: '/unsubscribed-list'    },
        // { id: 'admin/files',            label: 'Files & Notes',       icon: '&#x1F4C1;', href: '/filesUpload'          }, // HIDDEN — feature temporarily disabled; do not remove
        { id: 'admin/stripe-integration',  label: 'Stripe Integration',  icon: '&#x1F4B3;', href: '/stripeIntegration'    },
        { id: 'admin/whatsapp-integration',label: 'WhatsApp Integration',icon: '&#x1F4AC;', href: '/whatsappIntegration'  },
        { id: 'admin/private-access',      label: 'Private Page Access', icon: '&#x1F512;', href: '/private-access-settings' },
        { id: 'admin/ntag-access',         label: 'NTAG Login',          icon: '&#x1F511;', href: '/ntagAccess'          }
    ]},
    { id: 'accounting', label: 'Accounting', icon: '&#x1F4B0;', children: [
        { id: 'accounting/income',          label: 'Income',   icon: '&#x1F4B5;', href: '/income'           },
        { id: 'accounting/expense',         label: 'Expense',  icon: '&#x1F4B3;', href: '/expense'          },
        { id: 'accounting/bank-import',     label: 'Bank Import', icon: '&#x1F3E6;', href: '/bank-import'    },
        { id: 'accounting/bank-sync',       label: 'Bank Sync',   icon: '&#x1F3E6;', href: '/bankSync'         },
        { id: 'accounting/pledges',         label: 'Pledges',  icon: '&#x1F91D;', href: '/pledges'          },
        { id: 'accounting/reports',         label: 'Report',   icon: '&#x1F4CA;', href: '/accountingReports'},
        { id: 'accounting/donation-review', label: 'Donation', icon: '&#x1F381;', href: '/donation-review'  },
        // Payroll is now a sub-section of Accounting; links to the Payroll landing page.
        { id: 'accounting/payroll',         label: 'Payroll',  icon: '&#x1F4B5;', href: '/payroll'          }
    ]},
    { id: 'general', label: 'General', icon: '&#x1F310;', children: [
        // Meetings moved here from Admin per product change.
        { id: 'general/meetings',     label: 'Meetings',     icon: '&#x1F4C5;', href: '/meetings'    },
        { id: 'general/attendance',   label: 'Attendance',   icon: '&#x1F4CA;', href: '/attendance'  },
        { id: 'general/events',       label: 'Events',       icon: '&#x1F389;', href: '/events'      },
        { id: 'general/kids-ministry',label: 'Ministry',     icon: '&#x26EA;',  href: '/ministry'    },
        // { id: 'general/volunteers', label: 'Volunteers', icon: '&#x1F91D;', href: '/volunteers' }, // volunteer management is now inside each Event
        { id: 'general/notify-email', label: 'Compose Email',icon: '&#x2709;&#xFE0F;', href: '/notifyEmail' },
        { id: 'general/reminders',    label: 'Reminders',    icon: '&#x23F0;',  href: '/reminders'   }
    ]},
    { id: 'activity', label: 'Activity Corner', icon: '&#x1F3AE;', children: [
        { id: 'activity/guessit', label: 'Guess It', icon: '&#x1F3AF;', href: '#mbr-guessit' }
    ]},
    { id: 'more', label: 'More', icon: '&#x2022;&#x2022;&#x2022;', children: [
        { id: 'more/certificates',   label: 'Certificates',   icon: '&#x1F3C6;', href: '/certificates'  },
        { id: 'more/public-screens', label: 'Public Screens', icon: '&#x1F4FA;', href: '/publicScreens' },
        { id: 'more/follow-ups',     label: 'Follow-Ups',     icon: '&#x1F4CC;', href: '/followups'     },
        { id: 'more/help-center',    label: 'Help Center',    icon: '&#x1F4DA;', href: '/helpCenter'    },
        { id: 'more/songbook-access',label: 'Song Book Access',icon: '&#x1F3B5;', href: '/admin/songbook-access' }
    ]},
    // REMINDERS section kept for backward-compat active-page detection only.
    // Its items now live under GENERAL → Reminders hub page (/reminders).
    { id: 'reminders', label: 'Reminders', icon: '&#x1F514;', hidden: true, children: [
        { id: 'reminders/event',   label: 'Event Reminders',    icon: '&#x1F4C5;', href: '/eventReminders' },
        { id: 'reminders/auto',    label: 'Periodic Reminders', icon: '&#x1F501;', href: '/autoReminders'  },
        { id: 'reminders/onetime', label: 'One-time Reminders', icon: '&#x23F0;',  href: '/oneReminders'   }
    ]}
  ];

  /* ── Role → allowed sections ─────────────────────────────────────── */
  // NOTE: 'members' and 'activity' are included for all staff roles so that
  // MY PROFILE and Activity Corner remain visible when the user has permission.
  // session.js's applyRoleNavFilter() further refines sub-item visibility based
  // on saved permission flags; these entries just prevent the early-render filter
  // from hiding the sections before session.js has a chance to run.
  // 'reminders' is no longer a top-level rendered section (it's now under GENERAL)
  // but is kept in the allowed list for session.js compatibility.
  var ROLE_SECTIONS = {
    'SuperAdmin': ['admin','accounting','general','more','members','activity'],
    'Church':     ['admin'],
    'Admin':      ['admin','accounting','general','more','members','activity'],
    'Accountant': ['accounting','general','more','admin','members','activity'],
    'User':       ['general','more','members','activity'],
    'Member':     ['general','members','activity','more']
  };

  /* ── Open-section state ──────────────────────────────────────────── */
  var openMenus = new Set();

  /* ── Nav click handler ───────────────────────────────────────────── */
  function onNavClick(sectionId, hasChildren) {
    if (!hasChildren) return;
    var isOpening = !openMenus.has(sectionId);

    // Accordion: close all other open sections before opening a new one
    if (isOpening) {
      openMenus.forEach(function(otherId) {
        if (otherId !== sectionId) {
          openMenus.delete(otherId);
          var otherSub   = document.getElementById('sub-'   + otherId);
          var otherArrow = document.getElementById('arrow-' + otherId);
          if (otherSub)   otherSub.classList.remove('open');
          if (otherArrow) otherArrow.classList.remove('open');
        }
      });
      openMenus.add(sectionId);
    } else {
      openMenus.delete(sectionId);
    }

    var submenu = document.getElementById('sub-' + sectionId);
    var arrow   = document.getElementById('arrow-' + sectionId);
    if (submenu) submenu.classList.toggle('open', openMenus.has(sectionId));
    if (arrow)   arrow.classList.toggle('open',   openMenus.has(sectionId));
  }

  /* ── Sidebar toggle (placeholder; session.js overrides on load) ─── */
  function toggleSidebar() {
    var sb = document.getElementById('sidebar');
    if (sb) sb.classList.toggle('collapsed');
  }

  /* ── Render nav ──────────────────────────────────────────────────── */
  function CGP_renderNav() {
    var navEl = document.getElementById('sidebarNav');
    if (!navEl) return;

    var cfg        = window.CGP_PAGE || {};
    var activePage = cfg.activePage  || '';
    var openSec    = cfg.openSection || '';

    // Always ensure the active page's parent section is open (survives re-renders)
    if (openSec) {
      openMenus.add(openSec);
    }
    // Always open the section that contains the active page
    if (activePage) {
      var parts = activePage.split('/');
      if (parts.length > 1) {
        openMenus.add(parts[0]);
      }
    }

    var html = '';
    MENU.forEach(function (item) {
      if (item.hidden) return;   // skip sections flagged as hidden (e.g. legacy reminders)
      if (item.href) {
        // Leaf item (e.g. Help)
        var isActive = activePage === item.id ? ' active' : '';
        html += '<div class="nav-item">' +
          '<button class="nav-item-btn' + isActive + '" onclick="onNavClick(\'' + item.id + '\', false); window.location.href=\'' + item.href + '\'">' +
            '<span class="nav-icon">' + item.icon + '</span>' +
            '<span class="nav-label">' + item.label + '</span>' +
          '</button>' +
        '</div>';
        return;
      }

      var isOpen      = openMenus.has(item.id);
      var sectionActive = activePage && activePage.indexOf(item.id + '/') === 0 ? ' active' : '';

      html += '<div class="nav-item">' +
        '<button class="nav-item-btn' + sectionActive + '" onclick="onNavClick(\'' + item.id + '\', true)">' +
          '<span class="nav-icon">' + item.icon + '</span>' +
          '<span class="nav-label">' + item.label + '</span>' +
          '<span class="nav-arrow' + (isOpen ? ' open' : '') + '" id="arrow-' + item.id + '">&#x276F;</span>' +
        '</button>' +
        '<div class="nav-submenu' + (isOpen ? ' open' : '') + '" id="sub-' + item.id + '">';

      (item.children || []).forEach(function (child) {
        var childActive = activePage === child.id ? ' active' : '';
        var clickAction;
        if (child.href && child.href.indexOf('#mbr-') === 0) {
          // Member portal tab — call showMemberTab if available (memberHome), else navigate to memberHome with tab param
          var tabKey = child.href.substring(5); // strip '#mbr-'
          clickAction = 'if(window.showMemberTab){window.showMemberTab(\'' + tabKey + '\',this);}else{window.location.href=\'/memberHome?tab=' + tabKey + '\';}';
        } else {
          clickAction = 'window.location.href=\'' + child.href + '\'';
        }
        // Favorites: items with a real route can be starred. Member-portal tabs
        // (#mbr-…) become a /memberHome?tab=… URL so the shortcut works anywhere.
        var favHref = (child.href && child.href.indexOf('#mbr-') === 0)
          ? ('/memberHome?tab=' + child.href.substring(5))
          : (child.href || '');
        var favStar = (favHref && favHref.charAt(0) === '/')
          ? '<span class="fav-star" title="Add to favorites" onclick="event.stopPropagation();event.preventDefault();window.CGP_toggleFavorite(this);">&#x2606;</span>'
          : '';
        html += '<button class="nav-sub-btn' + childActive + '"' +
          ' data-fav-id="' + favAttr(child.id) + '"' +
          ' data-fav-label="' + favAttr(child.label) + '"' +
          ' data-fav-href="' + favAttr(favHref) + '"' +
          ' data-fav-icon="' + favAttr(child.icon || '&#x2022;') + '"' +
          ' onclick="' + clickAction + '">' +
          '<span class="sub-icon">' + (child.icon || '&#x2022;') + '</span>' +
          '<span class="nav-sub-text">' + child.label + '</span>' +
          favStar +
        '</button>';
      });

      html += '</div></div>';
    });

    // ── Synchronous role filter (before session.js async fetch completes) ──
    // Reads localStorage.role so Member users never see sections they lack access to,
    // even during the brief window before session.js's applyRoleNavFilter() runs.
    var ROLE_ALLOWED = {
      'SuperAdmin': ['admin', 'accounting', 'general', 'more', 'members', 'activity'],
      'Admin':      ['admin', 'accounting', 'general', 'more', 'members', 'activity'],
      'Accountant': ['accounting', 'general', 'more', 'admin', 'members', 'activity'],
      'User':       ['general', 'more', 'members', 'activity'],
      'Member':     ['general', 'more', 'members', 'activity'],
      'Limited':    ['general'],
      'Church':     ['admin']
    };
    var localRole   = (localStorage.getItem('role') || '').trim();
    var isChurchRole = (localStorage.getItem('church') === 'true');
    // Unknown / blank / missing role → MOST-RESTRICTIVE default (only General + Help,
    // matching session.js's unknown-role fallback). This guarantees accounting
    // (Income/Expense/Bank Import/Pledges/Reports/Donation/Payroll), admin, and other
    // privileged sections stay hidden until session.js applies the authoritative
    // role/permission filter — never the previous "skip filtering, show everything".
    var allowedSecs = ROLE_ALLOWED[localRole] || ['general', 'help'];
    // Always apply (allowedSecs is never undefined now).
    if (allowedSecs) {
      var tmpDiv = document.createElement('div');
      tmpDiv.innerHTML = html;
      tmpDiv.querySelectorAll('.nav-item').forEach(function (navItem) {
        var btn = navItem.querySelector('.nav-item-btn');
        if (!btn) return;
        var m = (btn.getAttribute('onclick') || '').match(/\w+\('([^']+)'/);
        if (!m) return;
        if (allowedSecs.indexOf(m[1]) === -1) navItem.style.display = 'none';
      });
      // Hide church-owner-only sub-items (Stripe, WhatsApp, NTAG Login) for all
      // non-Church roles immediately. session.js enforces this too, but this prevents
      // a flash. /ntagAccess is gated server-side by RoleGuard.requireChurch.
      if (!isChurchRole) {
        tmpDiv.querySelectorAll('.nav-sub-btn').forEach(function (btn) {
          var oc = btn.getAttribute('onclick') || '';
          if (oc.indexOf('/stripeIntegration')  !== -1 ||
              oc.indexOf('/whatsappIntegration') !== -1 ||
              oc.indexOf('/ntagAccess')          !== -1) {
            btn.style.display = 'none';
          }
        });
      }
      // Admin & Church reach Payroll through Accounting but are not full
      // accounting users — within Accounting show ONLY the Payroll sub-item.
      if (localRole === 'Admin' || isChurchRole) {
        var acctSub = tmpDiv.querySelector('#sub-accounting');
        if (acctSub) {
          acctSub.querySelectorAll('.nav-sub-btn').forEach(function (b) {
            var oc = b.getAttribute('onclick') || '';
            if (oc.indexOf('/payroll') === -1) b.style.display = 'none';
          });
        }
      }
      html = tmpDiv.innerHTML;
    }

    navEl.innerHTML = html;

    // Clicking anything in the main nav (a section header or a non-favorite
    // sub-item) clears the Favorite-navigation intent, so normal sections open
    // as usual. Bound once — navEl persists across re-renders.
    if (!navEl.__favIntentBound) {
      navEl.addEventListener('click', function (e) {
        if (e.target.closest && e.target.closest('#favNavItem')) return;  // favorites handle their own intent
        try { localStorage.removeItem('CGP_NAV_INTENT'); } catch (err) {}
      }, true);
      navEl.__favIntentBound = true;
    }

    // Build the Favorites section (top of nav) + sync star states.
    CGP_syncFavUI();
  }

  /* ── Favorites ───────────────────────────────────────────────────────
   * A per-account shortcut section pinned to the top of the sidebar. The
   * original menu is never modified — favorites are a parallel list that
   * mirrors selected sub-items. Visibility honours the same permission/role
   * filtering as the main nav: only favorites that map to a currently-visible
   * menu sub-item are shown. */
  function favAttr(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/"/g, '&quot;')
      .replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
  function favIsFav(id) {
    var list = window.CGP_FAVORITES || [];
    for (var i = 0; i < list.length; i++) if (list[i] && list[i].pageId === id) return true;
    return false;
  }
  // A favorite is shown only if its source menu item exists and is visible
  // (not hidden by role/permission filtering). Excludes the Favorites section itself.
  function favIsAllowed(pageId) {
    var navEl = document.getElementById('sidebarNav');
    if (!navEl) return false;
    var btn = navEl.querySelector('.nav-item:not(#favNavItem) .nav-sub-btn[data-fav-id="' + pageId + '"]');
    if (btn) {
      // Nav-item favorite: visible only when its source nav item is visible.
      if (btn.style.display === 'none') return false;
      var sec = btn.closest('.nav-item');
      if (sec && sec.style.display === 'none') return false;
      return true;
    }
    // No nav source. Nav-item ids look like "section/item" (no leading slash);
    // a leading-slash pageId is an in-page subsection/route favorite — show it,
    // but honour its stored permission key so inaccessible ones stay hidden.
    if (String(pageId).charAt(0) === '/') {
      var fav = (window.CGP_FAVORITES || []).filter(function (f) { return f && f.pageId === pageId; })[0];
      if (fav && fav.perm && window.CGP_can && !window.CGP_can(fav.perm)) return false;
      return true;
    }
    // A nav-item favorite whose source is currently hidden (role/permission) → hide.
    return false;
  }
  function renderFavoritesSection() {
    var navEl = document.getElementById('sidebarNav');
    if (!navEl) return;

    // Church accounts do not get a Favorites section (owner-only nav is Admin only).
    if (localStorage.getItem('church') === 'true') {
      var favChurch = document.getElementById('favNavItem');
      if (favChurch) favChurch.remove();
      return;
    }

    // Favorites permission OFF → no Favorites section at all.
    if (window.CGP_FAV_ENABLED === false) {
      var favDenied = document.getElementById('favNavItem');
      if (favDenied) favDenied.remove();
      return;
    }

    // Only show Favorites on the standard shell nav (where source items carry
    // data-fav-id). Pages with a custom nav (e.g. member portal) are left alone.
    var hasSource = navEl.querySelector('.nav-item:not(#favNavItem) .nav-sub-btn[data-fav-id]');
    if (!hasSource) {
      var stale = document.getElementById('favNavItem');
      if (stale) stale.remove();
      return;
    }

    var favItem = document.getElementById('favNavItem');
    if (!favItem) {
      favItem = document.createElement('div');
      favItem.className = 'nav-item';
      favItem.id = 'favNavItem';
      // Default collapsed (no 'open'); auto-open logic below decides. The header
      // toggle records a manual override so auto-open won't fight the user.
      favItem.innerHTML =
        '<button class="nav-item-btn" onclick="' +
          'var p=document.getElementById(\'favNavItem\');if(p)p.dataset.userToggled=\'1\';' +
          'var s=document.getElementById(\'sub-fav\');var a=document.getElementById(\'arrow-fav\');' +
          'if(s)s.classList.toggle(\'open\');if(a)a.classList.toggle(\'open\');">' +
          '<span class="nav-icon">&#x2B50;</span>' +
          '<span class="nav-label">Favorites</span>' +
          '<span class="nav-arrow" id="arrow-fav">&#x276F;</span>' +
        '</button>' +
        '<div class="nav-submenu" id="sub-fav"></div>';
    }
    // Always keep it as the first nav item, and visible (role/church nav filters
    // hide unknown sections — Favorites must survive that since it runs after them).
    if (navEl.firstChild !== favItem) navEl.insertBefore(favItem, navEl.firstChild);
    favItem.style.display = '';

    var activePage = (window.CGP_PAGE && window.CGP_PAGE.activePage) || '';
    var curPath    = window.location.pathname;
    var favs = (window.CGP_FAVORITES || []).filter(function (f) { return f && favIsAllowed(f.pageId); });
    var sub  = document.getElementById('sub-fav');
    if (!sub) return;

    // A favorite is "current" when its route matches the page path (covers in-page
    // subsection favorites whose pageId is a route) OR its nav id matches the active
    // page (covers plain nav-item favorites) — the same active logic as the main nav.
    function favIsCurrent(f) {
      return (f.href && f.href === curPath) || (f.pageId && f.pageId === activePage);
    }
    // Favorite-driven navigation: set when the user clicks a Favorite item, so we can
    // keep ONLY Favorites open after navigation/refresh (persisted in localStorage).
    var favMode = false;
    try { favMode = localStorage.getItem('CGP_NAV_INTENT') === 'fav:' + curPath; } catch (e) {}
    favMode = favMode && favs.some(favIsCurrent);

    if (!favs.length) {
      sub.innerHTML = '<div class="nav-fav-empty">No favorites added yet.</div>';
    } else {
      sub.innerHTML = favs.map(function (f) {
        var icon = f.icon || '&#x2022;';
        var active = favIsCurrent(f) ? ' active' : '';
        return '<button class="nav-sub-btn' + active + '"' +
          ' data-fav-id="' + favAttr(f.pageId) + '"' +
          ' data-fav-label="' + favAttr(f.label) + '"' +
          ' data-fav-href="' + favAttr(f.href) + '"' +
          ' data-fav-icon="' + favAttr(icon) + '"' +
          ' onclick="window.CGP_navFavorite(\'' + favAttr(f.href) + '\')">' +
          '<span class="sub-icon">' + icon + '</span>' +
          '<span class="nav-sub-text">' + favAttr(f.label) + '</span>' +
          '<span class="fav-star on" title="Remove from favorites" onclick="event.stopPropagation();event.preventDefault();window.CGP_toggleFavorite(this);">&#x2605;</span>' +
        '</button>';
      }).join('');
    }

    var sm = document.getElementById('sub-fav');
    var ar = document.getElementById('arrow-fav');

    if (favMode) {
      // Arrived via a Favorite → keep Favorites open and collapse every other section.
      favItem.dataset.userToggled = '';
      if (sm) sm.classList.add('open');
      if (ar) ar.classList.add('open');
      navEl.querySelectorAll('.nav-item:not(#favNavItem) .nav-submenu.open')
        .forEach(function (el) { el.classList.remove('open'); });
      navEl.querySelectorAll('.nav-item:not(#favNavItem) .nav-arrow.open')
        .forEach(function (el) { el.classList.remove('open'); });
    } else if (favItem.dataset.userToggled !== '1') {
      // Otherwise mirror the main sections: auto-open when on a favorited page.
      var autoOpen = favs.some(favIsCurrent);
      if (sm) sm.classList.toggle('open', autoOpen);
      if (ar) ar.classList.toggle('open', autoOpen);
    }
  }
  function updateFavStars() {
    var navEl = document.getElementById('sidebarNav');
    if (!navEl) return;
    navEl.querySelectorAll('.nav-item:not(#favNavItem) .nav-sub-btn[data-fav-id]').forEach(function (btn) {
      var st = btn.querySelector('.fav-star');
      if (!st) return;
      var on = favIsFav(btn.getAttribute('data-fav-id'));
      st.classList.toggle('on', on);
      st.innerHTML = on ? '&#x2605;' : '&#x2606;';
      st.title = on ? 'Remove from favorites' : 'Add to favorites';
    });
  }
  function CGP_syncFavUI() {
    // Hide every "add to favorites" star (and any other fav UI) when the
    // Favorites permission is off — a single body class drives the CSS.
    if (document.body) {
      document.body.classList.toggle('cgp-fav-off', window.CGP_FAV_ENABLED === false);
    }
    renderFavoritesSection();
    updateFavStars();
    if (window.CGP_initInlineFavorites) window.CGP_initInlineFavorites();
  }
  window.CGP_syncFavUI = CGP_syncFavUI;

  // ── Generic favorites API ───────────────────────────────────────────────
  // pageId/label/href/icon/perm. Works for any favoritable thing — a nav
  // sub-item OR an in-page subsection/card/tab/route — so new modules need no
  // bespoke code: just expose the descriptor.
  function favApplyList(list) {
    window.CGP_FAVORITES = Array.isArray(list) ? list : (window.CGP_FAVORITES || []);
    CGP_syncFavUI();
  }
  window.CGP_isFavorite = function (pageId) { return favIsFav(pageId); };
  window.CGP_addFavorite = function (fav) {
    if (window.CGP_FAV_ENABLED === false) return;          // permission off → no-op
    if (!fav || !fav.pageId) return;
    fetch('/api/favorites', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        pageId: fav.pageId, label: fav.label, href: fav.href,
        icon: fav.icon, perm: fav.perm || null
      })
    }).then(function (r) { return r.ok ? r.json() : null; }).then(favApplyList).catch(function () {});
  };
  window.CGP_removeFavorite = function (pageId) {
    if (!pageId) return;
    fetch('/api/favorites?pageId=' + encodeURIComponent(pageId), { method: 'DELETE' })
      .then(function (r) { return r.ok ? r.json() : null; }).then(favApplyList).catch(function () {});
  };

  // Navigate to a Favorite. Records the intent so the destination page keeps
  // ONLY the Favorites section open (and highlights the item) after navigation
  // and refresh — consistent with how main sections behave.
  window.CGP_navFavorite = function (href) {
    if (!href) return;
    try {
      var path = href.split('#')[0].split('?')[0];
      localStorage.setItem('CGP_NAV_INTENT', 'fav:' + path);
    } catch (e) {}
    window.location.href = href;
  };

  // Toggle from a sidebar nav star (reads the button's data-fav-* snapshot).
  window.CGP_toggleFavorite = function (starEl) {
    if (window.CGP_FAV_ENABLED === false) return;   // favorites disabled → no-op
    var btn = starEl && starEl.closest ? starEl.closest('.nav-sub-btn') : null;
    if (!btn) return;
    var id = btn.getAttribute('data-fav-id');
    if (!id) return;
    if (favIsFav(id)) {
      window.CGP_removeFavorite(id);
    } else {
      window.CGP_addFavorite({
        pageId: id,
        label:  btn.getAttribute('data-fav-label'),
        href:   btn.getAttribute('data-fav-href'),
        icon:   btn.getAttribute('data-fav-icon')
      });
    }
  };

  // ── Inline favorites (subsections / cards / tabs on any page) ────────────
  // Any element carrying class "cgp-fav-able" + data-fav-id/label/href/icon
  // (and optional data-fav-perm) gets a star toggle injected. Re-runs on every
  // CGP_syncFavUI so state + permission gating stay correct.
  function favReadEl(el) {
    return {
      pageId: el.getAttribute('data-fav-id'),
      label:  el.getAttribute('data-fav-label'),
      href:   el.getAttribute('data-fav-href'),
      icon:   el.getAttribute('data-fav-icon') || '&#x2B50;',
      perm:   el.getAttribute('data-fav-perm') || null
    };
  }
  window.CGP_initInlineFavorites = function (root) {
    var scope = root || document;
    var hosts = scope.querySelectorAll('.cgp-fav-able[data-fav-id]');
    hosts.forEach(function (host) {
      var fav = favReadEl(host);
      // Don't offer to favorite a subsection the user can't access.
      var accessible = !(fav.perm && window.CGP_can && !window.CGP_can(fav.perm));
      var star = host.querySelector('.cgp-inline-fav');
      if (!accessible || window.CGP_FAV_ENABLED === false) {
        if (star) star.style.display = 'none';
        if (!accessible && star) { star.remove(); }
        return;
      }
      if (!star) {
        star = document.createElement('span');
        star.className = 'fav-star cgp-inline-fav';
        star.addEventListener('click', function (e) {
          e.stopPropagation(); e.preventDefault();
          if (window.CGP_FAV_ENABLED === false) return;
          var f = favReadEl(host);
          if (favIsFav(f.pageId)) window.CGP_removeFavorite(f.pageId);
          else window.CGP_addFavorite(f);
        });
        host.appendChild(star);
      }
      star.style.display = '';
      var on = favIsFav(fav.pageId);
      star.classList.toggle('on', on);
      star.innerHTML = on ? '&#x2605;' : '&#x2606;';
      star.title = on ? 'Remove from favorites' : 'Add to favorites';
    });
  };

  function loadFavorites() {
    if (!document.getElementById('sidebarNav')) return;   // not an app-shell page
    if (localStorage.getItem('church') === 'true') return; // church accounts: no Favorites
    if (window.CGP_FAV_ENABLED === false) { CGP_syncFavUI(); return; } // permission off
    fetch('/api/favorites', { headers: { 'Accept': 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (list) { if (list) { window.CGP_FAVORITES = list; CGP_syncFavUI(); } })
      .catch(function () {});
  }

  /* ── Inject sidebar HTML ─────────────────────────────────────────── */
  function injectSidebar() {
    var sidebar = document.getElementById('sidebar');
    if (!sidebar || sidebar.innerHTML.trim() !== '') return;
    sidebar.innerHTML =
      '<div class="sidebar-brand" onclick="window.location.href=(localStorage.getItem(\'role\')==\'Member\'?\'/memberHome\':\'/home\')" style="cursor:pointer;">' +
        '<div class="brand-icon" id="sidebarBrandIcon">CG</div>' +
        '<span class="brand-name" id="sidebarBrandName">Church Genius</span>' +
      '</div>' +
      '<div class="sidebar-user" onclick="window.location.href=(localStorage.getItem(\'role\')==\'Member\'?\'/memberHome\':\'/home\')"' +
        (localStorage.getItem('church') === 'true' ? ' style="display:none"' : '') + '>' +
        '<div class="user-avatar" id="sidebarUserAvatar">&#128100;</div>' +
        '<div class="user-info">' +
          '<div class="user-name" id="sidebarUserName">—</div>' +
          '<div class="user-role" id="sidebarUserRole">—</div>' +
        '</div>' +
        '<span class="user-chevron">&#9662;</span>' +
      '</div>' +
      '<nav class="sidebar-nav" id="sidebarNav"></nav>';
  }

  /* ── Inject standard topbar HTML ─────────────────────────────────── */
  function injectTopbar() {
    var cfg = window.CGP_PAGE || {};
    if (cfg.customTopbar) return;                       // home.html keeps its own topbar
    var topbar = document.getElementById('topbar');
    if (!topbar || topbar.innerHTML.trim() !== '') return;
    var title = cfg.pageTitle || '';
    topbar.innerHTML =
      '<div class="topbar-left" id="topbarLeft">' +
        '<button class="btn-toggle" onclick="toggleSidebar()">&#9776;</button>' +
        '<span class="topbar-title" id="topbarTitle">' + title + '</span>' +
      '</div>' +
      '<div class="topbar-right" id="topbarRight">' +
        '<button class="tb-logout" onclick="logout ? logout() : (window.location.href=\'/login\')">Log out</button>' +
      '</div>';
  }

  /* ── Inject footer HTML ──────────────────────────────────────────── */
  function injectFooter() {
    var footer = document.getElementById('app-footer');
    if (!footer || footer.innerHTML.trim() !== '') return;
    var year = new Date().getFullYear();
    footer.innerHTML = '<span>&#169; ' + year + ' Church Genius Pro. All rights reserved.</span>';
  }

  /* ── Bootstrap on DOMContentLoaded ──────────────────────────────── */
  function bootstrap() {
    injectSidebar();
    injectTopbar();
    injectFooter();
    CGP_renderNav();
    loadFavorites();

    // Sync brand name from localStorage immediately (before session.js fetch)
    var cn = localStorage.getItem('churchName');
    if (cn) {
      var brandEl = document.getElementById('brandName');
      if (brandEl) brandEl.textContent = cn;
      var sidebarBrand = document.getElementById('sidebarBrand');
      if (sidebarBrand) sidebarBrand.textContent = cn;
      var brandNameEl = document.querySelector('.brand-name');
      if (brandNameEl) brandNameEl.textContent = cn;
    }

    // Sync user name + role from localStorage immediately (session.js will
    // overwrite with authoritative values once the /api/session fetch completes)
    var isChurch = localStorage.getItem('church') === 'true';
    if (!isChurch) {
      var first    = (localStorage.getItem('firstName') || '').trim();
      var last     = (localStorage.getItem('lastName')  || '').trim();
      var fullName = last ? (first + ' ' + last).trim() : first;
      var role     = (localStorage.getItem('role') || '').trim();
      var nameEl   = document.getElementById('sidebarUserName');
      var roleEl   = document.getElementById('sidebarUserRole');
      if (nameEl && fullName) nameEl.textContent = fullName;
      if (roleEl && role)     roleEl.textContent = role;
    }

    loadAiAssistant();
    loadFamilyAvatar();
  }

  /* ── Load the shared family photo into the sidebar avatar ───────────────────
   * Every member of a family shares the same picture: the backend resolves one
   * representative, optimized thumbnail for the current user's family and we swap
   * the default 👤 icon for it. Falls back silently to the icon on any failure. */
  function loadFamilyAvatar() {
    // Church accounts hide the sidebar-user block entirely — skip the fetch.
    if (localStorage.getItem('church') === 'true') return;
    var el = document.getElementById('sidebarUserAvatar');
    if (!el) return;
    fetch('/api/family/avatar', { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        if (!d || !d.photo) return;                 // keep default icon
        var img = document.createElement('img');
        img.src = d.photo;
        img.alt = 'Family photo';
        img.style.cssText = 'width:100%;height:100%;object-fit:cover;display:block;border-radius:inherit;';
        el.innerHTML = '';
        el.appendChild(img);
      })
      .catch(function () { /* cosmetic only — ignore */ });
  }

  /* ── Load the global AI Search Assistant on every authenticated page ─────────
   * Ensures the shared voice engine is present, then the assistant component,
   * which self-injects its toolbar/search bar into the page content area. */
  function loadAiAssistant() {
    function present(src) {
      for (var i = 0; i < document.scripts.length; i++) {
        if (document.scripts[i].src && document.scripts[i].src.indexOf(src) !== -1) return true;
      }
      return false;
    }
    function add(src, id) {
      if (id && document.getElementById(id)) return;
      if (present(src)) return;
      var el = document.createElement('script'); el.src = src; if (id) el.id = id; el.defer = true;
      document.body.appendChild(el);
    }
    add('/voice-flags.js', 'voice-flags-script'); // per-church Voice feature gating (hides disabled buttons)
    add('/voice-openai.js', 'aa-voice');   // shared recorder engine (Voice/Converse)
    add('/ai-assistant.js', 'aa-script');  // the assistant itself
    add('/temp-session.js', 'temp-session-script');  // temporary-access countdown banner (self-activates only for temp sessions)
    add('/ntag-session.js', 'ntag-session-script');  // NTAG restricted-access sidebar (self-activates only for NTAG sessions)
  }

  /* -- Export globals */
  window.onNavClick        = onNavClick;
  window.toggleSidebar     = toggleSidebar;
  window.CGP_renderNav     = CGP_renderNav;
  window.renderNav         = CGP_renderNav;
  window.CGP_MENU          = MENU;
  window.CGP_ROLE_SECTIONS = ROLE_SECTIONS;


  document.addEventListener('DOMContentLoaded', bootstrap);

})();
