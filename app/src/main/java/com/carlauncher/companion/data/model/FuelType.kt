package com.carlauncher.companion.data.model

import androidx.annotation.StringRes
import com.carlauncher.companion.R

/**
 * Fuel types supported by both the French data.gouv.fr dataset and the Swiss TCS API.
 *
 * @property code          Column-name prefix in the French SQLite DB (e.g. "gazole" → "gazole_prix").
 *                         Empty string for TCS-only fuels that have no French DB column.
 * @property canonicalName Display name for UI labels.
 * @property labelRes      String resource for the filter dropdown.
 * @property tcsCode       Fuel code sent to the Swiss TCS benzinGetStationByBbox API.
 *                         Null for fuels that have no TCS equivalent (e.g. E10).
 * @property hasOfficialDbColumn True only for fuels that have a dedicated price column in
 *                         the French gas_stations SQLite DB. Use this to guard the WHERE
 *                         clause in [com.carlauncher.companion.data.db.GasStationDatabase].
 */
enum class FuelType(
    val code: String,
    val canonicalName: String,
    @get:StringRes val labelRes: Int,
    val tcsCode: String? = null,
    val hasOfficialDbColumn: Boolean = false,
) {
    // ── French + Swiss fuels ──────────────────────────────────────────────────
    GAZOLE("gazole", "Gazole", R.string.fuel_gazole, tcsCode = "DIESEL", hasOfficialDbColumn = true),
    SP95("sp95", "SP95", R.string.fuel_sp95, tcsCode = "SP95", hasOfficialDbColumn = true),
    SP98("sp98", "SP98", R.string.fuel_sp98, tcsCode = "SP98", hasOfficialDbColumn = true),
    E10("e10", "E10", R.string.fuel_e10, tcsCode = null, hasOfficialDbColumn = true),
    E85("e85", "E85", R.string.fuel_e85, tcsCode = "E85", hasOfficialDbColumn = true),
    GPLC("gplc", "GPLc", R.string.fuel_gplc, tcsCode = "GPL", hasOfficialDbColumn = true),

    // ── Swiss TCS-only fuels (no French DB column) ────────────────────────────
    DIESEL_PREMIUM("", "Diesel Premium", R.string.fuel_diesel_premium, tcsCode = "DIESEL_PREMIUM"),
    ADBLUE("", "AdBlue", R.string.fuel_adblue, tcsCode = "ADBLUE"),
    CNG("", "CNG", R.string.fuel_cng, tcsCode = "CNG"),
    HVO100("", "HVO100", R.string.fuel_hvo100, tcsCode = "HVO100"),
    H2("", "H₂", R.string.fuel_h2, tcsCode = "H2");

    companion object {
        fun fromCode(code: String): FuelType? =
            entries.firstOrNull { it.code.isNotEmpty() && it.code.equals(code, ignoreCase = true) }

        fun fromName(name: String): FuelType? {
            val normalized = name.trim().lowercase()
            return entries.firstOrNull {
                (it.code.isNotEmpty() && it.code.equals(normalized, ignoreCase = true)) ||
                    it.canonicalName.equals(name.trim(), ignoreCase = true)
            }
        }

        /** Fuels with a TCS code, in their API order. Used to build the Swiss fuel filter dropdown. */
        val swissSupported: List<FuelType> get() = entries.filter { it.tcsCode != null }
    }
}
