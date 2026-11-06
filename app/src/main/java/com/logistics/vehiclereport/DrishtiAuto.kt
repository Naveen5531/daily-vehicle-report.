package com.logistics.vehiclereport

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView

/**
 * Reads Drishti by itself every [DrishtiStore.AUTO_EVERY_MS] while the app is open, with an invisible browser that
 * uses the sign-in made on the Drishti screen. Runs only after Drishti has worked once on this phone, while auto
 * pull is on, and stops when the sign-in has expired (Today then asks to sign in again).
 */
class DrishtiAuto(private val a: MainActivity, private val host: ViewGroup) : DrishtiPage.Listener {

    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var page: DrishtiPage? = null
    private var started = false
    private val tick = Runnable { runNow() }
    private val giveUp = Runnable { stopPage(); resume() }
    /** When the last try started (a failed try also waits a full round before the next). */
    private var lastTry = 0L

    // While Drishti live runs in the background it does the reading; this one waits.
    private fun allowed() = DrishtiStore.autoOn(a) && DrishtiStore.everWorked(a) &&
        (LaptopLink.linked(a) || DrishtiStore.status(a) != DrishtiStore.LOGIN) && !DrishtiLiveService.running

    fun resume() {
        main.removeCallbacks(tick)
        if (!allowed()) return
        val every = if (LaptopLink.linked(a)) 60_000L else DrishtiStore.AUTO_EVERY_MS
        val due = (if (LaptopLink.linked(a)) lastTry else maxOf(DrishtiStore.lastPull(a), lastTry)) + every - System.currentTimeMillis()
        main.postDelayed(tick, due.coerceIn(5_000L, every))
    }

    fun pause() {
        main.removeCallbacks(tick)
        stopPage()
    }

    private fun runNow() {
        if (!allowed() || page != null) { resume(); return }
        lastTry = System.currentTimeMillis()
        if (LaptopLink.linked(a)) {
            // The office laptop reads Drishti; take what it sent.
            val app = a.applicationContext
            Thread {
                try { LaptopLink.pullAndApply(app) } catch (e: Throwable) { }
                main.post { resume() }
            }.start()
            return
        }
        val w = try { WebView(a) } catch (e: Throwable) { resume(); return } // no WebView on this phone right now
        // Attached but not seen: 1 x 1 pixel, invisible.
        w.visibility = View.INVISIBLE
        host.addView(w, ViewGroup.LayoutParams(1, 1))
        web = w
        started = false
        page = DrishtiPage(w, this).also { it.setup(); it.openReport() }
        main.postDelayed(giveUp, 180_000)
    }

    override fun onPageLoaded(url: String) {
        val p = page ?: return
        if (started) return
        started = true
        // Give the page a moment to start its own loading, then read (the reader waits for the table).
        main.postDelayed({ if (page === p) p.read() }, 2_000)
    }

    override fun onRead(state: String, cells: List<List<String>>, pageDay: Long) {
        stopPage()
        if (state != DrishtiStore.OK) {
            DrishtiStore.setStatus(a, state)
            if (state != DrishtiStore.LOGIN) resume()
            return
        }
        val app = a.applicationContext
        Thread {
            try { DrishtiStore.apply(app, cells, pageDay) } catch (e: Throwable) { }
            main.post { resume() }
        }.start()
    }

    private fun stopPage() {
        main.removeCallbacks(giveUp)
        val p = page; val w = web
        page = null; web = null
        if (w != null) {
            try { host.removeView(w) } catch (e: Exception) { }
        }
        p?.destroy()
    }
}
