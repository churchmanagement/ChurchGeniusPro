/* ChurchGeniusPro — global legal footer + cookie-consent banner.
   Safe to include on any page. Injects once; no dependencies. */
(function () {
  "use strict";

  var POLICIES = [
    { href: "/terms-of-service",      label: "Terms of Service" },
    { href: "/privacy-policy",        label: "Privacy Policy" },
    { href: "/cookie-policy",         label: "Cookie Policy" },
    { href: "/acceptable-use-policy", label: "Acceptable Use Policy" },
    { href: "/refund-policy",         label: "Refund Policy" }
  ];

  function getCookie(name) {
    return document.cookie.split("; ").reduce(function (acc, c) {
      var p = c.split("=");
      return p[0] === name ? decodeURIComponent(p.slice(1).join("=")) : acc;
    }, "");
  }
  function setCookie(name, value, days) {
    var d = new Date();
    d.setTime(d.getTime() + days * 864e5);
    document.cookie = name + "=" + encodeURIComponent(value) +
      ";expires=" + d.toUTCString() + ";path=/;SameSite=Lax";
  }

  function injectStyles() {
    if (document.getElementById("cgp-legal-style")) return;
    var s = document.createElement("style");
    s.id = "cgp-legal-style";
    s.textContent =
      "#cgp-legal-footer{position:fixed;left:0;right:0;bottom:0;z-index:40;" +
      "background:rgba(246,244,246,.95);-webkit-backdrop-filter:blur(6px);backdrop-filter:blur(6px);" +
      "border-top:1px solid #e7e1e6;" +
      "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;" +
      "text-align:center;padding:9px 14px;color:#8a7d84;font-size:12px;line-height:1.7;}" +
      "#cgp-legal-footer a{color:#673147;text-decoration:none;font-weight:600;margin:0 7px;white-space:nowrap;}" +
      "#cgp-legal-footer a:hover{text-decoration:underline;}" +
      "#cgp-legal-footer .sep{color:#cbbfc6;}" +
      "#cgp-legal-footer .cgp-foot-copy{display:block;margin-top:3px;font-size:11px;color:#a89ba2;}" +
      "#cgp-cookie-banner{position:fixed;left:50%;transform:translateX(-50%);bottom:64px;z-index:9999;" +
      "max-width:680px;width:calc(100% - 28px);background:#2b2230;color:#f3eef1;border-radius:12px;" +
      "box-shadow:0 12px 36px rgba(0,0,0,.32);padding:14px 16px;display:flex;flex-wrap:wrap;align-items:center;" +
      "gap:10px 14px;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;font-size:13.5px;}" +
      "#cgp-cookie-banner p{margin:0;flex:1 1 280px;line-height:1.5;}" +
      "#cgp-cookie-banner a{color:#f0c879;font-weight:600;text-decoration:underline;}" +
      "#cgp-cookie-banner .cbtns{display:flex;gap:8px;flex:0 0 auto;}" +
      "#cgp-cookie-banner button{font:inherit;font-weight:700;border:none;border-radius:8px;padding:8px 16px;cursor:pointer;}" +
      "#cgp-cookie-banner .accept{background:#f0c879;color:#3a2a18;}" +
      "#cgp-cookie-banner .decline{background:transparent;color:#d8ccd3;border:1px solid rgba(255,255,255,.4);}" +
      "@media print{#cgp-legal-footer,#cgp-cookie-banner{display:none!important;}}";
    document.head.appendChild(s);
  }

  function buildFooter() {
    if (document.getElementById("cgp-legal-footer")) return;
    var f = document.createElement("footer");
    f.id = "cgp-legal-footer";
    var links = POLICIES.map(function (p) {
      return '<a href="' + p.href + '">' + p.label + "</a>";
    }).join('<span class="sep">·</span>');
    f.innerHTML = links +
      '<span class="cgp-foot-copy">© ' + new Date().getFullYear() +
      " ChurchGenius LLC. All rights reserved.</span>";
    document.body.appendChild(f);
    reserveSpace(f);
  }

  // The footer is fixed, so it doesn't disturb centered (flex) layouts like the
  // login screen. On normal-flow pages, reserve bottom padding so it never covers
  // page content.
  function reserveSpace(f) {
    try {
      var disp = window.getComputedStyle(document.body).display;
      if (disp !== "flex" && disp !== "grid") {
        var h = f.offsetHeight || 44;
        var cur = parseInt(window.getComputedStyle(document.body).paddingBottom, 10) || 0;
        if (cur < h + 8) document.body.style.paddingBottom = (h + 8) + "px";
      }
    } catch (e) {}
  }

  function recordCookieConsent(choice) {
    setCookie("cgp_cookie_consent", choice, 365);
    try {
      fetch("/api/policy-acceptance", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ policyType: "cookie", source: "cookie-banner", choice: choice })
      }).catch(function () {});
    } catch (e) {}
  }

  function buildCookieBanner() {
    if (getCookie("cgp_cookie_consent")) return;
    if (document.getElementById("cgp-cookie-banner")) return;
    var b = document.createElement("div");
    b.id = "cgp-cookie-banner";
    b.setAttribute("role", "dialog");
    b.setAttribute("aria-label", "Cookie consent");
    b.innerHTML =
      "<p>We use essential cookies to run ChurchGeniusPro and, with your consent, " +
      'optional cookies to improve it. See our <a href="/cookie-policy">Cookie Policy</a>.</p>' +
      '<div class="cbtns">' +
      '<button class="decline" type="button">Decline optional</button>' +
      '<button class="accept" type="button">Accept all</button>' +
      "</div>";
    document.body.appendChild(b);
    b.querySelector(".accept").addEventListener("click", function () {
      recordCookieConsent("all"); b.remove();
    });
    b.querySelector(".decline").addEventListener("click", function () {
      recordCookieConsent("essential"); b.remove();
    });
  }

  function init() {
    injectStyles();
    buildFooter();
    buildCookieBanner();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
