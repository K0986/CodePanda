package com.codepanda.otg.ui.connect

import android.hardware.usb.UsbDevice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.codepanda.otg.core.AppGraph

/** Backing state for the connection / device-picker screen. */
class ConnectViewModel : ViewModel() {

    private val usb = AppGraph.usbManager

    var devices by mutableStateOf<List<UsbDevice>>(emptyList())
        private set

    fun refresh() {
        devices = usb.compatibleDevices()
    }

    fun connect(device: UsbDevice) = usb.connect(device)

    fun disconnect() = usb.disconnect()
}
