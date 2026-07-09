/*
 * Restricted-page indicator. On a gated page, set `window.CGP_PRIVATE_KEY` to the
 * page-group key (e.g. 'kids', 'eventcheckin') before loading this script. If the
 * page is currently marked Private for the tenant, a small "church network" badge is
 * shown. (If the user is seeing the page at all, they already passed the network
 * check — this badge just communicates that the restriction is active.)
 */
(function () {
  var key = window.CGP_PRIVATE_KEY;
  if (!key) return;
  try {
    fetch('/api/private-access/page-status?key=' + encodeURIComponent(key), { headers: { 'Accept': 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) { if (d && d.private) showBadge(); })
      .catch(function () {});
  } catch (e) {}

  function showBadge() {
    if (document.getElementById('cgpPrivateBadge')) return;
    var b = document.createElement('div');
    b.id = 'cgpPrivateBadge';
    b.title = 'This page is restricted to approved church networks.';
    b.textContent = '🔒 Church network only';
    b.style.cssText =
      'position:fixed;right:14px;bottom:14px;z-index:9999;background:#673147;color:#fff;' +
      'font:600 12px -apple-system,Segoe UI,Roboto,sans-serif;padding:7px 13px;border-radius:20px;' +
      'box-shadow:0 4px 14px rgba(0,0,0,.22);display:flex;align-items:center;gap:6px;';
    document.body.appendChild(b);
  }
})();
