package com.carlauncher.companion.data.model

import java.util.Locale

/**
 * Represents a service station in France with coordinates, address, and live fuel prices.
 */
data class GasStation(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val address: String,
    val city: String,
    val postalCode: String,
    val pop: String?, // "R" = Route, "A" = Autoroute
    val automate24: Boolean,
    val prices: Map<FuelType, Double>,
    val availableFuels: List<String>,
    val lastUpdate: String?,
) {
    val isHighway: Boolean
        get() = pop.equals("A", ignoreCase = true)

    val title: String
        get() = buildString {
            if (isHighway) {
                append("Station Autoroute")
            } else {
                append("Station-service")
            }
            if (city.isNotBlank()) {
                append(" · ").append(city)
            }
        }

    val subtitle: String
        get() = buildString {
            if (address.isNotBlank()) {
                append(address)
            }
            if (postalCode.isNotBlank() || city.isNotBlank()) {
                if (isNotEmpty()) append(", ")
                if (postalCode.isNotBlank()) append(postalCode).append(" ")
                append(city)
            }
        }

    val shortName: String
        get() {
            if (isHighway && address.contains("aire", ignoreCase = true)) {
                val idx = address.indexOf("aire", ignoreCase = true)
                return address.substring(idx).trim()
            }
            val trimmed = address.trim()
            if (trimmed.isNotEmpty()) {
                return trimmed
            }
            return if (isHighway) "Station Autoroute" else "Station-service"
        }

    fun priceFor(fuelType: FuelType?): Double? {
        val target = fuelType ?: FuelType.GAZOLE
        return prices[target]
    }

    fun formattedPrice(fuelType: FuelType?): String? {
        val price = priceFor(fuelType) ?: return null
        return String.format(Locale.US, "%.3f €", price)
    }

    /**
     * Builds an HTML snippet suitable for [com.carlauncher.companion.ui.map.NeonInfoWindow],
     * displaying fuel prices in a neat grid and highlighting [highlightFuel] if specified.
     */
    fun buildPricesSnippetHtml(highlightFuel: FuelType? = null): String {
        if (prices.isEmpty()) {
            return "<i>Aucun prix disponible</i>"
        }
        val items = prices.entries.sortedBy { it.key.ordinal }.map { (type, price) ->
            val formattedPrice = String.format(Locale.US, "%.3f €", price)
            if (type == highlightFuel) {
                "<font color='#FFC93D'><b>${type.canonicalName}: $formattedPrice</b></font>"
            } else {
                "<b>${type.canonicalName}:</b> $formattedPrice"
            }
        }
        return items.chunked(2).joinToString("<br/>") { it.joinToString(" &nbsp;·&nbsp; ") }
    }

    /**
     * Builds the subdescription HTML with 24/24 automate status and last update date.
     */
    fun buildSubDescriptionHtml(): String {
        return buildString {
            append("24h/24 : ")
            append(if (automate24) "<b>Oui</b>" else "Non")
            if (!lastUpdate.isNullOrBlank()) {
                val cleanDate = lastUpdate.replace("T", " ").substringBefore("+").substringBefore("Z")
                append(" &nbsp;·&nbsp; MàJ : ").append(cleanDate)
            }
        }
    }
}
