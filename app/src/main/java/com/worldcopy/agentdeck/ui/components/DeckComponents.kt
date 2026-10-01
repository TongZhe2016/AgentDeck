package com.worldcopy.agentdeck.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.worldcopy.agentdeck.R

enum class DeckIcon(@param:DrawableRes val resource: Int) {
    Folder(R.drawable.ic_deck_folder), Computer(R.drawable.ic_deck_computer), Key(R.drawable.ic_deck_key),
    Inbox(R.drawable.ic_deck_inbox), Sync(R.drawable.ic_deck_sync), More(R.drawable.ic_deck_more),
    Add(R.drawable.ic_deck_add), Chevron(R.drawable.ic_deck_chevron), Back(R.drawable.ic_deck_arrow),
    Chat(R.drawable.ic_deck_chat), Send(R.drawable.ic_deck_send), Upload(R.drawable.ic_deck_upload),
    Shield(R.drawable.ic_deck_shield), Check(R.drawable.ic_deck_check), Warning(R.drawable.ic_deck_warning), Search(R.drawable.ic_deck_search),
    Code(R.drawable.ic_deck_code), Branch(R.drawable.ic_deck_branch), Attachment(R.drawable.ic_deck_attachment), Close(R.drawable.ic_deck_close),
}

@Composable
fun DeckGlyph(icon: DeckIcon, description: String? = null, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(icon.resource), description, modifier.size(24.dp), tint = tint)
}

@Composable
fun IconTile(icon: DeckIcon, modifier: Modifier = Modifier) {
    Surface(modifier.size(44.dp), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Box(contentAlignment = Alignment.Center) { DeckGlyph(icon) }
    }
}

@Composable
fun PageHeading(title: String, subtitle: String, action: @Composable () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun EmptyState(icon: DeckIcon, title: String, description: String, action: @Composable () -> Unit = {}) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon)
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            action()
        }
    }
}

@Composable
fun StatusLabel(text: String, positive: Boolean = false, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.small,
        color = if (positive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (positive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun DeckChevron(expanded: Boolean) {
    DeckGlyph(DeckIcon.Chevron, modifier = Modifier.rotate(if (expanded) 0f else -90f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun QuietCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), content = content)
}
