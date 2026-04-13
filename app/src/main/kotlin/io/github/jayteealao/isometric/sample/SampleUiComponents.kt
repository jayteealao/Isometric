package io.github.jayteealao.isometric.sample

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun TogglePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        backgroundColor = if (selected) MaterialTheme.colors.primary.copy(alpha = 0.18f) else Color.Transparent,
        elevation = 0.dp,
        modifier = Modifier
            .padding(top = 2.dp)
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface,
            style = MaterialTheme.typography.body2,
        )
    }
}
