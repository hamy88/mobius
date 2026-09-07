package com.mobius.momo.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable

@Composable
expect fun BoxScope.VerticalScrollbar(listState: LazyListState)
