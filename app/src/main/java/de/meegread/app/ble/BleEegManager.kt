package de.meegread.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import de.meegread.app.model.ChannelInfo
import de.meegread.app.model.ChannelType
import de.meegread.app.model.MeegRecording
import de.meegread.app.model.Modality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.UUID

enum class BleSampleFormat { INT16_LE, INT16_BE, INT24_LE, FLOAT32_LE }

data class BleEegProfile(
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    val sampleRateHz: Double,
    val channelNames: List<String>,
    val sampleFormat: BleSampleFormat = BleSampleFormat.INT16_LE,
    val scale: Double = 1.0,
    val offset: Double = 0.0
)

data class BleDeviceItem(val device: BluetoothDevice, val name: String, val address: String, val rssi: Int)
data class BleEegState(val status: String = "Bereit", val scanning: Boolean = false, val connectedDevice: String? = null, val devices: List<BleDeviceItem> = emptyList(), val channels: Map<String, List<Double>> = emptyMap(), val sampleRateHz: Double = 0.0)

@SuppressLint("MissingPermission")
class BleEegManager(context: Context) {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager?.adapter
    private val scanner get() = adapter?.bluetoothLeScanner
    private val lock = Any()
    private val buffers = linkedMapOf<String, ArrayDeque<Double>>()
    private val maxSamplesPerChannel = 20_000
    private var profile: BleEegProfile? = null
    private var gatt: BluetoothGatt? = null
    private val _state = MutableStateFlow(BleEegState())
    val state: StateFlow<BleEegState> = _state.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val item = BleDeviceItem(device, device.name ?: result.scanRecord?.deviceName ?: "Unbenannt", device.address, result.rssi)
            _state.value = _state.value.copy(devices = (_state.value.devices.filterNot { it.address == item.address } + item).sortedByDescending { it.rssi })
        }
        override fun onScanFailed(errorCode: Int) { _state.value = _state.value.copy(scanning = false, status = "BLE-Scanfehler $errorCode") }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                android.bluetooth.BluetoothProfile.STATE_CONNECTED -> { _state.value = _state.value.copy(status = "Verbunden – Dienste werden gesucht", connectedDevice = runCatching { gatt.device.name }.getOrNull() ?: gatt.device.address); gatt.discoverServices() }
                android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> { _state.value = _state.value.copy(status = "Getrennt", connectedDevice = null); if (this@BleEegManager.gatt === gatt) this@BleEegManager.gatt = null; gatt.close() }
            }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val active = profile ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) { _state.value = _state.value.copy(status = "Dienstsuche fehlgeschlagen: $status"); return }
            val service: BluetoothGattService = gatt.getService(active.serviceUuid) ?: run { _state.value = _state.value.copy(status = "Service nicht gefunden"); return }
            val characteristic = service.getCharacteristic(active.characteristicUuid) ?: run { _state.value = _state.value.copy(status = "Characteristic nicht gefunden"); return }
            val enabled = gatt.setCharacteristicNotification(characteristic, true)
            characteristic.getDescriptor(CCCD_UUID)?.let { descriptor ->
                @Suppress("DEPRECATION") descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION") gatt.writeDescriptor(descriptor)
            }
            _state.value = _state.value.copy(status = if (enabled) "Live-Daten aktiv" else "Notifications konnten nicht aktiviert werden")
        }
        @Deprecated("Deprecated in Android API")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { @Suppress("DEPRECATION") handlePacket(characteristic.value ?: return) }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { handlePacket(value) }
    }

    fun startScan() { val s = scanner ?: run { _state.value = _state.value.copy(status = "Bluetooth LE ist nicht verfügbar"); return }; stopScan(); _state.value = _state.value.copy(scanning = true, devices = emptyList(), status = "Suche nach BLE-Geräten …"); s.startScan(scanCallback) }
    fun stopScan() { runCatching { scanner?.stopScan(scanCallback) }; if (_state.value.scanning) _state.value = _state.value.copy(scanning = false, status = "Scan beendet") }
    fun connect(device: BluetoothDevice, profile: BleEegProfile) {
        stopScan(); disconnect(); this.profile = profile
        synchronized(lock) { buffers.clear(); profile.channelNames.forEach { buffers[it] = ArrayDeque() } }
        _state.value = BleEegState(status = "Verbindung zu ${device.name ?: device.address} …", connectedDevice = device.name ?: device.address, sampleRateHz = profile.sampleRateHz)
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE) else device.connectGatt(appContext, false, gattCallback)
    }
    fun disconnect() { val current = gatt; gatt = null; if (current != null) { runCatching { current.disconnect() }; runCatching { current.close() } }; _state.value = _state.value.copy(status = "Bereit", connectedDevice = null) }
    fun clearBuffer() { synchronized(lock) { buffers.values.forEach { it.clear() } }; publishBuffers() }
    fun snapshotRecording(name: String = "BLE_${System.currentTimeMillis()}"): MeegRecording? {
        val active = profile ?: return null
        val snapshot = synchronized(lock) { buffers.mapValues { it.value.toList() } }
        if (snapshot.values.all { it.isEmpty() }) return null
        val count = snapshot.values.maxOf { it.size }
        return MeegRecording(name, Modality.EEG, active.sampleRateHz, snapshot, count, count / active.sampleRateHz, active.channelNames.associateWith { ChannelInfo(it, ChannelType.EEG, "a.u.", active.sampleRateHz) }, metadata = mapOf("format" to "BLE", "device" to (_state.value.connectedDevice ?: "unknown")))
    }

    private fun handlePacket(packet: ByteArray) {
        val active = profile ?: return; val channelCount = active.channelNames.size; if (channelCount == 0) return
        val bytesPerValue = when (active.sampleFormat) { BleSampleFormat.INT16_LE, BleSampleFormat.INT16_BE -> 2; BleSampleFormat.INT24_LE -> 3; BleSampleFormat.FLOAT32_LE -> 4 }
        val frameSize = bytesPerValue * channelCount; if (packet.size < frameSize) return
        synchronized(lock) {
            var offset = 0
            while (offset + frameSize <= packet.size) {
                active.channelNames.forEach { channel ->
                    val decoded = decode(packet, offset, active.sampleFormat); offset += bytesPerValue
                    val value = decoded * active.scale + active.offset
                    if (value.isFinite()) { val queue = buffers.getOrPut(channel) { ArrayDeque() }; queue.addLast(value); while (queue.size > maxSamplesPerChannel) queue.removeFirst() }
                }
            }
        }
        publishBuffers()
    }
    private fun publishBuffers() { val snapshot = synchronized(lock) { buffers.mapValues { it.value.toList() } }; _state.value = _state.value.copy(channels = snapshot, sampleRateHz = profile?.sampleRateHz ?: 0.0) }
    private fun decode(bytes: ByteArray, offset: Int, format: BleSampleFormat): Double = when (format) {
        BleSampleFormat.INT16_LE -> { val raw = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8); (if (raw and 0x8000 != 0) raw - 0x10000 else raw).toDouble() }
        BleSampleFormat.INT16_BE -> { val raw = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff); (if (raw and 0x8000 != 0) raw - 0x10000 else raw).toDouble() }
        BleSampleFormat.INT24_LE -> { val raw = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or ((bytes[offset + 2].toInt() and 0xff) shl 16); (if (raw and 0x800000 != 0) raw or -0x1000000 else raw).toDouble() }
        BleSampleFormat.FLOAT32_LE -> { val bits = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or ((bytes[offset + 2].toInt() and 0xff) shl 16) or ((bytes[offset + 3].toInt() and 0xff) shl 24); Float.fromBits(bits).toDouble() }
    }
    companion object { private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") }
}
