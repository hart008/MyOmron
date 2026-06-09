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
import java.util.UUID
import kotlin.math.pow
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "OmronBLE"

        private val BLOOD_PRESSURE_SERVICE = UUID.fromString("00001810-0000-1000-8000-00805f9b34fb")
        private val BP_MEASUREMENT_CHAR = UUID.fromString("00002a35-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var scanner: BluetoothLeScanner
    private var gatt: BluetoothGatt? = null
    private var isConnecting = false

    private var measurementReceived = false

    // UI Elements
    private lateinit var tvStatus: TextView
    private lateinit var tvPressure: TextView
    private lateinit var tvPulse: TextView
    private lateinit var btnReconnect: Button

    // ===== PERMISSIONS =====

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

        // Инициализация UI
        tvStatus = findViewById(R.id.tvStatus)
        tvPressure = findViewById(R.id.tvPressure)
        tvPulse = findViewById(R.id.tvPulse)
        btnReconnect = findViewById(R.id.btnReconnect)

        btnReconnect.setOnClickListener {
            reconnect()
        }

        if (hasPermissions()) {
            initBluetooth()
        } else {
            permissionLauncher.launch(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                } else {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            )
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
    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    // ===== BLE INIT =====

    private fun initBluetooth() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter

        if (!bluetoothAdapter.isEnabled) {
            tvStatus.text = "Bluetooth is OFF"
            Log.e(TAG, "Bluetooth is OFF")
            return
        }

        scanner = bluetoothAdapter.bluetoothLeScanner
        startScan()
    }

    // ===== SCAN =====

    @SuppressLint("MissingPermission")
    private fun startScan() {
        tvStatus.text = "Scanning..."
        Log.d(TAG, "Scanning...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.e(TAG, "No SCAN permission")
                tvStatus.text = "No scan permission"
                return
            }
        }

        scanner.startScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.d(TAG, "Found: ${device.name} ${device.address}")

            val isTarget = device.name?.contains("OMRON", true) == true ||
                    device.name?.contains("BLESmart", true) == true ||
                    device.name?.contains("HEM", true) == true

            if (!isTarget) return

            if (isConnecting || gatt != null) {
                Log.d(TAG, "Skip duplicate connection attempt")
                return
            }

            if (!hasConnectPermission()) return

            isConnecting = true
            tvStatus.text = "Connecting..."

            try {
                scanner.stopScan(this)
                gatt = device.connectGatt(
                    this@MainActivity,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
                Log.d(TAG, "connectGatt started")
            } catch (e: SecurityException) {
                isConnecting = false
                Log.e(TAG, "connectGatt failed", e)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    // ===== GATT CALLBACK =====

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "✅ Connected")
                    isConnecting = false
                    gatt = g
                    runOnUiThread { tvStatus.text = "Connected, discovering services..." }
                    if (hasConnectPermission()) {
                        g.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {

                    Log.d(TAG, "❌ Disconnected")
                    try {
                        gatt?.close()
                    } catch (_: SecurityException) {
                    }
                    gatt = null
                    isConnecting = false
                    if (!measurementReceived) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            startScan()
                        }, 1000)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            for (service in g.services) {
                Log.d(TAG, "SERVICE = ${service.uuid}")
                for (characteristic in service.characteristics) {
                    Log.d(
                        TAG,
                        "CHAR = ${characteristic.uuid} PROPS=${characteristic.properties}"
                    )
                }
            }
            for (service in g.services) {

                for (ch in service.characteristics) {

                    if (ch.uuid.toString()
                            .equals(
                                "00002a52-0000-1000-8000-00805f9b34fb",
                                true
                            )
                    ) {

                        Log.d(TAG, "=== RACP FOUND ===")
                        Log.d(TAG, "UUID = ${ch.uuid}")
                        Log.d(TAG, "PROPS = ${ch.properties}")

                        for (d in ch.descriptors) {
                            Log.d(TAG, "DESC = ${d.uuid}")
                        }
                    }
                }
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }

            Log.d(TAG, "Services discovered")
            runOnUiThread { tvStatus.text = "Services discovered, setting MTU..." }

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

            enableIndication(g)

            Handler(Looper.getMainLooper()).postDelayed({
                enableRacp(g)
            }, 500)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Descriptor write failed: $status")
                return
            }

            val charUuid = descriptor.characteristic.uuid

            Log.d(TAG, "Descriptor written for $charUuid")

            if (charUuid == BP_MEASUREMENT_CHAR) {

                Log.d(TAG, "✅ BP indication enabled")

                runOnUiThread {
                    tvStatus.text = "Ready! Press START on your Omron device"
                }

            } else if (
                charUuid == UUID.fromString("00002a52-0000-1000-8000-00805f9b34fb")
            ) {

                Log.d(TAG, "✅ RACP indication enabled")

                Handler(Looper.getMainLooper()).postDelayed({
                    requestHistory(g)
                }, 500)
            }
        }
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(
                TAG,
                "Characteristic write ${characteristic.uuid} status=$status"
            )
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d(
                TAG,
                "NOTIFY UUID = ${characteristic.uuid}"
            )
            val data = characteristic.value
            Log.d(TAG, "RAW: ${data.joinToString { "%02x".format(it) }}")

            val result = parseBP(data)

            runOnUiThread {
                tvPressure.text = "${result.systolic.toInt()}/${result.diastolic.toInt()}"
                tvPulse.text = "Pulse: ${result.pulse.toInt()} bpm"
                tvStatus.text = "Measurement received!"
            }

            // Сброс статуса через 3 секунды
            android.os.Handler(mainLooper).postDelayed({
                runOnUiThread {
                    if (tvStatus.text == "Measurement received!") {
                        tvStatus.text = "Ready for next measurement"
                    }
                }
            }, 3000)
        }
    }

    // ===== ENABLE INDICATION =====

    @SuppressLint("MissingPermission")
    private fun enableIndication(g: BluetoothGatt) {
        if (!hasConnectPermission()) return

        try {
            val service = g.getService(BLOOD_PRESSURE_SERVICE)
            if (service == null) {
                Log.e(TAG, "No BP service")
                return
            }

            val char = service.getCharacteristic(BP_MEASUREMENT_CHAR)
            if (char == null) {
                Log.e(TAG, "No BP char")
                return
            }

            g.setCharacteristicNotification(char, true)

            val descriptor = char.getDescriptor(CLIENT_CONFIG)
            if (descriptor == null) {
                Log.e(TAG, "No descriptor")
                return
            }

            descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            g.writeDescriptor(descriptor)
            Log.d(TAG, "BP indication request sent")

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in enableIndication()", e)
        }
    }

    // ===== CLOSE GATT SAFE =====

    @SuppressLint("MissingPermission")
    private fun closeGattSafe(g: BluetoothGatt?) {
        if (g == null) return

        try {
            if (hasConnectPermission()) {
                g.close()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while closing GATT", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableRacp(gatt: BluetoothGatt) {

        try {
            val service = gatt.getService(
                UUID.fromString("5df5e817-a945-4f81-89c0-3d4e9759c07c")
            ) ?: return

            val racp = service.getCharacteristic(
                UUID.fromString("00002a52-0000-1000-8000-00805f9b34fb")
            ) ?: return

            gatt.setCharacteristicNotification(racp, true)

            val cccd = racp.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            ) ?: return

            cccd.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(
                    cccd,
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                )
            } else {
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }

            Log.d(TAG, "RACP indication enabled")
        } catch (e: SecurityException) {
            Log.e(TAG, "enableRacp", e)
        }
    }

    private fun requestHistory(gatt: BluetoothGatt) {
        if (!hasConnectPermission()) {
            Log.e(TAG, "No BLUETOOTH_CONNECT permission")
            return
        }
        try {
            val service = gatt.getService(
                UUID.fromString("5df5e817-a945-4f81-89c0-3d4e9759c07c")
            ) ?: return

            val racp = service.getCharacteristic(
                UUID.fromString("00002a52-0000-1000-8000-00805f9b34fb")
            ) ?: return

            val cmd = byteArrayOf(
                0x01,
                0x01
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                gatt.writeCharacteristic(
                    racp,
                    cmd,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )

            } else {

                @Suppress("DEPRECATION")
                racp.value = cmd

                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(racp)
            }

            Log.d(TAG, "RACP history request sent")
        } catch (e: SecurityException) {
            Log.e(TAG, "enableRacp", e)
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

        measurementReceived = true

        if (data.size < 7) {
            Log.e(TAG, "Data too short: ${data.size} bytes")
            return BPResult(0f, 0f, 0f, 0f)
        }

        var offset = 1

        // Систола (2 байта, IEEE-11073 sfloat)
        val systolic = readSFloat(data, offset); offset += 2

        // Диастола (2 байта, IEEE-11073 sfloat)
        val diastolic = readSFloat(data, offset); offset += 2

        // Среднее давление (2 байта, IEEE-11073 sfloat)
        val meanArterial = readSFloat(data, offset); offset += 2

        // Пульс - на позиции 14 (один байт)
        var pulse = 0f
        if (data.size > 14) {
            val pulseValue = data[14].toInt() and 0xFF
            if (pulseValue in 40..150) {
                pulse = pulseValue.toFloat()
            }
        }

        Log.d(TAG, "===== BLOOD PRESSURE =====")
        Log.d(TAG, "Systolic: $systolic mmHg")
        Log.d(TAG, "Diastolic: $diastolic mmHg")
        Log.d(TAG, "Mean Arterial: $meanArterial mmHg")
        Log.d(TAG, "Pulse: $pulse bpm")
        Log.d(TAG, "=========================")

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

    // ===== RECONNECT =====

    private fun reconnect() {
        runOnUiThread { tvStatus.text = "Reconnecting..." }

        closeGattSafe(gatt)
        gatt = null
        isConnecting = false
        initBluetooth()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeGattSafe(gatt)
    }
}