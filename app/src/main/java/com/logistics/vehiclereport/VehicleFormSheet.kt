package com.logistics.vehiclereport

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Bottom sheet to add or edit a dedicated vehicle and its details. */
object VehicleFormSheet {

    private val TYPES = listOf(
        "Tata Ace", "Bolero Pickup", "14 FT", "17 FT", "19 FT", "20 FT", "22 FT", "24 FT",
        "32 FT SXL", "32 FT MXL", "Trailer", "Container", "Other"
    )

    /** Vehicle types: the usual sizes, plus every type already used in your list or rate card. */
    fun typeOptions(ctx: android.content.Context): List<SearchDropdown.Option> {
        val seen = HashSet<String>()
        val out = ArrayList<SearchDropdown.Option>()
        fun add(t: String, sub: String) {
            val k = t.lowercase().filter { it.isLetterOrDigit() }
            if (t.isNotBlank() && seen.add(k)) out += SearchDropdown.Option(t.trim(), t.trim(), sub)
        }
        TYPES.forEach { add(it, "") }
        val rates = try { RateCard.get(ctx).rates } catch (e: Exception) { emptyList() }
        rates.map { it.vehicleType }.distinct().sorted().forEach { add(it, ctx.getString(R.string.opt_in_rate_card)) }
        VehicleStore.getVehicles(ctx).map { it.type }.distinct().sorted().forEach { add(it, ctx.getString(R.string.opt_in_list)) }
        return out
    }

    /** Vendors from the vendor list (with code), plus transporters already in your vehicle list. */
    fun vendorOptions(ctx: android.content.Context): List<SearchDropdown.Option> {
        val vehicles = VehicleStore.getVehicles(ctx)
        val counts = vehicles.filter { it.transporter.isNotBlank() }.groupingBy { it.transporter.trim().lowercase() }.eachCount()
        val seen = HashSet<String>()
        val out = ArrayList<SearchDropdown.Option>()
        val masters = try { MasterData.get(ctx).vendors } catch (e: Exception) { emptyList() }
        for (v in masters.sortedBy { it.name.lowercase() }) {
            if (v.name.isBlank() || !seen.add(v.name.trim().lowercase())) continue
            val n = counts[v.name.trim().lowercase()] ?: 0
            out += SearchDropdown.Option(v.name.trim(), v.name.trim(), Ui.joinNonBlank(
                if (v.code.isNotBlank()) ctx.getString(R.string.opt_code, v.code) else "",
                if (n > 0) ctx.resources.getQuantityString(R.plurals.opt_vehicles, n, n) else ""))
        }
        for ((name, n) in vehicles.map { it.transporter.trim() }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().entries.sortedBy { it.key.lowercase() }) {
            if (!seen.add(name.lowercase())) continue
            out += SearchDropdown.Option(name, name, ctx.resources.getQuantityString(R.plurals.opt_vehicles, n, n))
        }
        return out
    }

    /** Stores from the store list: "5102 Kurnool D-Mart", shown with city and region. */
    fun storeOptions(ctx: android.content.Context): List<SearchDropdown.Option> {
        val stores = try { MasterData.get(ctx).stores } catch (e: Exception) { emptyList() }
        return stores.map { st ->
            SearchDropdown.Option("${st.code} ${st.name}".trim(), Ui.joinNonBlank(st.code, st.name), Ui.joinNonBlank(st.city, st.region))
        }
    }

