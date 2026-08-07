package com.carlauncher.companion.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlauncher.companion.R
import com.carlauncher.companion.ui.theme.neonBorder

/** Decodes a photo saved by [com.carlauncher.companion.data.repo.PlatformFileStore], downsampled to [targetWidthPx], or a placeholder icon if there's none. */
@Composable
fun CarPhoto(photoPath: String?, modifier: Modifier = Modifier, targetWidthPx: Int = 256) {
    val bitmap = remember(photoPath) {
        photoPath?.let { path ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sampleSize = 1
            while (bounds.outWidth / (sampleSize * 2) >= targetWidthPx) sampleSize *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        }
    }
    CarPhotoBox(bitmap, modifier)
}

/** Same placeholder/scaling treatment as the path-based overload above, for a shared car's
 * photo fetched over the network ([com.carlauncher.companion.ui.feed.SharedCarDetailScreen])
 * rather than read from local disk — already downsized server-side, so no sampling needed here. */
@Composable
fun CarPhoto(bytes: ByteArray?, modifier: Modifier = Modifier) {
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    CarPhotoBox(bitmap, modifier)
}

/** [IconBadge]'s exact shape/border treatment, with a cropped photo standing in for the icon
 * when [bytes] is present — the compact list-row look ([FeedScreen]'s car activity cards),
 * as opposed to the other two overloads' full-width detail-screen hero. Falls back to the same
 * [Icons.Filled.DirectionsCar] placeholder [IconBadge] itself would show, so a car without a
 * photo looks identical to how every other badge in the app looks without one. */
@Composable
fun CarPhotoBadge(bytes: ByteArray?, tint: Color, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    val shape = RoundedCornerShape(size * 0.32f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(tint.copy(alpha = 0.14f), shape)
            .neonBorder(tint, shape, alpha = 0.45f),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.car_photo_content_description),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
        }
    }
}

@Composable
private fun CarPhotoBox(bitmap: Bitmap?, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.car_photo_content_description),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Filled.DirectionsCar,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
