package de.meegread.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.meegread.app.ble.BleEegManager
import de.meegread.app.ble.BleEegProfile
import de.meegread.app.ble.BleSampleFormat
import de.meegread.app.model.MeegRecording
import java.util.UUID

@Composable
fun BleLiveScreen(onUseRecording: (MeegRecording) -> Unit) {
    val context=LocalContext.current; val manager=remember{BleEegManager(context)}; val state by manager.state.collectAsState()
    var serviceUuid by remember{mutableStateOf("")}; var characteristicUuid by remember{mutableStateOf("")}; var channelNames by remember{mutableStateOf("AF7,AF8,TP9,TP10")}; var sampleRate by remember{mutableStateOf("256")}; var scale by remember{mutableStateOf("1.0")}; var sampleFormat by remember{mutableStateOf(BleSampleFormat.INT16_LE)}; var message by remember{mutableStateOf("")}
    val permissions=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ result -> if(result.values.all{it}) manager.startScan() else message="Bluetooth-Berechtigung fehlt" }
    DisposableEffect(Unit){ onDispose{manager.stopScan();manager.disconnect()} }
    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("BLE Live",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Text("Generisches EEG-BLE-Profil. Gerätespezifische UUIDs, Samplingrate und Skalierung müssen zum Sensor passen.",style=MaterialTheme.typography.bodySmall)
        Card{Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){ OutlinedTextField(serviceUuid,{serviceUuid=it},label={Text("Service UUID")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(characteristicUuid,{characteristicUuid=it},label={Text("Notify Characteristic UUID")},modifier=Modifier.fillMaxWidth()); OutlinedTextField(channelNames,{channelNames=it},label={Text("Kanäle")},modifier=Modifier.fillMaxWidth()); Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(sampleRate,{sampleRate=it},label={Text("Hz")},modifier=Modifier.weight(1f));OutlinedTextField(scale,{scale=it},label={Text("Skalierung")},modifier=Modifier.weight(1f))}; OutlinedButton(onClick={val entries=BleSampleFormat.entries;sampleFormat=entries[(sampleFormat.ordinal+1)%entries.size]}){Text("Format: ${sampleFormat.name}")} }}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={permissionLauncher.launch(permissions)},enabled=!state.scanning){Text("BLE scannen")};OutlinedButton(onClick=manager::stopScan,enabled=state.scanning){Text("Stop")};OutlinedButton(onClick=manager::disconnect,enabled=state.connectedDevice!=null){Text("Trennen")}}
        Text(state.status); if(message.isNotBlank()) Text(message,color=MaterialTheme.colorScheme.error)
        state.devices.take(12).forEach{item->Card{Row(Modifier.fillMaxWidth().padding(10.dp),horizontalArrangement=Arrangement.SpaceBetween){Column{Text(item.name,fontWeight=FontWeight.Bold);Text("${item.address} · ${item.rssi} dBm",style=MaterialTheme.typography.bodySmall)};Button(onClick={runCatching{val names=channelNames.split(',').map{it.trim()}.filter{it.isNotEmpty()};require(names.isNotEmpty());manager.connect(item.device,BleEegProfile(UUID.fromString(serviceUuid.trim()),UUID.fromString(characteristicUuid.trim()),sampleRate.toDouble(),names,sampleFormat,scale.toDouble()))}.onFailure{message=it.message ?: "Profil ungültig"}}){Text("Verbinden")}}}}
        if(state.channels.isNotEmpty()){val first=state.channels.entries.first();Card{Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Text("Live · ${first.key} · ${first.value.size} Samples",fontWeight=FontWeight.Bold);SignalChart(first.value);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)){Button(onClick={manager.snapshotRecording()?.let(onUseRecording)}){Text("Als Aufnahme übernehmen")};OutlinedButton(onClick=manager::clearBuffer){Text("Puffer leeren")}}}}}
        Spacer(Modifier.height(80.dp))
    }
}
