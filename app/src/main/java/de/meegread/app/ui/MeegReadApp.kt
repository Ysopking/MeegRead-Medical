package de.meegread.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import de.meegread.app.data.ArchiveStore
import de.meegread.app.model.MeegRecording

private val MeegReadColors = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    secondary = Color(0xFFA7F3D0),
    background = Color(0xFF0B0E14),
    surface = Color(0xFF121722),
    surfaceVariant = Color(0xFF1A2230),
    onPrimary = Color(0xFF001F2A),
    onBackground = Color(0xFFE7EEF8),
    onSurface = Color(0xFFE7EEF8)
)

private enum class Section(val label: String) {
    ANALYSIS("Analyse"),
    MAP("Map"),
    ARCHIVE("Archiv"),
    LIVE("Live")
}

@Composable
fun MeegReadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MeegReadColors,
        content = content
    )
}

@Composable
fun MeegReadApp() {
    val context = LocalContext.current
    val archive = remember { ArchiveStore(context.applicationContext) }
    var recording by remember { mutableStateOf<MeegRecording?>(null) }
    var section by remember { mutableStateOf(Section.ANALYSIS) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Section.entries.forEach { item ->
                    NavigationBarItem(
                        selected = section == item,
                        onClick = { section = item },
                        icon = {
                            Text(
                                when (item) {
                                    Section.ANALYSIS -> "∿"
                                    Section.MAP -> "◉"
                                    Section.ARCHIVE -> "▣"
                                    Section.LIVE -> "⌁"
                                }
                            )
                        },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            when (section) {
                Section.ANALYSIS -> Column {
                    AnalysisScreen(recording) { recording = it }
                    recording?.let {
                        // v0.7 is the primary theory-aligned research surface.
                        CouplingFieldV07Section(it)

                        // Legacy/research comparison layers remain available during migration.
                        ThermodynamicLoadSection(it)
                        ResearchFriction19Section(it)
                        BrmhCohortSupportSection(it)
                    }
                }

                Section.MAP -> BrainMapScreen(recording)
                Section.ARCHIVE -> ArchiveScreen(archive, recording) {
                    recording = it
                    section = Section.ANALYSIS
                }

                Section.LIVE -> BleLiveScreen {
                    recording = it
                    section = Section.ANALYSIS
                }
            }
        }
    }
}
