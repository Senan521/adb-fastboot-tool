package com.example.bootloaderaraci

import android.hardware.usb.*
import java.io.InputStream

sealed class FbResult {
    data class Okay(val msg: String) : FbResult()
    data class Fail(val msg: String) : FbResult()
    data class Data(val hexSize: String) : FbResult()
    data class Error(val msg: String) : FbResult()
}

/** Fastboot protokolü: ASCII komut gönder, INFO/OKAY/FAIL/DATA yanıtlarını oku. */
class FastbootClient(private val usb: UsbManager) {
    private var conn: UsbDeviceConnection? = null
    private var itf: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null

    fun open(device: UsbDevice): Boolean {
        close()
        val i = UsbDetect.find(device, 3) ?: return false
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

    private fun readResponse(timeoutMs: Int, onInfo: (String) -> Unit): FbResult {
        val c = conn ?: return FbResult.Error("Bağlı cihaz yok")
        val buf = ByteArray(512)
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val n = c.bulkTransfer(epIn, buf, buf.size, 1000)
            if (n < 4) continue
            val head = String(buf, 0, 4, Charsets.US_ASCII)
            val body = String(buf, 4, n - 4, Charsets.UTF_8)
            when (head) {
                "INFO" -> onInfo(body)
                "OKAY" -> return FbResult.Okay(body)
                "FAIL" -> return FbResult.Fail(body)
                "DATA" -> return FbResult.Data(body.trim())
                else -> return FbResult.Error("Beklenmeyen yanıt: $head")
            }
        }
        return FbResult.Error("Zaman aşımı")
    }

    /** Arka plan iş parçacığında çağır. */
    fun command(cmd: String, timeoutMs: Int = 5000, onInfo: (String) -> Unit = {}): FbResult {
        val c = conn ?: return FbResult.Error("Bağlı cihaz yok")
        val data = cmd.toByteArray(Charsets.US_ASCII)
        if (data.size > 64) return FbResult.Error("Komut 64 baytı aşıyor")
        if (c.bulkTransfer(epOut, data, data.size, 2000) < 0) return FbResult.Error("Komut gönderilemedi")
        return readResponse(timeoutMs, onInfo)
    }

    /** download: + flash: adımları. Sparse olmayan (düz) .img dosyaları için. */
    fun flashImage(partition: String, input: InputStream, size: Long, onProgress: (Int) -> Unit): FbResult {
        val c = conn ?: return FbResult.Error("Bağlı cihaz yok")
        val r = command("download:%08x".format(size))
        if (r !is FbResult.Data) return if (r is FbResult.Okay) FbResult.Error("Cihaz veri kabul etmedi") else r
        val buf = ByteArray(16384)
        var sent = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (c.bulkTransfer(epOut, buf, n, 5000) < n) return FbResult.Error("Veri gönderilemedi")
            sent += n
            onProgress((sent * 100 / size).toInt())
        }
        val done = readResponse(60_000) {}
        if (done !is FbResult.Okay) return done
        return command("flash:$partition", 120_000)
    }
}
