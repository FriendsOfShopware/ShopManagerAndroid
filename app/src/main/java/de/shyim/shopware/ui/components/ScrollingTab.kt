package de.shyim.shopware.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Width-capped scrolling column shared by the snapshot-backed tabs.
// Pass [onRefresh] to enable slide-down-to-refresh; [isRefreshing] drives the
// spinner (bind it to the shop's SyncState). Omit both for a plain scroller.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrollingTab(
    maxWidth: Dp = 560.dp,
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onRefresh == null) {
        Box(modifier = Modifier.fillMaxSize()) {
            ScrollColumn(maxWidth, content)
        }
        return
    }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = rememberPullToRefreshState(),
        modifier = Modifier.fillMaxSize(),
    ) {
        // The indicator is centered by PullToRefreshBox; the scroller fills the box.
        ScrollColumn(maxWidth, content)
    }
}

@Composable
private fun ScrollColumn(maxWidth: Dp, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 96.dp),
            content = content,
        )
    }
}
