package com.logistics.vehiclereport

/** The blank vehicle list the Vehicles tab hands out; its headers match what VehicleListImporter reads. */
object VehicleTemplate {

    const val FILE_NAME = "Vehicle_List_Template.xlsx"
    private const val EXAMPLE = "Example"

    val rows: List<List<String>> = listOf(
        listOf("S.No", "Vehicle No", "Transporter Name", "Vehicle Type"),
        listOf(EXAMPLE, "TS09UB1234", "Sri Balaji Transport", "32 FT MXL"),
        listOf("1", "", "", "")
    )

    val widths = listOf(9, 16, 26, 16)

    /** The example row is skipped on import so it never becomes a real vehicle. */
    fun isExampleRow(row: List<String>) = row.firstOrNull()?.trim().equals(EXAMPLE, ignoreCase = true)
}
