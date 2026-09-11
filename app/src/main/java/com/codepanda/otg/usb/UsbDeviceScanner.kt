package com.codepanda.otg.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.codepanda.otg.adb.AdbTransportException

/**
 * Locates and opens the ADB interface exposed by an Android device in
 * "USB debugging" mode.
 *
 * Every such device advertises a USB interface with class `0xFF` (vendor
 * specific), subclass `0x42` and protocol `0x01`, carrying exactly two bulk
 * endpoints. We match on those constants so the app is vendor-independent.
 */
object UsbDeviceScanner {

    private const val ADB_CLASS = 0xFF
    private const val ADB_SUBCLASS = 0x42
    private const val ADB_PROTOCOL = 0x01

    data class AdbInterface(
        val usbInterface: UsbInterface,
        val endpointIn: UsbEndpoint,
        val endpointOut: UsbEndpoint,
    )

    /** All currently attached devices that expose an ADB interface. */
    fun compatibleDevices(manager: UsbManager): List<UsbDevice> =
        manager.deviceList.values.filter { findAdbInterface(it) != null }

    /** Return the ADB interface + bulk endpoints for [device], or null. */
    fun findAdbInterface(device: UsbDevice): AdbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == ADB_CLASS &&
                iface.interfaceSubclass == ADB_SUBCLASS &&
                iface.interfaceProtocol == ADB_PROTOCOL
            ) {
                var input: UsbEndpoint? = null
                var output: UsbEndpoint? = null
                for (e in 0 until iface.endpointCount) {
                    val endpoint = iface.getEndpoint(e)
                    if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint
                    else output = endpoint
                }
                if (input != null && output != null) {
                    return AdbInterface(iface, input, output)
                }
            }
        }
        return null
    }

    /**
     * Claim the ADB interface and wrap it in a [UsbAdbTransport].
     * The caller must already hold USB permission for [device].
     */
    fun openTransport(manager: UsbManager, device: UsbDevice): UsbAdbTransport {
        val adb = findAdbInterface(device)
            ?: throw AdbTransportException("${device.deviceName} has no ADB interface")
        val connection: UsbDeviceConnection = manager.openDevice(device)
            ?: throw AdbTransportException("Unable to open ${device.deviceName}")
        if (!connection.claimInterface(adb.usbInterface, true)) {
            connection.close()
            throw AdbTransportException("Unable to claim ADB interface on ${device.deviceName}")
        }
        val name = buildString {
            append(device.productName ?: device.deviceName)
            device.serialNumber?.let { append(" ($it)") }
        }
        return UsbAdbTransport(connection, adb.usbInterface, adb.endpointIn, adb.endpointOut, name)
    }
}
