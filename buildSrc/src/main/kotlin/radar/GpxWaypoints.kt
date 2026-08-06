package radar

import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/** A single GPX `<wpt>`. */
data class Waypoint(val lat: Double, val lon: Double, val name: String)

private const val SECTION_MARKER = "Troncon"

/**
 * Streams a radar GPX file and returns only the average-speed ("Troncon") waypoints.
 *
 * The bundled files are up to a few MB each with tens of thousands of waypoints, so this
 * uses StAX rather than building a DOM.
 */
fun parseSectionWaypoints(gpx: File): List<Waypoint> {
    val factory = XMLInputFactory.newInstance().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }

    val points = mutableListOf<Waypoint>()
    gpx.inputStream().buffered().use { stream ->
        val reader = factory.createXMLStreamReader(stream)
        var lat = 0.0
        var lon = 0.0
        var name = ""
        var insideWpt = false

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "wpt" -> {
                        insideWpt = true
                        lat = reader.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                        lon = reader.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                        name = ""
                    }
                    "name" -> if (insideWpt) name = reader.elementText.trim()
                }
                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "wpt") {
                    insideWpt = false
                    if (name.contains(SECTION_MARKER, ignoreCase = true)) {
                        points.add(Waypoint(lat, lon, name))
                    }
                }
            }
        }
        reader.close()
    }
    return points
}
