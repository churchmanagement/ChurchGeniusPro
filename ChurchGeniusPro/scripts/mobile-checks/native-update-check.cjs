const { chromium } = require('playwright');
const fs = require('fs');
const BRIDGE = fs.readFileSync(require('path').join(__dirname, '..', '..', 'src', 'main', 'resources', 'static', 'native-bridge.js'), 'utf8');
(async () => {
  const browser = await chromium.launch({ headless: true });
  let fail = 0; const ok = (label, cond, extra) => { console.log((cond ? '✓ ' : '✗ ') + label + (extra ? ' — ' + extra : '')); if (!cond) fail++; };
  async function run(name, opts) {
    const ctx = await browser.newContext(); const page = await ctx.newPage();
    let version = 'v1', calls = 0, loads = 0;
    await page.route('http://cgp.test/**', async route => {
      const u = new URL(route.request().url());
      if (u.pathname === '/api/app-version') { calls++; return opts.status ? route.fulfill({ status: opts.status, body: '{}' }) : route.fulfill({ contentType: 'application/json', body: JSON.stringify({ version: typeof version === 'function' ? version() : version }) }); }
      if (u.pathname === '/native-bridge.js') return route.fulfill({ contentType: 'application/javascript', body: BRIDGE });
      loads++;
      const patch = `<script>window.__patched=0;var _f=window.fetch;window.fetch=function(i,n){var u=typeof i==='string'?i:(i.url||'');if(u.indexOf('/api/app-version')>=0)window.__patched++;return _f(i,n);};</script>`;
      const cap = opts.browser ? '' : `<script>window.Capacitor={isNativePlatform:()=>true,getPlatform:()=>'android',Plugins:{App:{addListener:(e,fn)=>{window.__resume=fn;}}}};</script>`;
      return route.fulfill({ contentType: 'text/html', body: `<!doctype html><html><body><input id="f">${patch}${cap}<script src="/native-bridge.js"></script></body></html>` });
    });
    await page.goto('http://cgp.test/home'); await page.waitForTimeout(300);
    return { ctx, page, set: v => { version = v; }, calls: () => calls, loads: () => loads };
  }
  const resume = async (page) => { await page.evaluate(() => { window.CGP_NATIVE.checkForNewVersion ? window.CGP_NATIVE.checkForNewVersion() : null; }); await page.waitForTimeout(500); };

  // 1. same version on resume → no reload
  let t = await run('same', {});
  await resume(t.page);
  ok('same version on resume → no reload', t.loads() === 1, 'loads=' + t.loads());
  // 2. new version, nothing typed → reload once
  t.set('v2'); await resume(t.page);
  ok('new version, page untouched → page reloads', t.loads() === 2, 'loads=' + t.loads());
  // after reload pageVersion = v2; same → no more reloads
  await t.page.waitForTimeout(300); await resume(t.page);
  ok('after the reload, no further reloads', t.loads() === 2, 'loads=' + t.loads());
  // 3. flip-flop between two instances → never loops
  t.set('v1'); await resume(t.page); t.set('v2'); await resume(t.page); t.set('v1'); await resume(t.page); t.set('v2'); await resume(t.page);
  ok('two alternating versions → at most one reload per version (no loop)', t.loads() <= 3, 'loads=' + t.loads());
  await t.ctx.close();
  // 4. typed something → bar, no reload; Reload button reloads
  t = await run('dirty', {});
  await t.page.fill('#f', 'half-written note'); t.set('v9'); await resume(t.page);
  ok('new version after typing → no automatic reload', t.loads() === 1, 'loads=' + t.loads());
  ok('… and the "Reload" bar is shown', await t.page.locator('#cgpUpdateBar').count() === 1);
  ok('… the typed text is still there', await t.page.inputValue('#f') === 'half-written note');
  await t.page.click('#cgpUpdateBar button:has-text("Reload")'); await t.page.waitForTimeout(400);
  ok('"Reload" on the bar reloads the page', t.loads() === 2, 'loads=' + t.loads());
  await t.ctx.close();
  // 5. visibilitychange path and the one-minute throttle
  t = await run('throttle', {});
  t.set('v5');
  await t.page.evaluate(() => { Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true }); document.dispatchEvent(new Event('visibilitychange')); });
  await t.page.waitForTimeout(500);
  ok('visibility change within a minute of loading → throttled, no request', t.loads() === 1 && t.calls() === 1, 'loads=' + t.loads() + ' calls=' + t.calls());
  await t.page.evaluate(() => window.__resume && window.__resume()); await t.page.waitForTimeout(400);
  ok('App resume within the minute → also throttled', t.loads() === 1);
  await t.ctx.close();
  // 6b. the version request bypasses the page's fetch wrapper (session.js logs out on any /api 401)
  t = await run('xhr', { status: 401 });
  ok('version check does not go through window.fetch (no logout side effect on 401)', await t.page.evaluate(() => window.__patched) === 0 && t.calls() >= 1, 'calls=' + t.calls());
  await t.ctx.close();
  // 6. signed out (401) → nothing
  t = await run('signedout', { status: 401 });
  await resume(t.page);
  ok('signed-out page (401) → no reload, no bar', t.loads() === 1 && await t.page.locator('#cgpUpdateBar').count() === 0);
  await t.ctx.close();
  // 7. plain browser → bridge does nothing at all
  t = await run('browser', { browser: true });
  ok('in a browser: no version request, no listeners', t.calls() === 0 && await t.page.evaluate(() => !window.CGP_NATIVE.checkForNewVersion), 'calls=' + t.calls());
  await t.ctx.close();
  console.log('failures:', fail); await browser.close(); process.exit(fail ? 1 : 0);
})().catch(e => { console.error('FAILED', e.message); process.exit(1); });
