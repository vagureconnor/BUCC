package app.bucc.ui.screens.libbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.bucc.ui.icons.FeatureIcon
import app.bucc.ui.screens.bykc.BykcFeatureCard

@Composable
fun LibBookHomeScreen(
    onReserveClick: () -> Unit,
    onBookingsClick: () -> Unit,
    onSeminarClick: () -> Unit,
    onScanClick: (() -> Unit)? = null,
) {
  Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.weight(1f),
    ) {
      item {
        BykcFeatureCard(
            title = "预约座位",
            description = "选择楼馆、分区和座位",
            icon = FeatureIcon.LIBRARY_SEAT.vector,
            onClick = onReserveClick,
        )
      }
      item {
        BykcFeatureCard(
            title = "研讨间预约",
            description = "查看开放时间与可预约时段",
            icon = FeatureIcon.LIBRARY_SEMINAR.vector,
            onClick = onSeminarClick,
        )
      }
      if (onScanClick != null)
          item {
            BykcFeatureCard(
                title = "扫码预约",
                description = "扫描图书馆座位二维码",
                icon = FeatureIcon.LIBRARY_SCAN.vector,
                onClick = onScanClick,
            )
          }
      item {
        BykcFeatureCard(
            title = "我的预约",
            description = "查看座位预约与取消",
            icon = FeatureIcon.LIBRARY_BOOKINGS.vector,
            onClick = onBookingsClick,
        )
      }
    }
  }
}
