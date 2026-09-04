package com.carlauncher.companion.ui.map

import android.content.Intent
import android.net.Uri
import android.text.Html
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.carlauncher.companion.R
import com.carlauncher.companion.data.model.GasStation
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.OverlayWithIW
import org.osmdroid.views.overlay.infowindow.InfoWindow

/**
 * Dark neon arcade themed InfoWindow matching the rest of the app's HUD cards.
 * Reusable for both [Marker] and [org.osmdroid.views.overlay.Polyline].
 */
class NeonInfoWindow(mapView: MapView) : InfoWindow(R.layout.view_neon_map_bubble, mapView) {

    init {
        mView?.setOnClickListener { close() }
    }

    override fun open(item: Any?, position: GeoPoint?, offsetX: Int, offsetY: Int) {
        closeAllInfoWindowsOn(mMapView)
        super.open(item, position, offsetX, offsetY)
    }

    override fun onOpen(item: Any?) {
        val overlay = item as? OverlayWithIW ?: return
        val titleView = mView?.findViewById<TextView>(R.id.bubble_title)
        val descriptionView = mView?.findViewById<TextView>(R.id.bubble_description)
        val subDescriptionView = mView?.findViewById<TextView>(R.id.bubble_subdescription)
        val imageView = mView?.findViewById<ImageView>(R.id.bubble_image)
        val navButton = mView?.findViewById<TextView>(R.id.bubble_navigate_button)

        val title = overlay.title
        if (!title.isNullOrEmpty()) {
            titleView?.text = title
            titleView?.visibility = View.VISIBLE
        } else {
            titleView?.visibility = View.GONE
        }

        val snippet = overlay.snippet
        if (!snippet.isNullOrEmpty()) {
            descriptionView?.text = Html.fromHtml(snippet, Html.FROM_HTML_MODE_LEGACY)
            descriptionView?.visibility = View.VISIBLE
        } else {
            descriptionView?.visibility = View.GONE
        }

        val subDescription = overlay.subDescription
        if (!subDescription.isNullOrEmpty()) {
            subDescriptionView?.text = Html.fromHtml(subDescription, Html.FROM_HTML_MODE_LEGACY)
            subDescriptionView?.visibility = View.VISIBLE
        } else {
            subDescriptionView?.visibility = View.GONE
        }

        if (overlay is Marker && overlay.image != null) {
            imageView?.setImageDrawable(overlay.image)
            imageView?.visibility = View.VISIBLE
        } else {
            imageView?.visibility = View.GONE
        }

        val station = (overlay as? Marker)?.relatedObject as? GasStation
        if (station != null && !station.isCluster) {
            navButton?.visibility = View.VISIBLE
            navButton?.setOnClickListener {
                close()
                val context = it.context
                val lat = station.lat
                val lon = station.lon
                val label = "${station.shortName}, ${station.city}".trim().removePrefix(",").trim()
                val uri = Uri.parse("geo:0,0?q=$lat,$lon(${Uri.encode(label)})")
                val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(mapIntent)
                } catch (_: Exception) {
                    val browserUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon")
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, browserUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Exception) {}
                }
            }
        } else {
            navButton?.visibility = View.GONE
            navButton?.setOnClickListener(null)
        }

    }

    override fun onClose() {
        // No-op
    }
}

