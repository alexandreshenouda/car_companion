package com.carlauncher.companion.data.model

import androidx.annotation.StringRes
import com.carlauncher.companion.R

@get:StringRes
val HistoryRange.labelRes: Int
    get() = when (this) {
        HistoryRange.TODAY -> R.string.history_range_today
        HistoryRange.LAST_7_DAYS -> R.string.history_range_7_days
        HistoryRange.LAST_30_DAYS -> R.string.history_range_30_days
        HistoryRange.ALL -> R.string.history_range_all
    }