    /**
     * [endsWith]: optional last-4 hint shown when adding a vehicle from an unmatched WhatsApp code.
     * [suggest]: details seen in WhatsApp posts; they fill the fields that are still empty.
     */
    fun show(activity: AppCompatActivity, existing: DedicatedVehicle?, endsWith: String? = null, suggest: DedicatedVehicle? = null): BottomSheetDialog {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_vehicle_form, null)
        dialog.setContentView(view)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val title = view.findViewById<TextView>(R.id.formTitle)
        val hint = view.findViewById<TextView>(R.id.formHint)
        val numberLayout = view.findViewById<TextInputLayout>(R.id.numberLayout)
        val number = view.findViewById<TextInputEditText>(R.id.numberInput)
        val type = view.findViewById<AutoCompleteTextView>(R.id.typeInput)
        val transporter = view.findViewById<AutoCompleteTextView>(R.id.transporterInput)
        val driver = view.findViewById<TextInputEditText>(R.id.driverInput)
        val mobile = view.findViewById<TextInputEditText>(R.id.mobileInput)
        val route = view.findViewById<AutoCompleteTextView>(R.id.routeInput)
        val dedicated = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.dedicatedSwitch)
        val delete = view.findViewById<MaterialButton>(R.id.deleteButton)
        val save = view.findViewById<MaterialButton>(R.id.saveButton)

        SearchDropdown.attach(view.findViewById(R.id.typeLayout), type, typeOptions(activity))
        SearchDropdown.attach(view.findViewById(R.id.transporterLayout), transporter, vendorOptions(activity))
        SearchDropdown.attach(view.findViewById(R.id.routeLayout), route, storeOptions(activity))

        if (existing == null) {
            title.setText(R.string.add_vehicle)
            hint.setText(R.string.form_hint_new)
            delete.visibility = View.GONE
            if (endsWith != null) numberLayout.helperText = activity.getString(R.string.ends_with, endsWith)
            suggest?.let { if (it.number.isNotBlank()) number.setText(it.number) }
        } else {
            title.setText(R.string.edit_vehicle)
            number.setText(existing.number)
            number.isEnabled = false
            type.setText(existing.type, false)
            transporter.setText(existing.transporter, false)
            driver.setText(existing.driver)
            mobile.setText(existing.mobile)
            route.setText(existing.route, false)
            dedicated.isChecked = existing.dedicated
        }

        // Details seen in WhatsApp fill what is still empty; those fields say where the value came from.
        val boxes = listOf(
            Triple(view.findViewById<TextInputLayout>(R.id.typeLayout), type as android.widget.EditText, suggest?.type),
            Triple(view.findViewById<TextInputLayout>(R.id.transporterLayout), transporter, suggest?.transporter),
            Triple(view.findViewById<TextInputLayout>(R.id.driverLayout), driver, suggest?.driver),
            Triple(view.findViewById<TextInputLayout>(R.id.mobileLayout), mobile, suggest?.mobile),
            Triple(view.findViewById<TextInputLayout>(R.id.routeLayout), route, suggest?.route)
        )
        for ((box, field, value) in boxes) {
            if (field.text.isNullOrBlank() && !value.isNullOrBlank()) {
                if (field is AutoCompleteTextView) field.setText(value, false) else field.setText(value)
                box.helperText = activity.getString(R.string.from_whatsapp)
            }
        }
        val pending = boxes.count { it.second.text.isNullOrBlank() }
        if (existing != null) {
            hint.text = if (pending == 0) activity.getString(R.string.form_hint_complete)
                else activity.resources.getQuantityString(R.plurals.form_hint_pending, pending, pending)
            // Start on the first empty detail.
            boxes.firstOrNull { it.second.text.isNullOrBlank() }?.second?.let { f -> f.post { f.requestFocus() } }
        }

        save.setOnClickListener {
            val plate = existing?.number ?: VehicleParser.extract(number.text?.toString().orEmpty()).firstOrNull()
            if (plate == null) {
                numberLayout.error = activity.getString(R.string.no_plate_found)
                return@setOnClickListener
            }
            if (existing == null && VehicleStore.findVehicle(activity, plate) != null) {
                numberLayout.error = activity.getString(R.string.already_in_list, plate)
                return@setOnClickListener
            }
            if (endsWith != null && existing == null && VehicleParser.last4(plate) != endsWith) {
                Toast.makeText(activity, activity.getString(R.string.ends_differently, endsWith), Toast.LENGTH_LONG).show()
            }
            val v = DedicatedVehicle(
                number = plate,
                type = type.text?.toString()?.trim().orEmpty(),
                driver = driver.text?.toString()?.trim().orEmpty(),
                mobile = mobile.text?.toString()?.trim().orEmpty(),
                transporter = transporter.text?.toString()?.trim().orEmpty(),
                route = route.text?.toString()?.trim().orEmpty(),
                dedicated = dedicated.isChecked
            )
            val isNew = VehicleStore.saveVehicle(activity, v)
            Toast.makeText(
                activity,
                if (isNew) activity.getString(R.string.vehicle_added, plate) else activity.getString(R.string.saved),
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismiss()
        }

        delete.setOnClickListener {
            val v = existing ?: return@setOnClickListener
            MaterialAlertDialogBuilder(activity)
                .setTitle(v.number)
                .setMessage(R.string.remove_vehicle_message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    VehicleStore.removeVehicle(activity, v.number)
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        dialog.show()
        return dialog
    }
}
