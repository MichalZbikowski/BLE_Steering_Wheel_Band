import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.*

class BleButtonReceiver(private val context: Context) {

    companion object {
        private const val TAG = "BleButtonReceiver"
        private const val DEVICE_NAME = "ESP32_Buttons"
        private const val SERVICE_UUID = "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
        private const val CHARACTERISTIC_UUID = "beb5483e-36e1-4688-b7f5-ea07361b26a8"
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val INITIAL_SCAN_DURATION_MS = 30000L  // 30s initial scan (for long absences)
        private const val BURST_SCAN_DURATION_MS = 10000L   // 10s burst scans
        private const val CONNECTION_TIMEOUT_MS = 5000L     // 5s connection timeout
        private const val INITIAL_RECONNECT_DELAY_MS = 500L // 500ms initial delay
        private const val MAX_RECONNECT_DELAY_MS = 8000L    // 8s max delay
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private val bleScanner by lazy { bluetoothAdapter?.bluetoothLeScanner }
    private var bluetoothGatt: BluetoothGatt? = null

    private var actionCallback: ((String) -> Unit)? = null
    private var connectionCallback: ((Boolean) -> Unit)? = null
    private var reconnectCallback: ((Int, Long) -> Unit)? = null  // attempt, delay

    private var isScanning = false
    private var reconnectAttempts = 0
    private var currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS
    private var scanStartTime = 0L
    private var connectionStartTime = 0L

    // ========================= SCAN =========================

    private val scanCallback = object : ScanCallback() {

        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return

            if (name == DEVICE_NAME) {
                Log.d(TAG, "✅ Found $DEVICE_NAME")
                stopScanning()
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "❌ Scan failed: $errorCode")
            scheduleReconnect()
        }
    }

    // ========================= GATT =========================

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "✅ Connected")
                    bluetoothGatt = gatt
                    reconnectAttempts = 0
                    currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS
                    connectionCallback?.invoke(true)
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "❌ Disconnected")
                    connectionCallback?.invoke(false)
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val service = gatt.getService(UUID.fromString(SERVICE_UUID)) ?: return
            val characteristic =
                service.getCharacteristic(UUID.fromString(CHARACTERISTIC_UUID)) ?: return

            gatt.setCharacteristicNotification(characteristic, true)

            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            val action = String(value)
            actionCallback?.invoke(action)
        }
    }

    // ========================= PUBLIC API =========================

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (!hasPermissions()) return
        if (bluetoothAdapter?.isEnabled != true) return

        reconnectAttempts = 0
        currentReconnectDelay = INITIAL_RECONNECT_DELAY_MS
        startScanWithDuration(INITIAL_SCAN_DURATION_MS)
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (isScanning) {
            bleScanner?.stopScan(scanCallback)
            isScanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        connectionStartTime = System.currentTimeMillis()
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScanning()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    fun setActionCallback(cb: (String) -> Unit) {
        actionCallback = cb
    }

    fun setConnectionCallback(cb: (Boolean) -> Unit) {
        connectionCallback = cb
    }

    fun setReconnectCallback(cb: (Int, Long) -> Unit) {
        reconnectCallback = cb
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startScanWithDuration(durationMs: Long) {
        if (isScanning) return

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "BLUETOOTH_SCAN permission not granted")
            return
        }

        Log.d(TAG, "🔍 Scanning for ${durationMs}ms...")
        isScanning = true
        scanStartTime = System.currentTimeMillis()
        bleScanner?.startScan(scanCallback)

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isScanning) {
                stopScanning()
                scheduleReconnect()
            }
        }, durationMs)
    }

    private fun scheduleReconnect() {
        reconnectAttempts++
        reconnectCallback?.invoke(reconnectAttempts, currentReconnectDelay)

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startScanWithDuration(BURST_SCAN_DURATION_MS)
        }, currentReconnectDelay)

        // Exponential backoff
        currentReconnectDelay = minOf(currentReconnectDelay * 2, MAX_RECONNECT_DELAY_MS)
    }
}
