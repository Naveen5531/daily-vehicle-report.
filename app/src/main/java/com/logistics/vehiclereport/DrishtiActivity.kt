package com.logistics.vehiclereport

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.Date

/**
 * DMart Drishti in the app. The user signs in on the site itself (the sign-in stays for next time), opens
 * Vehicle Summary, and Pull now reads the gate's vehicle log: Checked In -> Reported, Checked Out -> Departed on Today,
 * and new vehicles into the vehicle list. The page is read once by itself when Vehicle Summary first opens.
 */
class DrishtiActivity : AppCompatActivity(), DrishtiPage.Listener {

    private lateinit var page: DrishtiPage
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var pull: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private var autoReadDone = false
    private var silentRead = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        AppLock.applyScreenBlock(this)
        try {
            setContentView(R.layout.activity_drishti)
        } catch (e: Throwable) {
            // No WebView on this phone (it is being updated, or switched off).
            Toast.makeText(this, R.string.drishti_no_webview, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.drishtiRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        web = findViewById(R.id.drishtiWeb)
        status = findViewById(R.id.drishtiStatus)
        pull = findViewById(R.id.drishtiPull)
        progress = findViewById(R.id.drishtiProgress)
        page = DrishtiPage(web, this)
        page.setup()

        findViewById<View>(R.id.drishtiBack).setOnClickListener { finish() }
        pull.setOnClickListener { startRead(silent = false) }
        findViewById<View>(R.id.drishtiMore).setOnClickListener { showMenu(it) }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })

        showStatus()
        if (savedInstanceState == null) page.openReport() else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    override fun onStart() {
        super.onStart()
        AppLock.checkOnStart(this)
    }

    override fun onResume() {
        super.onResume()
        AppLock.stillInApp()
        if (::web.isInitialized) web.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::web.isInitialized) web.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing) AppLock.onBackground()
    }

    override fun onDestroy() {
        if (::page.isInitialized) page.destroy()
        super.onDestroy()
    }

    // ---------- Page ----------

    override fun onProgress(percent: Int) {
        progress.visibility = if (percent in 1..99 || page.isReading) View.VISIBLE else View.INVISIBLE
        if (!page.isReading) progress.setProgressCompat(percent, true)
    }

    override fun onPageLoaded(url: String) {
        // Vehicle Summary opened: read it once by itself (the user can always tap Pull now).
        if (!autoReadDone && url.contains("log-vehicle-summary") && DrishtiStore.isDrishtiUrl(url)) {
            autoReadDone = true
            web.postDelayed({ if (!isFinishing) startRead(silent = true) }, 1500)
        }
    }

    private fun startRead(silent: Boolean) {
        if (page.isReading) return
        silentRead = silent
        if (!DrishtiStore.isDrishtiUrl(page.url) || !page.url.contains("drishti")) {
            if (!silent) {
                // Not on the report: open it, then read when it has loaded.
                autoReadDone = false
                page.openReport()
                status.setText(R.string.drishti_opening)
            }
            return
        }
        pull.isEnabled = false
        pull.setText(R.string.drishti_reading)
        progress.visibility = View.INVISIBLE
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
        status.setText(R.string.drishti_reading_long)
        page.read()
    }

    override fun onRead(state: String, cells: List<List<String>>, pageDay: Long) {
        if (state != DrishtiStore.OK) {
            DrishtiStore.setStatus(this, state)
            done()
            showStatus()
            if (!silentRead || state == DrishtiStore.LOGIN) Toast.makeText(this,
                if (state == DrishtiStore.LOGIN) R.string.drishti_need_login else R.string.drishti_no_table, Toast.LENGTH_LONG).show()
            return
        }
        val app = applicationContext
        Thread {
            val r = try { DrishtiStore.apply(app, cells, pageDay) } catch (e: Throwable) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                done()
                showStatus()
                if (r == null) { Toast.makeText(this, R.string.drishti_failed, Toast.LENGTH_LONG).show(); return@runOnUiThread }
                val text = getString(R.string.drishti_summary, r.read, r.inList, r.reported, r.departed, r.newVehicles)
                if (silentRead) Toast.makeText(this, text, Toast.LENGTH_LONG).show()
                else MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.drishti_pulled)
                    .setMessage(text + if (r.read == 0) "\n\n" + getString(R.string.drishti_zero_hint, DrishtiStore.site(this)) else "")
                    .setPositiveButton(R.string.drishti_see_today) { _, _ -> finish() }
                    .setNegativeButton(R.string.close, null)
                    .show()
            }
        }.start()
    }

    private fun done() {
        pull.isEnabled = true
        pull.setText(R.string.drishti_pull)
        progress.visibility = View.INVISIBLE
        progress.isIndeterminate = false
    }

    private fun showStatus() {
        status.text = statusText(this)
    }

    // ---------- Menu ----------

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.drishti_menu_report)
        m.menu.add(0, 2, 1, R.string.drishti_menu_reload)
        m.menu.add(0, 3, 2, getString(R.string.drishti_menu_site, DrishtiStore.site(this).ifBlank { "–" }))
        m.menu.add(0, 4, 3, if (DrishtiStore.autoOn(this)) R.string.drishti_menu_auto_off else R.string.drishti_menu_auto_on)
        m.menu.add(0, 5, 4, R.string.drishti_menu_signout)
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { autoReadDone = false; page.openReport() }
                2 -> web.reload()
                3 -> askSite()
                4 -> {
                    val on = !DrishtiStore.autoOn(this)
                    DrishtiStore.setAuto(this, on)
                    Toast.makeText(this, if (on) R.string.drishti_auto_on_done else R.string.drishti_auto_off_done, Toast.LENGTH_SHORT).show()
                    showStatus()
                }
                5 -> signOut()
            }
            true
        }
        m.show()
    }

    private fun askSite() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(DrishtiStore.site(this@DrishtiActivity))
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.drishti_site_title)
            .setMessage(R.string.drishti_site_msg)
            .setView(box)
            .setPositiveButton(R.string.done) { _, _ -> DrishtiStore.setSite(this, input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun signOut() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.drishti_menu_signout)
            .setMessage(R.string.drishti_signout_msg)
            .setPositiveButton(R.string.drishti_signout_yes) { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                web.clearCache(true)
                DrishtiStore.setStatus(this, DrishtiStore.LOGIN)
                autoReadDone = false
                page.openReport()
                showStatus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        /** One line about Drishti for this screen and the Today card. */
        fun statusText(a: android.content.Context): String {
            val last = DrishtiStore.lastPull(a)
            val auto = if (DrishtiStore.autoOn(a)) a.getString(R.string.drishti_auto_suffix) else ""
            return when (DrishtiStore.status(a)) {
                DrishtiStore.LOGIN -> a.getString(R.string.drishti_status_login)
                DrishtiStore.NO_TABLE -> a.getString(R.string.drishti_status_none)
                else -> if (last <= 0) a.getString(R.string.drishti_status_new)
                else a.getString(R.string.drishti_status_ok, ReportBuilder.timeFmt().format(Date(last)), DrishtiStore.lastCount(a)) + auto
            }
        }
    }
}
