package dev.wiserlocal.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wiserlocal.app.R

val CardShape = RoundedCornerShape(20.dp)

@Composable fun flameIcon(): Painter = painterResource(R.drawable.ic_flame)
@Composable fun waterIcon(): Painter = painterResource(R.drawable.ic_water)

/** A screen opened on top of the tabs, with a back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    title: String,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    loading: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = actions,
                )
                LoadingBar(loading)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = bottomBar,
        content = content,
    )
}

/** A thin bar under the top bar while the hub is being talked to; keeps its height so nothing jumps. */
@Composable
fun LoadingBar(loading: Boolean) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 0.dp)) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        else Spacer(Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

/** A scrolling column of settings rows with page padding. */
@Composable
fun SettingsColumn(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** A rounded group of rows, with an optional heading above it. */
@Composable
fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        if (title != null) SectionTitle(title)
        Card(
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/** A row with a label and value or description; tappable when [onClick] is set. */
@Composable
fun InfoRow(
    title: String,
    detail: String? = null,
    icon: Painter? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    showChevron: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    /** For destructive rows (delete): the title in this colour. */
    titleColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (!detail.isNullOrEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
        if (showChevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SwitchRow(title: String, detail: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    InfoRow(
        title,
        detail,
        trailing = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        onClick = if (enabled) ({ onChange(!checked) }) else null,
    )
}

@Composable
fun RowDivider() = HorizontalDivider(
    Modifier.padding(horizontal = 16.dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
)

/** Shown in place of content when the hub could not be reached or is not set up. */
@Composable
fun ProblemCard(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (action != null && onAction != null) {
                androidx.compose.material3.TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}
