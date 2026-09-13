package de.meegread.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.meegread.app.model.MeegRecording
import java.util.Locale

@Composable
fun BrainMapScreen(recording: MeegRecording?) {
    if(recording==null){ Column(Modifier.padding(16.dp)){ Text("Brain Map",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold); Text("Zuerst eine EEG/MEG-Aufnahme laden.") }; return }
    var mode by remember { mutableStateOf(BrainViewMode.TOPO_2D) }; var sample by remember(recording){ mutableFloatStateOf(0f) }; var rotation by remember { mutableFloatStateOf(0f) }
    val maxSample=(recording.sampleCount-1).coerceAtLeast(1).toFloat()
    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Brain Map",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){ if(mode==BrainViewMode.TOPO_2D) Button(onClick={mode=BrainViewMode.TOPO_2D}){Text("2D Topografie")} else OutlinedButton(onClick={mode=BrainViewMode.TOPO_2D}){Text("2D Topografie")}; if(mode==BrainViewMode.SPHERE_3D) Button(onClick={mode=BrainViewMode.SPHERE_3D}){Text("3D Sensoren")} else OutlinedButton(onClick={mode=BrainViewMode.SPHERE_3D}){Text("3D Sensoren")} }
        Text("Zeit ${String.format(Locale.US,"%.3f",sample/recording.sampleRateHz)} s · Sample ${sample.toInt()}"); Slider(value=sample,onValueChange={sample=it},valueRange=0f..maxSample)
        if(mode==BrainViewMode.SPHERE_3D){ Text("Rotation ${rotation.toInt()}°"); Slider(value=rotation,onValueChange={rotation=it},valueRange=-180f..180f) }
        BrainMapView(recording,sample.toInt(),mode,rotation,Modifier.fillMaxWidth())
        Text("Sensorpositionen aus FIFF werden verwendet; fehlende EEG-Koordinaten werden auf ein 10–20/10–10-Layout projiziert.",style=MaterialTheme.typography.bodySmall); Spacer(Modifier.height(80.dp))
    }
}
