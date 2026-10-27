/* Runs the Meetings page's own meeting-voice.js (plus voice-core.js) in Node with a
 * minimal fake DOM, so the Converse state machine can be exercised exactly as shipped.
 *
 *   node meeting-harness.cjs <voice-core.js> <meeting-voice.js> <scenario.json | '<json scenario>'>
 *
 * scenario: { form:{meetingType:"Bible Study", locationSearch:"", meetingDate:"", startTime:"", occurrence:"One-time", note:""},
 *             categories:["Bible Study","Prayer Meeting"], locations:[{id:1,locType:"H",displayName:"Fellowship Hall"}],
 *             mode:"converse", start:true, steps:["Set location to Fellowship Hall","yes"] }
 * Prints JSON: { replies:[...], form:{...}, stage, confirm:bool, log:[...] }.
 */
const vm = require('vm'); const fs = require('fs');
const [,, corePath, meetingPath, scenarioArg] = process.argv;
// The scenario arrives as a path to a JSON file (NodeJs.meetingScenario — safe on Windows);
// inline JSON is still accepted for running the harness by hand.
const scenarioJson = scenarioArg && !scenarioArg.trim().startsWith('{') ? fs.readFileSync(scenarioArg, 'utf8') : scenarioArg;
const sc = JSON.parse(scenarioJson || '{}');

function mkEl(id, init){
  const e = { id, value: '', style: {}, innerHTML: '', textContent: '', options: [], selectedIndex: 0, _cls: new Set(),
    classList: { add(c){ e._cls.add(c); }, remove(c){ e._cls.delete(c); }, toggle(c,on){ on?e._cls.add(c):e._cls.delete(c); }, contains(c){ return e._cls.has(c); } },
    addEventListener(){}, removeEventListener(){}, dispatchEvent(){ return true; }, focus(){}, blur(){}, click(){},
    querySelector(){ return null; }, querySelectorAll(){ return []; }, appendChild(){}, insertBefore(){}, removeChild(){}, setAttribute(){}, getAttribute(){ return null; },
    scrollIntoView(){} };
  Object.assign(e, init || {});
  return e;
}
const els = {};
const form = sc.form || {};
Object.keys(form).forEach(id => { els[id] = mkEl(id, { value: form[id] }); });
// Category <select>: an empty first option, then the church's categories.
const cats = sc.categories || [];
els.meetingType = els.meetingType || mkEl('meetingType');
els.meetingType.options = [{ value: '', text: '' }].concat(cats.map((c, i) => ({ value: String(i + 1), text: c })));
if (form.meetingType) { const i = cats.indexOf(form.meetingType); els.meetingType.value = i >= 0 ? String(i + 1) : ''; }
['locationSearch','meetingDate','seriesStartDate','startTime','occurrence','note','endDate','monthDayOfMonth','monthWeekOrdinal','monthWeekDay']
  .forEach(id => { if (!els[id]) els[id] = mkEl(id, { value: id === 'occurrence' ? 'One-time' : '' }); });

const storage = () => { const m = {}; return { getItem: k => (k in m ? m[k] : null), setItem: (k, v) => { m[k] = String(v); }, removeItem: k => { delete m[k]; } }; };
const window = {
  location: { pathname: '/meetings' },
  allLocations: sc.locations || [],
  localStorage: storage(), sessionStorage: storage(),
  addEventListener(){}, setTimeout, clearTimeout, setInterval, clearInterval,
  selectLocation(locType, id){ const l = (sc.locations || []).find(x => String(x.id) === String(id) && x.locType === locType); els.locationSearch.value = l ? l.displayName : ''; window.__selected = { locType, id }; },
  onMeetingTypeChange(){}, onOccurrenceChange(v){ els.occurrence.value = v; }, setSelectedDays(d){ window.__days = d; }, setSelectedMonths(m){ window.__months = m; },
  saveMeeting(){ window.__saved = true; },
};
const document = {
  readyState: 'complete', body: mkEl('body'),
  getElementById(id){ return els[id] || null; },
  querySelector(){ return null; }, querySelectorAll(){ return []; },
  createElement(tag){ return mkEl(tag); }, addEventListener(){}, removeEventListener(){},
};
const ctx = { window, document, localStorage: window.localStorage, sessionStorage: window.sessionStorage, console, setTimeout, clearTimeout, setInterval, clearInterval,
  fetch(){ return Promise.resolve({ ok: false, json(){ return Promise.resolve([]); } }); }, navigator: { userAgent: 'node' } };
ctx.window.document = document; ctx.globalThis = ctx;
vm.createContext(ctx);
vm.runInContext(fs.readFileSync(corePath, 'utf8'), ctx);
ctx.window.VoiceCore = ctx.window.VoiceCore || ctx.VoiceCore;
vm.runInContext(fs.readFileSync(meetingPath, 'utf8'), ctx);
const M = ctx.window.CGP_MEETING;
if (sc.today) M.setToday(sc.today);

const replies = [];
if (sc.start) replies.push(M.startConverse());
else if (sc.stage) { M._reset(); M._state().stage = sc.stage; }
(sc.steps || []).forEach(t => { replies.push(M.converseStep(t)); });
const st = M._state();
const out = { replies, stage: st.stage, confirm: !!st.confirm, updating: st.updating || null,
  form: { meetingType: (els.meetingType.options.find(o => o.value === els.meetingType.value) || {}).text || '', locationSearch: els.locationSearch.value,
          meetingDate: els.meetingDate.value, seriesStartDate: els.seriesStartDate.value, startTime: els.startTime.value, occurrence: els.occurrence.value, note: els.note.value, endDate: els.endDate.value },
  selected: window.__selected || null, saved: !!window.__saved,
  setCmd: sc.setCmd != null ? M.parseSetCmd(sc.setCmd) : undefined, isDone: sc.isDone != null ? M.isDoneCmd(sc.isDone) : undefined, isStop: sc.isStop != null ? M.isStopCmd(sc.isStop) : undefined };
process.stdout.write(JSON.stringify(out));
