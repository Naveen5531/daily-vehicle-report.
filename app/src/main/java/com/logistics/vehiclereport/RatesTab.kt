package com.logistics.vehiclereport

import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import android.text.Editable
import android.text.TextWatcher

/** Rates tab: the master rate card (store code + vehicle type -> cost). */
class RatesTab(private val a: MainActivity, root: View) {
    private val list: LinearLayout = root.findViewById(R.id.rateList)
    private val count: TextView = root.findViewById(R.id.rateCount)
    private val empty: TextView = root.findViewById(R.id.rateEmpty)
    private var query = ""

    init {
        root.findViewById<TextInputEditText>(R.id.rateSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim()?.lowercase().orEmpty(); refresh() }
        })
        root.findViewById<MaterialButton>(R.id.rateTemplate).setOnClickListener {
            Exporter.deliver(a, "Rate_Card_Template.xlsx", RateCard.templateRows(MasterData.get(a)), RateCard.TEMPLATE_WIDTHS)
        }
        root.findViewById<MaterialButton>(R.id.rateImport).setOnClickListener { a.pickRateFile() }
    }

    fun refresh() {
        val card = RateCard.get(a)
        val stores = MasterData.get(a).stores.associateBy { RateCard.cleanCode(it.code) }
        count.text = a.getString(R.string.rates_count, card.rates.size, card.rates.map { RateCard.cleanCode(it.storeCode) }.distinct().size)
        empty.visibility = if (card.rates.isEmpty()) View.VISIBLE else View.GONE
        list.removeAllViews()
        val shown = card.rates.filter { r ->
            val name = r.storeName.ifBlank { stores[RateCard.cleanCode(r.storeCode)]?.name.orEmpty() }
            query.isEmpty() || "${r.storeCode} $name ${r.vehicleType}".lowercase().contains(query)
        }.sortedWith(compareBy({ it.storeCode.toIntOrNull() ?: Int.MAX_VALUE }, { it.storeCode }, { it.vehicleType }))
        val d = a.resources.displayMetrics.density
        for (r in shown.take(200)) {
            val name = r.storeName.ifBlank { stores[RateCard.cleanCode(r.storeCode)]?.name.orEmpty() }
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            }
            row.addView(TextView(a).apply {
                text = Ui.joinNonBlank("${r.storeCode}  $name", r.vehicleType, sep = "\n")
                textSize = 13.5f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(a).apply {
                text = "₹ " + formatAmount(r.amount)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.color(a, R.color.brand_primary))
            })
            list.addView(row)
        }
        if (shown.size > 200) list.addView(TextView(a).apply {
            text = a.getString(R.string.rates_more, shown.size - 200); setPadding(0, (8 * d).toInt(), 0, 0)
        })
    }

    companion object {
        /** 12500 -> 12,500 (Indian grouping) */
        fun formatAmount(s: String): String {
            val v = s.toDoubleOrNull() ?: return s
            val f = java.text.NumberFormat.getNumberInstance(java.util.Locale("en", "IN"))
            f.maximumFractionDigits = 2
            return f.format(v)
        }
    }
}
