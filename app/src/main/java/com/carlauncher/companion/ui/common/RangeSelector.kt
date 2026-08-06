package com.carlauncher.companion.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.carlauncher.companion.data.model.HistoryRange
import com.carlauncher.companion.data.model.labelRes

@Composable
fun RangeSelector(
    selected: HistoryRange,
    onSelect: (HistoryRange) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    NeonSegmentedSelector(
        options = HistoryRange.entries,
        selected = selected,
        label = { stringResource(it.labelRes) },
        onSelect = onSelect,
        modifier = modifier,
    )
}
