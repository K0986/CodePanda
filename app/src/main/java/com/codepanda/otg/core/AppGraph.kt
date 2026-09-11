package com.codepanda.otg.core

import android.content.Context
import com.codepanda.otg.core.session.UsbAdbManager

/**
 * Minimal manual dependency graph. The app is small enough that a full DI
 * framework would be overkill; a single initialised-at-startup holder keeps the
 * one stateful, context-bound object ([UsbAdbManager]) reachable from ViewModels.
 */
object AppGraph {

    lateinit var usbManager: UsbAdbManager
        private set

    fun init(context: Context) {
        if (::usbManager.isInitialized) return
        usbManager = UsbAdbManager(context.applicationContext)
    }
}
