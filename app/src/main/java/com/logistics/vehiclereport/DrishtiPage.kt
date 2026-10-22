package com.logistics.vehiclereport

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * The DMart Drishti page inside the app: a browser (WebView) where the user signs in themselves, and a reader that
 * takes the rows of Vehicle Summary → Vehicle logs (vehicle number, type, driver, mobile, checked in, checked out),
 * scrolling and paging through the whole list. Only https pages are opened, and only a meradmart.com page can send
 * rows back to the app. The password is typed into the site only; the app never sees or keeps it.
 */
class DrishtiPage(private val web: WebView, private val listener: Listener) {

    interface Listener {
        /** [status] is [DrishtiStore.OK], [DrishtiStore.LOGIN] or [DrishtiStore.NO_TABLE]. */
        fun onRead(status: String, cells: List<List<String>>, pageDay: Long)
        fun onPageLoaded(url: String) {}
        fun onProgress(percent: Int) {}
    }

    @Volatile private var lastUrl = ""
    private val main = Handler(Looper.getMainLooper())
    private var reading = false
    private val timeout = Runnable { finish(DrishtiStore.NO_TABLE, emptyList(), ReportBuilder.startOfDay()) }

    val url: String get() = lastUrl
    val isReading: Boolean get() = reading

    @SuppressLint("SetJavaScriptEnabled")
    fun setup() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION")
        s.saveFormData = false
        // The desktop page: Drishti's table is laid out for a computer screen.
        val ver = Regex("Chrome/([\\d.]+)").find(s.userAgentString ?: "")?.groupValues?.get(1) ?: "124.0.0.0"
        s.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Safari/537.36"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(), "DrishtiBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Sign-in may pass through the company's login page; anything that is not https stays closed.
                return request.url.scheme != "https"
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { lastUrl = url }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) { lastUrl = url }
            override fun onPageFinished(view: WebView, url: String) {
                lastUrl = url
                CookieManager.getInstance().flush()
                listener.onPageLoaded(url)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) = listener.onProgress(newProgress)
        }
    }

    fun openReport() = web.loadUrl(DrishtiStore.URL)

    /** Reads the vehicle log on the page now; the answer comes to [Listener.onRead]. */
    fun read() {
        if (reading) return
        if (!DrishtiStore.isDrishtiUrl(lastUrl)) {
            // Still on a sign-in page outside meradmart.com.
            listener.onRead(DrishtiStore.LOGIN, emptyList(), ReportBuilder.startOfDay())
            return
        }
        reading = true
        main.postDelayed(timeout, 150_000)
        web.evaluateJavascript(READER_JS, null)
    }

    fun destroy() {
        main.removeCallbacks(timeout)
        reading = false
        try {
            web.stopLoading()
            web.removeJavascriptInterface("DrishtiBridge")
            web.destroy()
        } catch (e: Exception) { }
    }

    private fun finish(status: String, cells: List<List<String>>, pageDay: Long) {
        main.removeCallbacks(timeout)
        if (!reading) return
        reading = false
        listener.onRead(status, cells, pageDay)
    }

    inner class Bridge {
        @JavascriptInterface
        fun result(json: String) {
            // Only the Drishti site may hand rows to the app.
            if (!DrishtiStore.isDrishtiUrl(lastUrl)) return
            val out = try { parse(json) } catch (e: Exception) { Triple(DrishtiStore.NO_TABLE, emptyList(), ReportBuilder.startOfDay()) }
            main.post { finish(out.first, out.second, out.third) }
        }
    }

    private fun parse(json: String): Triple<String, List<List<String>>, Long> {
        val o = JSONObject(json)
        val status = when (o.optString("status")) {
            "ok" -> DrishtiStore.OK
            "login" -> DrishtiStore.LOGIN
            else -> DrishtiStore.NO_TABLE
        }
        val arr = o.optJSONArray("cells") ?: JSONArray()
        val cells = (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONArray(i) ?: return@mapNotNull null
            (0 until r.length()).map { r.optString(it) }
        }
        val day = DrishtiData.parseTime(o.optString("pageDate") + " 00:00", 0L)
            .takeIf { it > 0 }?.let { ReportBuilder.startOfDay(it) } ?: ReportBuilder.startOfDay()
        return Triple(status, cells, day)
    }

    companion object {
        /**
         * Finds the table whose headers say Vehicle Number / Checked In, reads its rows by header name (so column
         * order does not matter), scrolls through it and clicks Next page until no new rows come, then sends
         * {status, cells, pageDate} to DrishtiBridge.result. Waits up to 30 s for the table to load.
         */
        private val READER_JS = """
(function(){
  if (window.__drishtiBusy) return; window.__drishtiBusy = true;
  var ORDER = ['site','vehicle','type','driver','mobile','inT','outT','dest'];
  var KEYS = [['vehicle',/vehicle\s*(no|num|number)\b|^vehicle$|^vehicle\s*no\.?$/i],['type',/vehicle\s*type|^type$/i],
    ['driver',/driver\s*name|^driver$/i],['mobile',/mobile|phone|contact/i],['inT',/check(ed)?[\s-]*in|in[\s-]*time|entry\s*time/i],
    ['outT',/check(ed)?[\s-]*out|out[\s-]*time|exit\s*time/i],['site',/site/i],['dest',/destination/i]];
  function sleep(ms){ return new Promise(function(r){ setTimeout(r, ms); }); }
  function txt(e){ return ((e && (e.innerText || e.textContent)) || '').replace(/\s+/g,' ').trim(); }
  function keyOf(h){ for (var i=0;i<KEYS.length;i++) if (KEYS[i][1].test(h)) return KEYS[i][0]; return null; }
  function send(o){ window.__drishtiBusy = false; try { DrishtiBridge.result(JSON.stringify(o)); } catch(e){} }
  function visible(e){ return !!(e && (e.offsetWidth || e.offsetHeight || e.getClientRects().length)); }
  function loginPage(){
    var pw = document.querySelector('input[type=password]');
    return !!(pw && visible(pw));
  }
  function pageDate(){
    var ins = document.querySelectorAll('input');
    for (var i=0;i<ins.length;i++){ var m = (ins[i].value||'').match(/\d{1,2}[\/-]\d{1,2}[\/-]\d{4}/); if (m) return m[0]; }
    var m2 = txt(document.body).match(/\d{1,2}[\/-]\d{1,2}[\/-]\d{4}/); return m2 ? m2[0] : '';
  }
  function grid(){
    var heads = document.querySelectorAll('th,[role=columnheader]');
    var seen = [];
    for (var i=0;i<heads.length;i++){
      var row = heads[i].closest('tr,[role=row]') || heads[i].parentElement;
      if (!row || seen.indexOf(row) >= 0) continue; seen.push(row);
      var cells = Array.prototype.filter.call(row.children, function(c){ return true; });
      var map = {}, byId = {}, hasVehicle = false;
      for (var k=0;k<cells.length;k++){
        var key = keyOf(txt(cells[k]));
        if (!key || map[key] !== undefined && key !== 'vehicle') continue;
        if (map[key] === undefined) map[key] = k;
        var cid = cells[k].getAttribute('col-id') || cells[k].getAttribute('aria-colindex');
        if (cid) byId[cid] = key;
        if (key === 'vehicle') hasVehicle = true;
      }
      if (!hasVehicle) continue;
      var root = row.closest('table,[role=grid],[role=table],[role=treegrid],.ag-root-wrapper') || row.parentElement.parentElement || document.body;
      return { head: row, root: root, map: map, byId: byId };
    }
    return looseGrid();
  }
  // Tables drawn with plain boxes (no th / role): find the "Vehicle Number" heading, take the box whose children are
  // the column headings, and later read every box with the same number of children as a row.
  function looseGrid(){
    var all = document.querySelectorAll('div,span,p,li,label');
    for (var i=0;i<all.length;i++){
      var e = all[i];
      if (e.children.length > 1 || !/^vehicle\s*(no\.?|num|number)$/i.test(txt(e))) continue;
      var row = e;
      for (var up=0; up<5 && row && row !== document.body; up++){
        if (row.parentElement && row.parentElement.children.length >= 4) { row = row.parentElement; break; }
        row = row.parentElement;
      }
      if (!row || row === document.body) continue;
      var map = {};
      for (var k=0;k<row.children.length;k++){ var key = keyOf(txt(row.children[k])); if (key && map[key] === undefined) map[key] = k; }
      if (map.vehicle === undefined) continue;
      var root = row;
      for (var u=0; u<6 && root.parentElement && root.parentElement !== document.body; u++) root = root.parentElement;
      return { head: row, root: root, map: map, byId: {}, loose: true };
    }
    return null;
  }
  function dataRows(g){
    if (g.loose){
      var n = g.head.children.length, boxes = g.root.querySelectorAll('div,li,tr'), res = [];
      for (var b=0;b<boxes.length;b++){
        var x = boxes[b];
        if (x === g.head || x.children.length !== n || g.head.contains(x) || x.contains(g.head)) continue;
        var v = txt(x.children[g.map.vehicle]);
        if (/[a-z]{2}\s*\d{1,2}\s*[a-z]{0,3}\s*\d{3,4}/i.test(v)) res.push(x);
      }
      return res;
    }
    var all = g.root.querySelectorAll('tr,[role=row]'), out = [];
    for (var i=0;i<all.length;i++){
      var r = all[i];
      if (r === g.head || r.querySelector('th,[role=columnheader]')) continue;
      out.push(r);
    }
    return out;
  }
  function collect(g, acc){
    var rows = dataRows(g), added = 0, idKeys = Object.keys(g.byId).length;
    for (var i=0;i<rows.length;i++){
      var cells = rows[i].children, o = {};
      if (idKeys){
        for (var c=0;c<cells.length;c++){
          var cid = cells[c].getAttribute('col-id') || cells[c].getAttribute('aria-colindex');
          if (cid && g.byId[cid]) o[g.byId[cid]] = txt(cells[c]);
        }
      }
      if (!o.vehicle) for (var k in g.map){ var cell = cells[g.map[k]]; if (cell) o[k] = txt(cell); }
      if (!o.vehicle || !/\d/.test(o.vehicle)) continue;
      var key = o.vehicle + '|' + (o.inT||'');
      var row = ORDER.map(function(k){ return o[k] || ''; });
      if (!acc[key]) added++;
      if (!acc[key] || (!acc[key][6] && row[6])) acc[key] = row;
    }
    return added;
  }
  function scroller(g){
    var e = g.root;
    while (e && e !== document.body){
      var st = getComputedStyle(e);
      if (e.scrollHeight > e.clientHeight + 10 && /(auto|scroll)/.test(st.overflowY)) return e;
      e = e.parentElement;
    }
    var inner = g.root.querySelectorAll('*');
    for (var i=0;i<inner.length;i++){
      var s2 = getComputedStyle(inner[i]);
      if (inner[i].scrollHeight > inner[i].clientHeight + 10 && /(auto|scroll)/.test(s2.overflowY)) return inner[i];
    }
    return null;
  }
  async function scrollAll(g, acc){
    var s = scroller(g);
    if (!s) return;
    s.scrollTop = 0; await sleep(300); collect(g, acc);
    for (var n=0; n<300; n++){
      var before = s.scrollTop;
      s.scrollTop = before + Math.max(80, s.clientHeight * 0.8);
      await sleep(250);
      collect(g, acc);
      if (s.scrollTop === before || s.scrollTop + s.clientHeight >= s.scrollHeight - 2) { await sleep(300); collect(g, acc); break; }
    }
  }
  function nextButton(){
    var els = document.querySelectorAll('button,a,[role=button],li.page-item a,.p-paginator-next,.mat-mdc-paginator-navigation-next,.mat-paginator-navigation-next');
    for (var i=0;i<els.length;i++){
      var e = els[i];
      var label = ((e.getAttribute('aria-label')||'') + ' ' + (e.getAttribute('title')||'') + ' ' + (e.className && e.className.baseVal === undefined ? e.className : '')).toLowerCase();
      var t = txt(e);
      var isNext = /\bnext\b/.test(label) || /next/i.test(t) && t.length < 12 || t === '›' || t === '>' ;
      if (!isNext || /last/.test(label)) continue;
      var off = e.disabled || e.getAttribute('aria-disabled') === 'true' || /disabled/.test(label) ||
        (e.parentElement && /disabled/.test(String(e.parentElement.className)));
      if (!off && visible(e)) return e;
    }
    return null;
  }
  function firstRowText(g){ var r = dataRows(g); return r.length ? txt(r[0]) : ''; }
  (async function(){
    try {
      var g = null;
      for (var w=0; w<60; w++){
        if (location.hostname.indexOf('meradmart.com') < 0) { send({status:'login'}); return; }
        if (w > 4 && loginPage()) { send({status:'login'}); return; }
        g = grid();
        if (g && (dataRows(g).length || /no\s+(data|record|vehicle)/i.test(txt(g.root)))) break;
        await sleep(500);
      }
      if (!g) { send({status: loginPage() ? 'login' : 'none', pageDate: pageDate()}); return; }
      await sleep(600);
      var acc = {};
      collect(g, acc);
      await scrollAll(g, acc);
      for (var p=0; p<60; p++){
        var nb = nextButton();
        if (!nb) break;
        var before = firstRowText(g);
        nb.click();
        var moved = false;
        for (var t=0; t<20; t++){ await sleep(300); g = grid() || g; if (firstRowText(g) !== before) { moved = true; break; } }
        if (!moved) break;
        await sleep(300);
        var added = collect(g, acc);
        await scrollAll(g, acc);
        if (!added) break;
      }
      var cells = Object.keys(acc).map(function(k){ return acc[k]; });
      send({status:'ok', cells: cells, pageDate: pageDate()});
    } catch (e) { send({status:'none', error: String(e)}); }
  })();
})();
""".trimIndent()
    }
}

