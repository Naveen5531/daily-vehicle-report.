package com.logistics.vehiclereport

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.ComponentName
import androidx.activity.enableEdgeToEdge
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var today: TodayTab
    private lateinit var whatsapp: WhatsAppTab
    private lateinit var vehicles: VehiclesTab
    private lateinit var live: LiveTab
    private lateinit var rates: RatesTab
    private lateinit var mail: MailTab
    private lateinit var tabViews: Map<Int, View>
    private lateinit var bottomNav: BottomNavigationView
    private var export: ExportTab? = null
    private var plan: PlanTab? = null
    /** Reads DMart Drishti's gate log by itself while the app is open. */
    private var drishti: DrishtiAuto? = null
    /** The tab on screen (Live, Rates and Mail have no bottom-bar button; Back returns to the tab they open from). */
    private var currentTab = R.id.nav_today
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var liveDot: View
    private lateinit var liveRing: View
    private lateinit var liveText: TextView

    private val watchedKeys = setOf(VehicleStore.KEY_RECORDS, VehicleStore.KEY_DEDICATED, VehicleStore.KEY_UNMATCHED, GateMarks.KEY, PlateScans.KEY, DrishtiStore.KEY_STATUS)
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in watchedKeys) runOnUiThread { refreshAll() }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile(uri)
    }

    private val plateLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode != RESULT_OK || data == null) return@registerForActivityResult
        val plate = data.getStringExtra(PlateScanActivity.EXTRA_PLATE) ?: return@registerForActivityResult
        onPlateScanned(data.getStringExtra(PlateScanActivity.EXTRA_ROW).orEmpty(),
            data.getStringExtra(PlateScanActivity.EXTRA_CODE) ?: VehicleParser.last4(plate), plate)
    }

    /**
     * Opens the camera to read the full number of [row] (a list vehicle, or a 4-digit code not in the list),
     * which was posted today with only its last 4 digits.
     */
    fun scanPlate(row: String) {
        val code = VehicleParser.last4(row)
        val known = VehicleStore.getVehicles(this).map { it.number }.filter { VehicleParser.last4(it) == code }
        plateLauncher.launch(Intent(this, PlateScanActivity::class.java)
            .putExtra(PlateScanActivity.EXTRA_CODE, code)
            .putExtra(PlateScanActivity.EXTRA_ROW, row)
            .putStringArrayListExtra(PlateScanActivity.EXTRA_KNOWN, ArrayList(known)))
    }

    /** Saves what the camera read: today's posts of those 4 digits now count for [plate]; says what changed. */
    private fun onPlateScanned(row: String, code: String, plate: String) {
        val day = ReportBuilder.startOfDay()
        holdTodayOrder()
        PlateScans.add(this, day, code, plate)
        // A mark already made on the row (Reported, Departed) goes with the vehicle that really came.
        if (row.isNotBlank() && row != plate) GateMarks.move(this, day, row, plate)
        val listed = VehicleStore.getVehicles(this).any { it.number == plate }
        // Posted as 4 digits that no list vehicle has: the scanned vehicle goes into the vehicle list (record only),
        // so the next post of those 4 digits matches it by itself.
        if (!listed && row.length <= 4) addScannedToList(plate)
        val p = PlateReader.pretty(plate)
        val msg = when {
            row == plate -> getString(R.string.scan_ok, p)
            row.length <= 4 -> getString(R.string.scan_code_saved, p, row)
            listed -> getString(R.string.scan_other_listed, p, row)
            else -> getString(R.string.scan_not_listed, p, row)
        }
        val arrived = GateMarks.forDay(this, day)[plate]?.gate.let { it == Gate.REPORTED || it == Gate.DEPARTED }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scan_done_title)
            .setMessage(msg)
            .apply {
                if (!arrived) setPositiveButton(R.string.scan_mark_reported) { _, _ -> GateMarks.set(this@MainActivity, day, plate, Gate.REPORTED) }
                // A different vehicle with the same last 4 as a list vehicle: saving it is your choice
                // (afterwards a post of those 4 digits is checked between the two, and a scan settles it).
                if (!listed && row.length > 4) setNeutralButton(R.string.scan_save_master) { _, _ ->
                    addScannedToList(plate)
                    Toast.makeText(this@MainActivity, getString(R.string.scan_saved_master, p), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.done, null)
            .show()
    }

    /** Adds a scanned vehicle to the vehicle list as record only, with what today's posts say (type, vendor, place, mobile). */
    private fun addScannedToList(plate: String) {
        val posts = VehicleStore.getAll(this).filter { it.vehicle == plate }.sortedByDescending { it.time }
        fun pick(f: (VehicleRecord) -> String) = posts.firstOrNull { f(it).isNotBlank() }?.let(f).orEmpty()
        VehicleStore.saveVehicle(this, DedicatedVehicle(plate, type = pick { it.size }, driver = pick { it.driver },
            mobile = pick { it.mobile }, transporter = pick { it.vendor }, route = pick { it.city }, dedicated = false))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15+ draws apps under the status and navigation bars; keep dark icons on the light page.
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        // Every account keeps its own data: sign in first.
        if (!Account.isSignedIn(this)) {
            startActivity(Intent(this, SignInActivity::class.java).apply {
                if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) { action = intent.action; type = intent.type; intent.extras?.let { putExtras(it) } }
            })
            finish()
            return
        }
        AppLock.applyScreenBlock(this)
        try {
            setContentView(R.layout.activity_main)
            // Keep the header below the status bar and the tab bar above the navigation bar / keyboard.
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
                val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                androidx.core.view.WindowInsetsCompat.CONSUMED
            }
        } catch (e: Throwable) {
            // Could not even build the screen: show the error so it can be shared.
            CrashLog.save(this, "Screen layout", e)
            showPlainError(CrashLog.takeSaved(this).orEmpty())
            return
        }

        titleText = findViewById(R.id.titleText)
        subtitleText = findViewById(R.id.subtitleText)
        liveDot = findViewById(R.id.liveDot)
        liveRing = findViewById(R.id.liveRing)
        liveText = findViewById(R.id.liveText)
        bottomNav = findViewById(R.id.bottomNav)

        tabViews = mapOf(
            R.id.nav_today to findViewById<View>(R.id.tabToday),
            R.id.nav_whatsapp to findViewById<View>(R.id.tabWhatsApp),
            R.id.nav_live to findViewById<View>(R.id.tabLive),
            R.id.nav_vehicles to findViewById<View>(R.id.tabVehicles),
            R.id.nav_rates to findViewById<View>(R.id.tabRates),
            R.id.nav_mail to findViewById<View>(R.id.tabMail),
            R.id.nav_export to findViewById<View>(R.id.tabExport),
            R.id.nav_plan to findViewById<View>(R.id.tabPlan)
        )
        safe("Today setup") { today = TodayTab(this, tabViews.getValue(R.id.nav_today)) }
        safe("WhatsApp setup") { whatsapp = WhatsAppTab(this, tabViews.getValue(R.id.nav_whatsapp)) }
        safe("Live setup") { live = LiveTab(this, tabViews.getValue(R.id.nav_live)) }
        safe("Vehicles setup") { vehicles = VehiclesTab(this, tabViews.getValue(R.id.nav_vehicles)) }
        safe("Rates setup") { rates = RatesTab(this, tabViews.getValue(R.id.nav_rates)) }
        safe("Mail setup") { mail = MailTab(this, tabViews.getValue(R.id.nav_mail)) }
        safe("Export setup") { export = ExportTab(this, tabViews.getValue(R.id.nav_export)) }
        safe("Plan setup") { plan = PlanTab(this, tabViews.getValue(R.id.nav_plan)) }
        safe("Drishti setup") { drishti = DrishtiAuto(this, window.decorView as android.view.ViewGroup) }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val parent = when (currentTab) {
                    R.id.nav_mail -> R.id.nav_export
                    R.id.nav_rates -> R.id.nav_vehicles
                    R.id.nav_live -> R.id.nav_today
                    else -> null
                }
                if (parent != null) { bottomNav.selectedItemId = parent; showTab(parent) }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })

        bottomNav.setOnItemReselectedListener { item -> showTab(item.itemId) }
        bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        // Live reading has no bottom-bar button; the "Live" chip at the top opens it.
        findViewById<View>(R.id.liveChip).setOnClickListener { showTab(R.id.nav_live) }
        val startTab = savedInstanceState?.getInt("tab") ?: R.id.nav_today
        if (bottomNav.menu.findItem(startTab) != null) bottomNav.selectedItemId = startTab
        showTab(startTab)
        CrashLog.takeSaved(this)?.let { showError(it) }
        handleShare(intent)
        if (savedInstanceState == null) showIntro()
        if (savedInstanceState == null) askDcNameOnce()
    }

    /** First start of an account: ask for the DC / warehouse name used in titles, reports and mail. */
    private fun askDcNameOnce() {
        val p = VehicleStore.prefs(this)
        if (p.getBoolean(Account.K_DC_ASKED, false) || Masters.dispatchName.isNotBlank()) return
        window.decorView.postDelayed({ if (!isFinishing) editDcName() }, 6000)
    }

    fun editDcName() {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(Masters.dispatchName); hint = getString(R.string.dc_name_hint); setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dc_name_title)
            .setMessage(R.string.dc_name_message)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                Account.setDcName(this, input.text?.toString().orEmpty())
                export?.updateAccount(); refreshAll(); showTab(bottomNav.selectedItemId)
            }
            .setNegativeButton(R.string.later) { _, _ -> VehicleStore.prefs(this).edit().putBoolean(Account.K_DC_ASKED, true).apply() }
            .show()
    }

    fun signOut() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sign_out)
            .setMessage(getString(R.string.sign_out_message, Account.email(this).ifBlank { Account.name(this) }))
            .setPositiveButton(R.string.sign_out) { _, _ ->
                Account.signOut(this)
                startActivity(Intent(this, SignInActivity::class.java)); finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Deletes the account, its cloud copy and its data on this phone (Google Play requires this in the app). */
    fun deleteAccount() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_account)
            .setMessage(R.string.delete_account_message)
            .setPositiveButton(R.string.delete_account_yes) { _, _ ->
                val wait = MaterialAlertDialogBuilder(this).setMessage(R.string.delete_account_working).setCancelable(false).show()
                CloudSync.deleteAll(this) { cloudOk ->
                    val user = try { if (Account.cloudReady(this)) com.google.firebase.auth.FirebaseAuth.getInstance().currentUser else null } catch (e: Exception) { null }
                    fun finishDelete() {
                        wait.dismiss()
                        Account.wipeLocal(this)
                        Account.signOut(this)
                        Toast.makeText(this, R.string.delete_account_done, Toast.LENGTH_LONG).show()
                        startActivity(Intent(this, SignInActivity::class.java)); finish()
                    }
                    if (user == null) { finishDelete(); return@deleteAll }
                    user.delete().addOnCompleteListener { t ->
                        if (t.isSuccessful && cloudOk) finishDelete()
                        else {
                            wait.dismiss()
                            // Firebase asks for a fresh sign-in before deleting an account signed in long ago.
                            MaterialAlertDialogBuilder(this).setTitle(R.string.delete_account)
                                .setMessage(R.string.delete_account_relogin)
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    Account.signOut(this); startActivity(Intent(this, SignInActivity::class.java)); finish()
                                }.show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Opening animation: truck drives to the DC, dock door rolls up, the DC team walks out (~5.5 s, tap to skip). */
    private fun showIntro() {
        try {
            addContentView(IntroView(this) {}, android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        } catch (e: Throwable) { }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Shared to this app: photos or the Excel file of the vehicle request sheet (Plan), or a WhatsApp chat export. */
    private fun handleShare(i: Intent?) {
        val act = i?.action
        if (act != Intent.ACTION_SEND && act != Intent.ACTION_SEND_MULTIPLE) return
        val type = i.type.orEmpty().lowercase()
        @Suppress("DEPRECATION")
        val streams: List<Uri> = if (act == Intent.ACTION_SEND_MULTIPLE) i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else listOfNotNull(i.getParcelableExtra(Intent.EXTRA_STREAM))
        val excel = type.contains("spreadsheet") || type.contains("ms-excel") ||
            streams.firstOrNull()?.let { u -> (u.lastPathSegment ?: "").lowercase().endsWith(".xlsx") } == true
        if (type.startsWith("image/") || excel) {
            i.action = null // handle once
            bottomNav.selectedItemId = R.id.nav_plan
            showTab(R.id.nav_plan)
            if (excel) streams.firstOrNull()?.let { plan?.readExcel(it) } else plan?.readPhotos(streams)
            return
        }
        if (act != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: run {
            Toast.makeText(this, R.string.chat_import_need_file, Toast.LENGTH_LONG).show()
            return
        }
        i.action = null // handle once
        bottomNav.selectedItemId = R.id.nav_live
        showTab(R.id.nav_live)
        ChatImporter.start(this, uri, i.getStringExtra(Intent.EXTRA_SUBJECT) ?: i.getStringExtra(Intent.EXTRA_TITLE))
    }

    private val chatPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ChatImporter.start(this, uri, null)
    }

    fun pickChatExport() = chatPicker.launch(arrayOf("text/plain", "application/zip", "application/x-zip-compressed", "*/*"))

    // ---------- Plan: photos or Excel of the vehicle request sheet ----------

    private val planGallery = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) plan?.readPhotos(uris)
    }
    private var planShot: Uri? = null
    private val planCamera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = planShot
        if (ok && u != null) plan?.readPhotos(listOf(u))
    }
    private val planCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) takePlanPhoto() else Toast.makeText(this, R.string.plan_camera_needed, Toast.LENGTH_LONG).show()
    }
    private val planExcel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) plan?.readExcel(uri)
    }

    fun addPlanPhotos() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.plan_photo_title)
            .setItems(arrayOf(getString(R.string.plan_photo_take), getString(R.string.plan_photo_pick))) { _, which ->
                if (which == 0) {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED) takePlanPhoto()
                    else planCameraPermission.launch(android.Manifest.permission.CAMERA)
                } else {
                    planGallery.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
            .show()
    }

    private fun takePlanPhoto() {
        val f = java.io.File(cacheDir, "reports/plan_${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        val u = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        planShot = u
        planCamera.launch(u)
    }

    fun pickPlanExcel() = planExcel.launch(arrayOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "application/octet-stream", "*/*"))

    fun showTodayTab() {
        bottomNav.selectedItemId = R.id.nav_today
        showTab(R.id.nav_today)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", bottomNav.selectedItemId)
    }

    override fun onStart() {
        super.onStart()
        AppLock.checkOnStart(this)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) AppLock.onBackground()
    }

    override fun onResume() {
        super.onResume()
        if (::today.isInitialized) today.releaseOrder()
        VehicleStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        if (hasNotificationAccess()) {
            // Some phones (Xiaomi, Oppo, Vivo...) disconnect the listener after an update; ask to reconnect.
            try {
                NotificationListenerService.requestRebind(ComponentName(this, WhatsAppNotificationListener::class.java))
            } catch (e: Exception) { }
        }
        refreshAll()
        safe("Drishti") { drishti?.resume() }
    }

    override fun onPause() {
        super.onPause()
        drishti?.pause()
        CloudSync.flushNow(this)
        VehicleStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    // ---------- Tabs ----------

    /** DMart Drishti: sign in, read the gate's vehicle log (check-in / check-out). */
    fun openDrishti() {
        startActivity(Intent(this, DrishtiActivity::class.java))
    }

    /** Keeps Today's rows in place while a vehicle is marked from its details. */
    fun holdTodayOrder() { if (::today.isInitialized) today.holdOrder() }

    fun showTab(id: Int) {
        currentTab = id
        if (id == R.id.nav_today && ::today.isInitialized) today.releaseOrder()
        if (id == R.id.nav_plan) safe("Plan") { plan?.refresh() }
        tabViews.forEach { (navId, view) -> view.visibility = if (navId == id) View.VISIBLE else View.GONE }
        titleText.setText(
            when (id) {
                R.id.nav_whatsapp -> R.string.tab_whatsapp
                R.id.nav_vehicles -> R.string.tab_vehicles
                R.id.nav_live -> R.string.tab_live
                R.id.nav_rates -> R.string.tab_rates
                R.id.nav_mail -> R.string.tab_mail
                R.id.nav_export -> R.string.tab_export
                R.id.nav_plan -> R.string.tab_plan
                else -> R.string.dc_title
            }
        )
        if (id == R.id.nav_today && Masters.dispatchName.isNotBlank()) titleText.text = Masters.dispatchName
    }

    fun loadSnapshot() = Snapshot(
        vehicles = VehicleStore.getVehicles(this),
        records = VehicleStore.getAll(this),
        unmatched = VehicleStore.getUnmatched(this),
        hasAccess = hasNotificationAccess()
    )

    private var errorShown = false

    /** Runs [block]; on failure keeps the app open and shows the error so it can be shared. */
    private inline fun safe(where: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            CrashLog.save(this, where, e)
            if (!errorShown) CrashLog.takeSaved(this)?.let { showError(it) }
        }
    }

    private fun showPlainError(text: String) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        box.addView(TextView(this).apply {
            setText(R.string.error_title); textSize = 20f
        })
        box.addView(android.widget.Button(this).apply {
            setText(R.string.error_share)
            setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                }, getString(R.string.error_share)))
            }
        })
        box.addView(TextView(this).apply { this.text = text; setTextIsSelectable(true) })
        setContentView(android.widget.ScrollView(this).apply { addView(box) })
    }

    private fun showError(text: String) {
        errorShown = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(getString(R.string.error_message) + "\n\n" + text.take(1500))
            .setPositiveButton(R.string.error_share) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(send, getString(R.string.error_share)))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    fun refreshAll() {
        var s = try { loadSnapshot() } catch (e: Throwable) {
            safe("Load data") { throw e }
            return
        }
        // Driver mobile, driver name, vendor, size and store written in WhatsApp fill empty vehicle details.
        safe("Capture details") {
            if (VehicleStore.fillBlanksFromPosts(this, s.records) > 0) s = s.copy(vehicles = VehicleStore.getVehicles(this))
        }
        subtitleText.text = SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(Date())
        liveText.setText(if (s.hasAccess) R.string.live else R.string.off)
        liveDot.backgroundTintList = ColorStateList.valueOf(
            if (s.hasAccess) 0xFF19B26B.toInt() else 0xFFE8772E.toInt()
        )
        liveRing.backgroundTintList = ColorStateList.valueOf(
            if (s.hasAccess) 0xFFD4F5E3.toInt() else 0xFFFFE9DC.toInt()
        )
        safe("Today") { today.refresh(s) }
        safe("WhatsApp") { whatsapp.refresh(s) }
        safe("Vehicles") { vehicles.refresh(s) }
        safe("Live") { live.refresh(s) }
        safe("Rates") { if (::rates.isInitialized) rates.refresh() }
        safe("Mail") { if (::mail.isInitialized) mail.refresh() }

        val unmatchedToday = s.unmatched.count { it.time >= ReportBuilder.startOfDay() }
        if (unmatchedToday > 0) {
            bottomNav.getOrCreateBadge(R.id.nav_whatsapp).number = unmatchedToday
        } else {
            bottomNav.removeBadge(R.id.nav_whatsapp)
        }
    }

    // ---------- Notification access ----------

    private fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    fun openAccessSettings() {
        Disclosure.notifications(this) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    // ---------- Import ----------

    fun pickImportFile() = importLauncher.launch(arrayOf("*/*"))

    private val rateLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importRates(uri)
    }

    fun pickRateFile() = rateLauncher.launch(arrayOf("*/*"))

    private fun importRates(uri: Uri) {
        val card = try {
            RateCard.fromFile(contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.import_failed, e.message), Toast.LENGTH_LONG).show()
            return
        }
        if (card.rates.isEmpty()) {
            MaterialAlertDialogBuilder(this).setMessage(R.string.rates_none).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val stores = card.rates.map { RateCard.cleanCode(it.storeCode) }.distinct().size
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tab_rates)
            .setMessage(getString(R.string.rates_found, card.rates.size, stores))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                RateCard.save(this, card)
                Toast.makeText(this, getString(R.string.rates_saved, card.rates.size), Toast.LENGTH_SHORT).show()
                refreshAll()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val mastersLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importMasters(uri)
    }

    fun pickMastersFile() = mastersLauncher.launch(arrayOf("*/*"))

    private fun importMasters(uri: Uri) {
        val found = try {
            MasterData.fromWorkbook(contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.import_failed, e.message), Toast.LENGTH_LONG).show()
            return
        }
        if (found.stores.isEmpty() && found.vendors.isEmpty()) {
            Toast.makeText(this, R.string.masters_none, Toast.LENGTH_LONG).show()
            return
        }
        val old = MasterData.get(this)
        // A file with only one of the two lists keeps the other one.
        val merged = Masters(found.stores.ifEmpty { old.stores }, found.vendors.ifEmpty { old.vendors })
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.masters_title)
            .setMessage(getString(R.string.masters_found, found.stores.size, found.vendors.size, found.vendors.count { it.code.isNotBlank() }))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                MasterData.save(this, merged)
                Toast.makeText(this, R.string.masters_saved, Toast.LENGTH_SHORT).show()
                refreshAll()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importFile(uri: Uri) {
        val list = try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            VehicleListImporter.read(bytes)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.import_failed, e.message), Toast.LENGTH_LONG).show()
            return
        }
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.import_none, Toast.LENGTH_LONG).show()
            return
        }
        // Which list: for reporting (tracked daily) or dedicated storage only (never Pending).
        val name = (try { contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null
        } } catch (e: Exception) { null }).orEmpty()
        var tracked = !name.contains("dedicated", ignoreCase = true)
        val kinds = arrayOf(getString(R.string.import_as_reporting), getString(R.string.import_as_dedicated))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.import_as_title, list.size))
            .setSingleChoiceItems(kinds, if (tracked) 0 else 1) { _, which -> tracked = which == 0 }
            .setPositiveButton(R.string.import_add) { _, _ ->
                val added = VehicleStore.importAs(this, list, tracked, replace = false)
                Toast.makeText(this, getString(R.string.import_done, added, list.size - added), Toast.LENGTH_LONG).show()
            }
            .setNeutralButton(R.string.import_replace_kind) { _, _ ->
                VehicleStore.importAs(this, list, tracked, replace = true)
                Toast.makeText(this, getString(R.string.import_replaced_kind,
                    getString(if (tracked) R.string.kind_reporting else R.string.kind_dedicated), list.size), Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
