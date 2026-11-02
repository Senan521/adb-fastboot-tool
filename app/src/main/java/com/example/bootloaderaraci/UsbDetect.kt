package com.example.bootloaderaraci

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface

enum class Mode { NONE, ADB, FASTBOOT }

object UsbDetect {
    /** ADB: protokol 1, Fastboot: protokol 3 (class 0xFF, subclass 0x42) */
    fun find(d: UsbDevice, protocol: Int): UsbInterface? {
        for (i in 0 until d.interfaceCount) {
            val x = d.getInterface(i)
            if (x.interfaceClass == 0xFF && x.interfaceSubclass == 0x42 && x.interfaceProtocol == protocol) return x
        }
        return null
    }

    fun modeOf(d: UsbDevice): Mode? = when {
        find(d, 3) != null -> Mode.FASTBOOT
        find(d, 1) != null -> Mode.ADB
        else -> null
    }
}
