const $ = id => document.getElementById(id);
async function show() {
  const s = await chrome.storage.local.get(['code', 'on', 'status', 'statusAt']);
  $('code').value = s.code || '';
  $('on').checked = !!s.on;
  $('status').textContent = (s.status || 'Not started yet.') + (s.statusAt ? '  (' + new Date(s.statusAt).toLocaleTimeString() + ')' : '');
}
$('save').onclick = async () => { await chrome.storage.local.set({ code: $('code').value.trim(), lastHash: '' }); show(); };
$('on').onchange = async () => {
  await chrome.storage.local.set({ on: $('on').checked });
  chrome.alarms.create('pull', { periodInMinutes: 3 });
  show();
};
$('now').onclick = async () => { $('status').textContent = 'Reading the page…'; await chrome.runtime.sendMessage({ type: 'sendNow' }); show(); };
$('open').onclick = () => chrome.runtime.sendMessage({ type: 'openReport' });
chrome.storage.onChanged.addListener(show);
show();
