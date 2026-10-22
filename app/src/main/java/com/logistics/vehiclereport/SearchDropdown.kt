package com.logistics.vehiclereport

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView

/**
 * Type-to-search dropdowns for the vehicle form (vendor, vehicle type, store / route).
 * Tapping the field shows the whole list; typing narrows it ("22" finds "22 FT", "kohi" finds "Kohinoor Cargo",
 * "5102" finds the store with that code). Anything not in the list can still be typed in.
 */
object SearchDropdown {

    /** [value] is what goes into the field; [label] / [sub] are what the list shows. */
    data class Option(val value: String, val label: String = value, val sub: String = "")

    /** Turns [field] (inside [box]) into a search dropdown with [options]. */
    fun attach(box: com.google.android.material.textfield.TextInputLayout, field: AutoCompleteTextView, options: List<Option>) {
        val adapter = Adapter(field.context, options)
        field.setAdapter(adapter)
        field.threshold = 1
        // Tapping a filled field selects its text, so typing searches afresh instead of editing the old value.
        field.setSelectAllOnFocus(true)
        // Show the whole list (not only what matches the current text) when the field is tapped.
        val openAll: () -> Unit = {
            if (field.isAttachedToWindow && options.isNotEmpty()) {
                adapter.filter.filter(null) { if (field.isAttachedToWindow && field.windowToken != null) field.showDropDown() }
            }
        }
        field.setOnClickListener { openAll() }
        // The first tap on a field only focuses it, so open the list on focus as well.
        field.setOnFocusChangeListener { _, has -> if (has) field.post { if (field.hasFocus()) openAll() } }
        box.setEndIconOnClickListener {
            if (field.isPopupShowing) field.dismissDropDown() else { field.requestFocus(); openAll() }
        }
    }

    /** Lower-case letters and digits only, so "22 ft", "22FT" and "22-Ft" compare equal. */
    private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private class Adapter(context: Context, private val all: List<Option>) : BaseAdapter(), Filterable {
        private val inflater = LayoutInflater.from(context)
        private var shown: List<Option> = all

        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: inflater.inflate(R.layout.item_dropdown, parent, false)
            val o = shown[position]
            v.findViewById<TextView>(R.id.dropTitle).text = o.label
            v.findViewById<TextView>(R.id.dropSub).apply {
                text = o.sub
                visibility = if (o.sub.isBlank()) View.GONE else View.VISIBLE
            }
            return v
        }

        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(q: CharSequence?): FilterResults {
                val query = q?.toString().orEmpty().trim()
                val list = if (query.isEmpty()) all else {
                    val words = query.lowercase().split(Regex("\\s+")).map { squash(it) }.filter { it.isNotEmpty() }
                    val whole = squash(query)
                    all.filter { o ->
                        val hay = squash(o.label + " " + o.sub + " " + o.value)
                        hay.contains(whole) || words.all { hay.contains(it) }
                    }.sortedBy { o ->
                        // Names that start with what was typed come first.
                        if (squash(o.label).startsWith(whole) || squash(o.value).startsWith(whole)) 0 else 1
                    }
                }
                return FilterResults().apply { values = list; count = list.size }
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(q: CharSequence?, r: FilterResults?) {
                shown = (r?.values as? List<Option>) ?: all
                notifyDataSetChanged()
            }

            override fun convertResultToString(resultValue: Any?): CharSequence =
                (resultValue as? Option)?.value ?: ""
        }
    }
}
