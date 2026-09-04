package com.carlauncher.companion.data.model

import java.util.Locale

/** Identifies which national dataset a [GasStation] came from. */
enum class GasStationSource { FRANCE, SWITZERLAND }

/**
 * Confidence level of a gas station price, ranging from very recent to outdated.
 * Used for color-coding price freshness indicators in the UI.
 */
enum class PriceConfidenceLevel {
    /** Very recent: updated within the last 2 days, or Swiss CONFIDENT. */
    EXCELLENT,
    /** Recent: updated 2 to 3 days ago, or Swiss FEW_RECENT_PRICES. */
    GOOD,
    /** Moderate: updated 4 to 7 days ago. */
    MODERATE,
    /** Aging: updated 8 to 14 days ago, or Swiss OLD_LAST_UPDATE. */
    AGING,
    /** Outdated: updated more than 14 days ago, or Swiss OUTDATED_LAST_PRICE_UPDATE. */
    OUTDATED,
}


/**
 * Represents a service station with coordinates, address, and live fuel prices.
 * Covers both French stations (from data.gouv.fr, cached in SQLite) and Swiss stations
 * (from the TCS benzinGetStationByBbox API, fetched live per viewport).
 *
 * Swiss stations may be **clusters** ([isCluster] = true) — aggregated groups returned
 * by the TCS API at low zoom levels. Clusters carry a [pointCount] and a representative
 * [price] but no navigable address.
 */
