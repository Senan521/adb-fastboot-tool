package com.example.bootloaderaraci

import android.hardware.usb.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Küçük ADB istemcisi: CNXN + AUTH el sıkışması ve "shell:" servisi. */
class AdbClient(private val usb: UsbManager, private val keys: AdbKeys) {
    private var conn: UsbDeviceConnection? = null
    private var itf: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private var nextId = 1

    private class Msg(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray)

    companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val VERSION = 0x01000000
        const val MAX_DATA = 4096
    }

    fun open(device: UsbDevice): Boolean {
        close()
        val i = UsbDetect.find(device, 1) ?: return false
        val c = usb.openDevice(device) ?: return false
        if (!c.claimInterface(i, true)) { c.close(); return false }
        for (n in 0 until i.endpointCount) {
            val e = i.getEndpoint(n)
            if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (e.direction == UsbConstants.USB_DIR_IN) epIn = e else epOut = e
            }
        }
        if (epIn == null || epOut == null) { c.releaseInterface(i); c.close(); return false }
        conn = c; itf = i
        return true
    }

    fun close() {
        try { itf?.let { conn?.releaseInterface(it) }; conn?.close() } catch (_: Exception) {}
        conn = null; itf = null; epIn = null; epOut = null
    }

    private fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)): Boolean {
        val c = conn ?: return false
        var sum = 0
        for (b in data) sum += b.toInt() and 0xff
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(sum).putInt(cmd.inv())
        if (c.bulkTransfer(epOut, h.array(), 24, 3000) != 24) return false
        var off = 0
        while (off < data.size) {
            val r = c.bulkTransfer(epOut, data, off, minOf(16384, data.size - off), 3000)
            if (r < 0) return false
            off += r
        }
        return true
    }

    private fun recv(timeoutMs: Int): Msg? {
        val c = conn ?: return null
        val h = ByteArray(24)
        if (c.bulkTransfer(epIn, h, 24, timeoutMs) != 24) return null
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int; val a0 = bb.int; val a1 = bb.int; val len = bb.int
        val data = ByteArray(maxOf(len, 0))
        var off = 0
        while (off < len) {
            val r = c.bulkTransfer(epIn, data, off, minOf(16384, len - off), 3000)
            if (r < 0) return null
            off += r
        }
        return Msg(cmd, a0, a1, data)
    }

    /** Hedef telefonda "USB hata ayıklamaya izin ver" penceresi çıkabilir; 35 sn beklenir. */
    fun connect(onStatus: (String) -> Unit): Boolean {
        if (!send(CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())) return false
        var signed = false
        val end = System.currentTimeMillis() + 35_000
        while (System.currentTimeMillis() < end) {
            val m = recv(1000) ?: continue
            when (m.cmd) {
                CNXN -> return true
                AUTH -> if (m.a0 == 1) {
                    if (!signed) {
                        signed = true
                        if (!send(AUTH, 2, 0, keys.sign(m.data))) return false
                    } else {
                        onStatus("Hedef telefonda “USB hata ayıklamaya izin ver” penceresini onayla")
                        if (!send(AUTH, 3, 0, keys.adbPublicKey())) return false
                    }
                }
            }
        }
        return false
    }

    /** "shell:<komut>" çalıştırır. Cihaz yeniden başlıyorsa bağlantı kopar; o zamana kadar gelen çıktıyı döndürür. */
    fun shell(cmd: String, timeoutMs: Int = 8000): String {
        val id = nextId++
        if (!send(OPEN, id, 0, "shell:$cmd\u0000".toByteArray())) return ""
        val out = ByteArrayOutputStream()
        val end = System.currentTimeMillis() + timeoutMs
        loop@ while (System.currentTimeMillis() < end) {
            val m = recv(1000) ?: continue
            when (m.cmd) {
                WRTE -> { out.write(m.data); send(OKAY, id, m.a0) }
                CLSE -> { send(CLSE, id, m.a0); break@loop }
            }
        }
        return out.toString("UTF-8").trim()
    }
}
