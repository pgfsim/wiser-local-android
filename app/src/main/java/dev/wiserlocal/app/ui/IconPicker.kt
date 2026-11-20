package dev.wiserlocal.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The drawable for a hub IconId, or [fallback] for one this app has no icon for. */
@DrawableRes
fun momentIcon(iconId: Int, @DrawableRes fallback: Int): Int = MOMENT_ICONS[iconId]?.drawable ?: fallback

/** The icon at the start of the Moment and automation editors; tap it to choose another. */
@Composable
fun IconChooser(iconId: Int, @DrawableRes fallback: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    val dialogs = LocalDialogs.current
    val name = MOMENT_ICONS[iconId]?.name ?: "No icon"
    Box(
        Modifier
            .size(56.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Choose an icon") {
                dialogs.icons(iconId, onPick)
            }
            .semantics { contentDescription = "Icon: $name" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(momentIcon(iconId, fallback)),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(28.dp),
        )
    }
}

/** A grid of every icon in [MOMENT_ICONS]; picking one closes the dialog. */
@OptIn(ExperimentalLayoutApi::class)
fun DialogHost.icons(selected: Int, onPick: (Int) -> Unit) = show {
    val scroll = rememberScrollState()
    var selectedTop by remember { mutableIntStateOf(-1) }
    // Open with the current icon in view; most of the list is below the fold.
    LaunchedEffect(selectedTop) {
        if (selectedTop > 0) scroll.scrollTo((selectedTop - scroll.viewportSize / 3).coerceAtLeast(0))
    }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text("Choose an icon") },
        text = {
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MOMENT_ICONS.forEach { (id, icon) ->
                    val chosen = id == selected
                    Box(
                        Modifier
                            .size(48.dp)
                            .then(
                                if (chosen) {
                                    Modifier
                                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                                } else Modifier
                            )
                            .then(
                                if (chosen) Modifier.onGloballyPositioned { selectedTop = it.positionInParent().y.toInt() }
                                else Modifier
                            )
                            .clickable(role = Role.Button) { close(); onPick(id) }
                            .semantics {
                                contentDescription = icon.name
                                this.selected = chosen
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(icon.drawable),
                            contentDescription = null,
                            tint = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = ::close) { Text("Cancel") } },
    )
}
