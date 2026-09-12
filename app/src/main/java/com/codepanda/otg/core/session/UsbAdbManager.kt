package com.codepanda.otg.core.session

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.codepanda.otg.adb.AdbConnection
import com.codepanda.otg.adb.AdbCrypto
import com.codepanda.otg.core.log.AppLog
import com.codepanda.otg.usb.UsbDeviceScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Bridges Android's [UsbManager] permission model to our [SessionManager].
 *
 * Responsibilities:
 *  - enumerate attached OTG devices that speak ADB;
 *  - request the one-time USB permission (the system dialog);
 *  - once granted, open the transport and run the ADB handshake off the main
 *    thread, then publish the resulting [DeviceSession];
 *  - drop the session automatically when the cable is pulled.
 */
class UsbAdbManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val crypto: AdbCrypto by lazy { AdbCrypto.loadOrCreate(context.filesDir) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var connectingDeviceId: Int? = null
    private var receiverRegistered = false

    fun compatibleDevices(): List<UsbDevice> {
        val devices = UsbDeviceScanner.compatibleDevices(usbManager)
        AppLog.d(
            TAG,
            "Scan found ${devices.size} ADB-capable device(s) of ${usbManager.deviceList.size} attached" +
                devices.joinToString(prefix = ": ", separator = ", ") { displayName(it) }
                    .takeIf { devices.isNotEmpty() }.orEmpty(),
        )
        return devices
    }

    /** Kick off a connection: request permission if needed, then handshake. */
    fun connect(device: UsbDevice) {
        ensureReceiver()
        if (usbManager.hasPermission(device)) {
            AppLog.i(TAG, "USB permission already granted for ${displayName(device)}")
            startConnect(device)
        } else {
            AppLog.i(TAG, "Requesting USB permission for ${displayName(device)}")
            SessionManager.setConnecting(displayName(device))
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(device, pendingIntent)
        }
    }

    fun disconnect() {
        connectingDeviceId = null
        SessionManager.disconnect()
    }

    private fun startConnect(device: UsbDevice) {
        connectingDeviceId = device.deviceId
        val name = displayName(device)
        SessionManager.setConnecting(name)
        scope.launch {
            try {
                AppLog.i(
                    TAG,
                    "Opening transport to $name (vendor=0x%04x product=0x%04x)"
                        .format(device.vendorId, device.productId),
                )
                val transport = UsbDeviceScanner.openTransport(usbManager, device)
                val connection = AdbConnection(transport, crypto)
                connection.connect()
                SessionManager.setConnected(
                    session = DeviceSession(connection, name),
                    deviceName = name,
                    banner = connection.deviceBanner,
                )
            } catch (t: Throwable) {
                AppLog.e(TAG, "Connect to $name failed", t)
                SessionManager.setFailed(t.message ?: "Failed to connect")
            }
        }
    }

    private fun ensureReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    fun release() {
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice() ?: return
                    val granted =
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        AppLog.i(TAG, "USB permission granted for ${displayName(device)}")
                        startConnect(device)
                    } else {
                        AppLog.w(TAG, "USB permission denied for ${displayName(device)}")
                        SessionManager.setFailed("USB permission was denied.")
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDevice()
                    AppLog.w(TAG, "USB device detached: ${device?.let { displayName(it) } ?: "unknown"}")
                    if (device == null || device.deviceId == connectingDeviceId ||
                        SessionManager.state.value is ConnectionState.Connected
                    ) {
                        connectingDeviceId = null
                        SessionManager.disconnect()
                    }
                }
            }
        }
    }

    private fun displayName(device: UsbDevice): String =
        device.productName ?: device.deviceName

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        private const val TAG = "UsbAdbManager"
        private const val ACTION_USB_PERMISSION = "com.codepanda.otg.USB_PERMISSION"
    }
}
