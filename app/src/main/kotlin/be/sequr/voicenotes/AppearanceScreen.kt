// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme

/** The Material colour scheme for [theme]: the wallpaper's colours for the first three, the fixed palettes for the others. */
fun colorSchemeFor(theme: AppTheme, systemDark: Boolean, context: Context): ColorScheme =
    theme.seeds(systemDark)?.let { derive(it).toColorScheme() }
        // minSdk is 34, so the wallpaper-based colours are always available.
        ?: if (theme.isDark(systemDark)) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

/** Choose the colour theme: one card per theme, each drawn in its own colours so the choice can be seen before it is made. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(current: AppTheme, onChoose: (AppTheme) -> Unit, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val systemDark = isSystemInDarkTheme()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Appearance") }, navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } }) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "The colours of the app. Follow the system keeps the colours from your wallpaper, as before; the others stay the same whatever your wallpaper or your phone's dark mode.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(AppTheme.entries) { theme ->
                ThemeCard(theme, selected = theme == current, scheme = colorSchemeFor(theme, systemDark, context), onClick = { onChoose(theme) })
            }
        }
    }
}

@Composable
private fun ThemeCard(theme: AppTheme, selected: Boolean, scheme: ColorScheme, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(theme.label, style = MaterialTheme.typography.titleMedium)
                Text(theme.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // A tiny note in the theme's own colours: a note card, a label chip and the record button.
                MaterialTheme(colorScheme = scheme) { Sample() }
            }
        }
    }
}

@Composable
private fun Sample() {
    Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp)) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Remind me to call the dentist", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
                            Text("errands", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Text("Default", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text("Failed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.size(28.dp), color = MaterialTheme.colorScheme.primary, shape = CircleShape) {}
            }
        }
    }
}