data class GasStation(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val address: String,
    val city: String,
    val postalCode: String,
    val pop: String?, // "R" = Route, "A" = Autoroute (French only)
    val automate24: Boolean,
    val prices: Map<FuelType, Double>,
    val availableFuels: List<String>,
    val lastUpdate: String?,
    // ── Swiss / TCS fields ────────────────────────────────────────────────────
    val source: GasStationSource = GasStationSource.FRANCE,
    /** True for TCS cluster objects — multiple stations grouped at the current zoom level. */
    val isCluster: Boolean = false,
    /** Number of individual stations inside a cluster (0 for individual stations). */
    val pointCount: Int = 0,
    /** Brand name returned by TCS for individual stations (e.g. "ENI", "SIMOND"). */
    val brand: String? = null,
    /** Human-readable name for Swiss stations (TCS `displayName`). */
    val displayName: String? = null,
    /** Formatted address for Swiss individual stations (TCS `formattedAddress`). */
    val formattedAddress: String? = null,
    /** TCS data reliability indicator: "CONFIDENT", "FEW_RECENT_PRICES", "OLD_LAST_UPDATE", etc. */
    val fiability: String? = null,
    /** Whether this station/cluster has the lowest price in its area (TCS `isCheapest`). */
    val isCheapest: Boolean = false,
) {
    val isHighway: Boolean
        get() = pop.equals("A", ignoreCase = true)

    /**
     * Determines the confidence/freshness level of the station's fuel prices.
     * Evaluates against [now] for deterministic testing.
     */
    fun priceConfidenceAt(now: java.time.LocalDate = java.time.LocalDate.now()): PriceConfidenceLevel {
        if (source == GasStationSource.SWITZERLAND) {
            return when (fiability) {
                "CONFIDENT" -> PriceConfidenceLevel.EXCELLENT
                "FEW_RECENT_PRICES" -> PriceConfidenceLevel.GOOD
                "OLD_LAST_UPDATE" -> PriceConfidenceLevel.AGING
                "OUTDATED_LAST_PRICE_UPDATE" -> PriceConfidenceLevel.OUTDATED
                else -> PriceConfidenceLevel.MODERATE
            }
        }
        val update = lastUpdate
        if (update.isNullOrBlank()) return PriceConfidenceLevel.MODERATE
        return try {
            val dateStr = update.trim().substringBefore("T").substringBefore(" ")
            val date = java.time.LocalDate.parse(dateStr)
            val daysAgo = java.time.temporal.ChronoUnit.DAYS.between(date, now)
            when {
                daysAgo <= 1 -> PriceConfidenceLevel.EXCELLENT
                daysAgo in 2..3 -> PriceConfidenceLevel.GOOD
                daysAgo in 4..7 -> PriceConfidenceLevel.MODERATE
                daysAgo in 8..14 -> PriceConfidenceLevel.AGING
                else -> PriceConfidenceLevel.OUTDATED
            }
        } catch (_: Exception) {
            PriceConfidenceLevel.MODERATE
        }
    }

    val priceConfidence: PriceConfidenceLevel
        get() = priceConfidenceAt()


    val title: String
        get() = when {
            isCluster -> if (pointCount == 1) "1 station" else "$pointCount stations"
            source == GasStationSource.SWITZERLAND -> brand?.takeIf { it.isNotBlank() }
                ?: displayName?.takeIf { it.isNotBlank() }
                ?: "Station-service"
            isHighway -> buildString {
                append("Station Autoroute")
                if (city.isNotBlank()) append(" · ").append(city)
            }
            else -> buildString {
                append("Station-service")
                if (city.isNotBlank()) append(" · ").append(city)
            }
        }

    val subtitle: String
        get() = when {
            isCluster -> {
                val price = prices.values.firstOrNull()
                if (price == null) ""
                else if (source == GasStationSource.SWITZERLAND) {
                    String.format(Locale.US, "%.2f CHF", price)
                } else {
                    String.format(Locale.US, "%.3f €", price)
                }
            }
            source == GasStationSource.SWITZERLAND ->
                formattedAddress?.takeIf { it.isNotBlank() } ?: displayName ?: ""
            else -> buildString {
                if (address.isNotBlank()) append(address)
                if (postalCode.isNotBlank() || city.isNotBlank()) {
                    if (isNotEmpty()) append(", ")
                    if (postalCode.isNotBlank()) append(postalCode).append(" ")
                    append(city)
                }
            }
        }

    val shortName: String
        get() {
            if (isCluster) return if (pointCount == 1) "1 station" else "$pointCount stations"
            if (source == GasStationSource.SWITZERLAND) {
                return (brand?.takeIf { it.isNotBlank() }

                    ?: displayName?.takeIf { it.isNotBlank() }
                    ?: formattedAddress?.takeIf { it.isNotBlank() }
                    ?: "Station-service")
            }
            if (isHighway && address.contains("aire", ignoreCase = true)) {
                val idx = address.indexOf("aire", ignoreCase = true)
                return address.substring(idx).trim()
            }
            val trimmed = address.trim()
            if (trimmed.isNotEmpty()) return trimmed
            return if (isHighway) "Station Autoroute" else "Station-service"
        }

    fun priceFor(fuelType: FuelType?): Double? {
        val target = fuelType ?: FuelType.GAZOLE
        return prices[target]
    }

    fun formattedPrice(fuelType: FuelType?): String? {
        val price = priceFor(fuelType) ?: return null
        return if (source == GasStationSource.SWITZERLAND) {
            String.format(Locale.US, "%.2f CHF", price)
        } else {
            String.format(Locale.US, "%.3f €", price)
        }
    }


    /**
     * Builds an HTML snippet suitable for [com.carlauncher.companion.ui.map.NeonInfoWindow],
     * displaying fuel prices in a neat grid and highlighting [highlightFuel] if specified.
     */
    fun buildPricesSnippetHtml(highlightFuel: FuelType? = null): String {
        if (prices.isEmpty()) {
            return "<i>Aucun prix disponible</i>"
        }
        val (format, currency) = if (source == GasStationSource.SWITZERLAND) {
            "%.2f" to "CHF"
        } else {
            "%.3f" to "€"
        }
        val items = prices.entries.sortedBy { it.key.ordinal }.map { (type, price) ->
            val formattedPrice = String.format(Locale.US, "$format $currency", price)
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
     * For Swiss stations, shows the fiability indicator instead.
     */
    fun buildSubDescriptionHtml(): String {
        if (source == GasStationSource.SWITZERLAND) {
            return buildString {
                if (isCheapest) append("<b>✓ Moins cher</b> &nbsp;·&nbsp; ")
                if (!fiability.isNullOrBlank()) {
                    val label = when (fiability) {
                        "CONFIDENT" -> "Prix récents"
                        "FEW_RECENT_PRICES" -> "Peu de prix récents"
                        "OLD_LAST_UPDATE" -> "Prix anciens"
                        "OUTDATED_LAST_PRICE_UPDATE" -> "Prix obsolètes"
                        else -> fiability
                    }
                    append(label)
                }
            }
        }
        if (isCluster) {
            return "<i>Touchez à nouveau pour agrandir</i>"
        }
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


