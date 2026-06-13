package com.kancy.display_test

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kancy.display_test.ui.theme.OutlineVariantDark
import com.kancy.display_test.ui.theme.StatusOk
import com.kancy.display_test.ui.theme.SurfaceContainer
import com.kancy.display_test.ui.theme.SurfaceContainerHigh

/** A rounded surface-container card with an optional uppercase section title (".card" in mockup). */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    container: Color = SurfaceContainer,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = container,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (title != null) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            content()
        }
    }
}

/** A label/value row with an underline divider, matching the read-only info rows in the mockup. */
@Composable
fun InfoRow(label: String, value: String, divider: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
    if (divider) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .size(1.dp)
                .background(OutlineVariantDark)
        )
    }
}

/** A small status dot. */
@Composable
fun StatusDot(color: Color, size: Int = 8) {
    Box(modifier = Modifier.size(size.dp).clip(CircleShape).background(color))
}

enum class StepState { Ok, Active, Pending }

/** A Mission-Control state-machine list item with a leading state icon. */
@Composable
fun StateListItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: String? = null,
    state: StepState = StepState.Pending,
    topDivider: Boolean = false,
) {
    if (topDivider) {
        Box(modifier = Modifier.fillMaxWidth().size(1.dp).background(OutlineVariantDark))
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val (bg, fg) = when (state) {
            StepState.Ok -> StatusOk.copy(alpha = 0.18f) to StatusOk
            StepState.Active -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
            StepState.Pending -> SurfaceContainerHigh to MaterialTheme.colorScheme.outline
        }
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A compact rounded pill used in immersive overlays. */
@Composable
fun Pill(text: String, dotColor: Color? = null, container: Color = SurfaceContainerHigh) {
    Surface(shape = RoundedCornerShape(999.dp), color = container) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (dotColor != null) StatusDot(dotColor, size = 7)
            Text(text, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
