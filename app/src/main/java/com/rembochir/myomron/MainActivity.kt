package com.rembochir.myomron

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.*
import kotlin.math.pow
import android.annotation.SuppressLint
import java.text.SimpleDateFormat

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "OmronBLE"

        private val BLOOD_PRESSURE_SERVICE = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")
        private val BP_MEASUREMENT_CHAR = UUID.fromString("00002a35-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // UCP для информации (не используем активно)
        private val USER_DATA_SERVICE = UUID.fromString("0000181c-0000-1000-8000-00805f9b34fb")
        private val USER_CONTROL_POINT = UUID.fromString("00002a9f-0000-1000-8000-00805f9b34fb")
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var scanner: BluetoothLeScanner
    private var gatt: BluetoothGatt? = null
    private var isConnecting = false

    private var bpMeasurementChar: BluetoothGattCharacteristic? = null

    // Для истории
    private val historyMeasurements = mutableListOf<String>()

    // UI Elements
    private lateinit var tvStatus: TextView
    private lateinit var tvPressure: TextView
    private lateinit var tvPulse: TextView
    private lateinit var tvHistory: TextView
    private lateinit var btnReconnect: Button
    private lateinit var btnClearHistory: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            initBluetooth()
        } else {
            Log.e(TAG, "Permissions denied")
            tvStatus.text = "Permissions denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvPressure = findViewById(R.id.tvPressure)
        tvPulse = findViewById(R.id.tvPulse)
        tvHistory = findViewById(R.id.tvHistory)
        btnReconnect = findViewById(R.id.btnReconnect)
        btnClearHistory = findViewById(R.id.btnClearHistory)

        btnReconnect.setOnClickListener { reconnect() }
        btnClearHistory.setOnClickListener {
            historyMeasurements.clear()
            updateHistoryDisplay()
            tvStatus.text = "History cleared"
        }

        if (hasPermissions()) {
            initBluetooth()
        } else {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            permissionLauncher.launch(permissions)
        }
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun isConnectPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun isScanPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun initBluetooth() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter

        if (!bluetoothAdapter.isEnabled) {
            tvStatus.text = "Bluetooth is OFF"
            return
        }

        scanner = bluetoothAdapter.bluetoothLeScanner
        startScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!isScanPermissionGranted()) {
            tvStatus.text = "No scan permission"
            return
        }

        tvStatus.text = "Scanning..."
        Log.d(TAG, "Scanning...")
        scanner.startScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.d(TAG, "Found: ${device.name} ${device.address}")

            val isTarget = device.name?.contains("BLESmart", true) == true ||
                    device.name?.contains("OMRON", true) == true ||
                    device.name?.contains("HEM", true) == true

            if (!isTarget) return

            if (isConnecting || gatt != null) return
            if (!isConnectPermissionGranted()) return

            isConnecting = true
            runOnUiThread { tvStatus.text = "Connecting..." }

            try {
                scanner.stopScan(this)
                gatt = device.connectGatt(
                    this@MainActivity,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            } catch (e: SecurityException) {
                isConnecting = false
                Log.e(TAG, "connectGatt failed", e)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "✅ Connected")
                    isConnecting = false
                    gatt = g
                    runOnUiThread { tvStatus.text = "Connected, discovering services..." }
                    if (isConnectPermissionGranted()) {
                        g.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "❌ Disconnected")
                    isConnecting = false
                    runOnUiThread { tvStatus.text = "Disconnected" }
                    closeGattSafe(g)
                    if (gatt === g) {
                        gatt = null
                        bpMeasurementChar = null
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }

            Log.d(TAG, "✅ Services discovered")

            // ===== Blood Pressure Service (1810) =====
            val bpService = g.getService(BLOOD_PRESSURE_SERVICE)
            if (bpService != null) {
                Log.d(TAG, "=== Blood Pressure Service (1810) ===")

                for (char in bpService.characteristics) {
                    Log.d(TAG, "  Char: ${char.uuid} | props: ${char.properties}")
                }

                bpMeasurementChar = bpService.getCharacteristic(BP_MEASUREMENT_CHAR)
                if (bpMeasurementChar != null) {
                    Log.d(TAG, "✅ BP Measurement CHAR found")
                    enableBPIndication(g)
                }
            } else {
                Log.e(TAG, "❌ Blood Pressure Service (1810) not found!")
            }

            // UCP включаем для информации, но не используем активно
            val userDataService = g.getService(USER_DATA_SERVICE)
            if (userDataService != null) {
                val ucp = userDataService.getCharacteristic(USER_CONTROL_POINT)
                if (ucp != null) {
                    Log.d(TAG, "✅ User Control Point FOUND (for info only)")
                }
            }

            runOnUiThread { tvStatus.text = "Waiting for data from device..." }
            try {
                g.requestMtu(160)
            } catch (e: Exception) {
                Log.e(TAG, "requestMtu failed", e)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "MTU failed: $status")
                return
            }
            Log.d(TAG, "MTU: $mtu")
            runOnUiThread { tvStatus.text = "Ready - device will send data automatically" }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val charUuid = descriptor.characteristic.uuid
            Log.d(TAG, "Descriptor written for $charUuid status=$status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (charUuid) {
                    BP_MEASUREMENT_CHAR -> {
                        Log.d(TAG, "✅ BP indications enabled")
                        runOnUiThread { tvStatus.text = "BP indications enabled, waiting for data..." }
                    }
                }
            } else {
                Log.e(TAG, "❌ Descriptor write failed: $status")
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value
            Log.d(TAG, "📩 NOTIFY/INDICATE from ${characteristic.uuid}")
            Log.d(TAG, "   Data: ${data.joinToString { "%02x".format(it) }}")

            when (characteristic.uuid) {
                BP_MEASUREMENT_CHAR -> {
                    val result = parseBP(data)

                    // ВСЕ ПРИХОДЯЩИЕ ИЗМЕРЕНИЯ ДОБАВЛЯЕМ В ИСТОРИЮ
                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    val historyLine = "${timeStr} - ${result.systolic.toInt()}/${result.diastolic.toInt()} @ ${result.pulse.toInt()} bpm"
                    historyMeasurements.add(0, historyLine)
                    updateHistoryDisplay()

                    runOnUiThread {
                        tvPressure.text = "${result.systolic.toInt()}/${result.diastolic.toInt()}"
                        tvPulse.text = "Pulse: ${result.pulse.toInt()} bpm"
                        tvStatus.text = "📊 ${historyMeasurements.size} measurements received"
                    }

                    Log.d(TAG, "📊 BP: ${result.systolic.toInt()}/${result.diastolic.toInt()} pulse=${result.pulse.toInt()}")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableBPIndication(g: BluetoothGatt) {
        if (!isConnectPermissionGranted()) return

        bpMeasurementChar?.let { char ->
            g.setCharacteristicNotification(char, true)
            val descriptor = char.getDescriptor(CLIENT_CONFIG)
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                g.writeDescriptor(it)
                Log.d(TAG, "Enabling BP indications")
            }
        }
    }

    // ===== PARSE FUNCTIONS =====

    data class BPResult(
        val systolic: Float,
        val diastolic: Float,
        val meanArterial: Float,
        val pulse: Float
    )

    private fun parseBP(data: ByteArray): BPResult {
        if (data.size < 7) {
            Log.e(TAG, "Data too short: ${data.size} bytes")
            return BPResult(0f, 0f, 0f, 0f)
        }

        var offset = 1
        val systolic = readSFloat(data, offset); offset += 2
        val diastolic = readSFloat(data, offset); offset += 2
        val meanArterial = readSFloat(data, offset); offset += 2

        var pulse = 0f
        if (data.size > 14) {
            val pulseValue = data[14].toInt() and 0xFF
            if (pulseValue in 40..150) {
                pulse = pulseValue.toFloat()
            }
        }

        return BPResult(systolic, diastolic, meanArterial, pulse)
    }

    private fun readSFloat(data: ByteArray, offset: Int): Float {
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        val raw = (b1 shl 8) or b0
        val mantissa = raw and 0x0FFF
        val exponent = raw shr 12
        val m = if (mantissa >= 0x0800) mantissa - 0x1000 else mantissa
        val e = if (exponent >= 0x08) exponent - 0x10 else exponent
        return (m * 10.0.pow(e)).toFloat()
    }

    private fun updateHistoryDisplay() {
        val displayText = historyMeasurements.take(15).joinToString("\n")
        runOnUiThread {
            tvHistory.text = if (displayText.isEmpty()) "No history yet\n\nMeasurements will appear here automatically when device sends them" else displayText
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGattSafe(g: BluetoothGatt?) {
        if (g == null) return
        try {
            if (isConnectPermissionGranted()) {
                g.close()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while closing GATT", e)
        }
    }

    private fun reconnect() {
        runOnUiThread { tvStatus.text = "Reconnecting..." }
        closeGattSafe(gatt)
        gatt = null
        bpMeasurementChar = null
        isConnecting = false
        initBluetooth()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeGattSafe(gatt)
    }
}