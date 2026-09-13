package de.meegread.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.meegread.app.data.ArchiveStore
import de.meegread.app.model.MeegRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ArchiveScreen(store: ArchiveStore, currentRecording: MeegRecording?, onLoadRecording: (MeegRecording) -> Unit) {
    val scope = rememberCoroutineScope(); var refresh by remember { mutableIntStateOf(0) }; var pseudonym by remember { mutableStateOf("") }; var notes by remember { mutableStateOf("") }; var selectedSubjectId by remember { mutableStateOf<String?>(null) }; var status by remember { mutableStateOf("") }
    val subjects = remember(refresh) { store.listSubjects() }; if (selectedSubjectId == null && subjects.isNotEmpty()) selectedSubjectId = subjects.first().id
    val sessions = remember(refresh, selectedSubjectId) { selectedSubjectId?.let(store::listSessions).orEmpty() }
    val history = remember(refresh, selectedSubjectId) { selectedSubjectId?.let(store::listThermodynamicMetrics).orEmpty() }
    val metricsBySession = remember(history) { history.associateBy { it.sessionId } }
    Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Probanden & Sessions", style=MaterialTheme.typography.headlineMedium, fontWeight=FontWeight.Bold)
        Card { Column(Modifier.padding(12.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) { Text("Neuer Proband", fontWeight=FontWeight.Bold); OutlinedTextField(pseudonym,{pseudonym=it},label={Text("Pseudonym / Kennung")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(notes,{notes=it},label={Text("Notizen")},modifier=Modifier.fillMaxWidth()); Button(enabled=pseudonym.isNotBlank(),onClick={ val subject=store.createSubject(pseudonym,notes); selectedSubjectId=subject.id; pseudonym=""; notes=""; refresh++ }) { Text("Anlegen") } } }
        if (subjects.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) { subjects.forEach { s -> if (selectedSubjectId==s.id) Button(onClick={selectedSubjectId=s.id}){Text(s.pseudonym)} else OutlinedButton(onClick={selectedSubjectId=s.id}){Text(s.pseudonym)} } }
            subjects.firstOrNull { it.id==selectedSubjectId }?.let { subject -> Card { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(subject.pseudonym,fontWeight=FontWeight.Bold); if(subject.notes.isNotBlank()) Text(subject.notes)
                Button(enabled=currentRecording!=null,onClick={ val active=currentRecording ?: return@Button; scope.launch { runCatching { withContext(Dispatchers.IO){ store.saveSession(subject.id,active) } }.onSuccess { status="Session gespeichert"; refresh++ }.onFailure { status=it.message ?: "Speichern fehlgeschlagen" } } }) { Text("Aktuelle Aufnahme speichern") }
                if(history.isNotEmpty()) { val latest=history.first(); Text("MMSI-Forschungsverlauf",fontWeight=FontWeight.Bold); Text("Letztes Wraw ${fa(latest.finalRawLoad)} · Max ${fa(history.maxOf{it.maxRawLoad})} · Ω-Ereignisse ${history.count{it.omegaBreach}}",style=MaterialTheme.typography.bodySmall); if(history.size>1) SignalChart(history.asReversed().map{it.finalRawLoad},Modifier.fillMaxWidth()); Text("Session-Endwerte; keine automatische Kumulation zwischen getrennten Messungen.",style=MaterialTheme.typography.bodySmall) }
            } } }
        }
        sessions.forEach { session -> Card { Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(session.recordingName,fontWeight=FontWeight.Bold); Text("${session.modality} · ${session.channelCount} Ch · ${session.sampleRateHz.toInt()} Hz"); Text(DateFormat.getDateTimeInstance().format(Date(session.createdAtEpochMs)),style=MaterialTheme.typography.bodySmall); metricsBySession[session.id]?.let { m -> Text("MMSI Wraw ${fa(m.finalRawLoad)} / ${fa(m.omegaCrit)} · FAA μ ${fa(m.meanFaa)}${if(m.omegaBreach) " · Modellgrenze" else ""}",style=MaterialTheme.typography.bodySmall) } }; Column { OutlinedButton(onClick={ scope.launch { runCatching { withContext(Dispatchers.IO){store.loadSession(session.id)} }.onSuccess { onLoadRecording(it); status="Session geladen" } } }){Text("Laden")}; OutlinedButton(onClick={store.deleteSession(session.id);refresh++}){Text("Löschen")} } } } }
        if(status.isNotBlank()) Text(status,style=MaterialTheme.typography.bodySmall); Spacer(Modifier.height(80.dp))
    }
}
private fun fa(value: Double): String = String.format(Locale.US,"%.3f",value)
