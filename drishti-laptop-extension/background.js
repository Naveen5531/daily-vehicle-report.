// Every 3 minutes: reload the open Drishti Vehicle Summary tab, read the vehicle log, and – when it changed – send it,
// encrypted with the key from the app's link code, through ntfy.sh to the app. Only the app with that code can read it.
const REPORT = 'https://w4.meradmart.com/app/drishti/report/log-vehicle-summary';
const EVERY_MIN = 3;
const RELAY = 'https://ntfy.sh/';

chrome.runtime.onInstalled.addListener(() => chrome.alarms.create('pull', { periodInMinutes: EVERY_MIN }));
chrome.runtime.onStartup.addListener(() => chrome.alarms.create('pull', { periodInMinutes: EVERY_MIN }));
chrome.alarms.onAlarm.addListener(a => { if (a.name === 'pull') run(false); });
chrome.runtime.onMessage.addListener((m, _s, reply) => {
  if (m && m.type === 'sendNow') { run(true).then(() => reply({ ok: true })); return true; }
  if (m && m.type === 'openReport') { chrome.tabs.create({ url: REPORT }); }
});

async function status(text, extra) {
  await chrome.storage.local.set(Object.assign({ status: text, statusAt: Date.now() }, extra || {}));
}

function parseCode(code) {
  const m = /^DVR1-([A-Za-z0-9]{16,64})-([A-Za-z0-9_-]{40,})$/.exec((code || '').trim());
  if (!m) return null;
  const b = atob(m[2].replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((m[2].length + 3) % 4));
  const key = new Uint8Array([...b].map(c => c.charCodeAt(0)));
  return key.length === 32 ? { topic: m[1], key } : null;
}

function waitLoaded(tabId) {
  return new Promise(resolve => {
    const t = setTimeout(() => { chrome.tabs.onUpdated.removeListener(l); resolve(); }, 60000);
    function l(id, info) {
      if (id === tabId && info.status === 'complete') { clearTimeout(t); chrome.tabs.onUpdated.removeListener(l); resolve(); }
    }
    chrome.tabs.onUpdated.addListener(l);
  });
}

async function findTab() {
  const tabs = await chrome.tabs.query({ url: 'https://*.meradmart.com/*' });
  return tabs.find(t => /log-vehicle-summary/.test(t.url || '')) || null;
}

async function gzip(bytes) {
  const s = new Blob([bytes]).stream().pipeThrough(new CompressionStream('gzip'));
  return new Uint8Array(await new Response(s).arrayBuffer());
}

async function encrypt(key, bytes) {
  const k = await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['encrypt']);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, k, bytes));
  const out = new Uint8Array(12 + ct.length); out.set(iv); out.set(ct, 12);
  return out;
}

function b64(bytes) { let s = ''; for (const b of bytes) s += String.fromCharCode(b); return btoa(s); }

async function sha(text) {
  const h = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return b64(new Uint8Array(h));
}

async function send(link, payload) {
  const box = await encrypt(link.key, await gzip(new TextEncoder().encode(JSON.stringify(payload))));
  const text = b64(box);
  // Small: a plain message. Large: an attachment (ntfy keeps it 3 hours; the app takes it within minutes).
  const res = text.length <= 3900
    ? await fetch(RELAY + link.topic, { method: 'POST', body: text, headers: { 'Title': 'gate', 'Cache': 'yes' } })
    : await fetch(RELAY + link.topic, { method: 'PUT', body: box, headers: { 'Filename': 'gate.bin' } });
  if (!res.ok) throw new Error('relay ' + res.status + (res.status === 429 ? ' (daily limit reached)' : ''));
}

let busy = false;
async function run(force) {
  if (busy) return;
  busy = true;
  try {
    const st = await chrome.storage.local.get(['code', 'on', 'lastHash', 'lastSent', 'lastState']);
    if (!st.on && !force) return;
    const link = parseCode(st.code);
    if (!link) { await status('Paste the link code from the app first.'); return; }
    const tab = await findTab();
    if (!tab) { await status('Open Drishti → Vehicle Summary in a tab (and keep it open).'); return; }
    await chrome.tabs.reload(tab.id);
    await waitLoaded(tab.id);
    await new Promise(r => setTimeout(r, 2500));
    await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ['content.js'] });
    const [res] = await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: () => window.__dvrRead() });
    const out = JSON.parse((res && res.result) || '{"status":"none"}');
    const state = out.status || 'none';
    if (state !== 'ok') {
      // Tell the app once when the sign-in has expired; "no table" is only shown here.
      if (state === 'login' && st.lastState !== 'login') await send(link, { v: 1, status: 'login', at: Date.now() });
      await status(state === 'login' ? 'Drishti sign-in expired – log in again in the tab.' : 'Vehicle log not found on the page – check the tab.',
        { lastState: state });
      return;
    }
    const cells = out.cells || [];
    const hash = await sha(JSON.stringify(cells) + '|' + (out.pageDate || ''));
    // Unchanged: send again only every 30 minutes (keeps well inside ntfy's 250 messages a day).
    if (!force && hash === st.lastHash && Date.now() - (st.lastSent || 0) < 30 * 60000) {
      await status('No change · ' + cells.length + ' vehicles · checked ' + new Date().toLocaleTimeString(), { lastState: 'ok' });
      return;
    }
    await send(link, { v: 1, status: 'ok', cells, pageDate: out.pageDate || '', at: Date.now() });
    await status('Sent ' + cells.length + ' vehicles at ' + new Date().toLocaleTimeString(),
      { lastHash: hash, lastSent: Date.now(), lastState: 'ok' });
  } catch (e) {
    await status('Error: ' + (e && e.message || e));
  } finally {
    busy = false;
  }
}
