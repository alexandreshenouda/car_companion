package com.carlauncher.companion.data.model

import androidx.annotation.StringRes
import com.carlauncher.companion.R

/**
 * Fuel types available in the French government gas stations dataset.
 */
enum class FuelType(
    val code: String,
    val canonicalName: String,
    @get:StringRes val labelRes: Int,
) {
    GAZOLE("gazole", "Gazole", R.string.fuel_gazole),
    SP95("sp95", "SP95", R.string.fuel_sp95),
    SP98("sp98", "SP98", R.string.fuel_sp98),
    E10("e10", "E10", R.string.fuel_e10),
    E85("e85", "E85", R.string.fuel_e85),
    GPLC("gplc", "GPLc", R.string.fuel_gplc);

    companion object {
        fun fromCode(code: String): FuelType? =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) }

        fun fromName(name: String): FuelType? {
            val normalized = name.trim().lowercase()
            return entries.firstOrNull {
                it.code.equals(normalized, ignoreCase = true) ||
                    it.canonicalName.equals(name.trim(), ignoreCase = true)
            }
        }
    }
}
