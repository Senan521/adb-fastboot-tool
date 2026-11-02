package com.example.bootloaderaraci

import android.app.Application
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*

enum class Kind { SYS, CMD, OUT, OK, ERR }
data class LogLine(val time: String, val kind: Kind, val text: String)
data class UiState(
    val mode: Mode = Mode.NONE,
    val busy: Boolean = false,
    val progress: Int? = null,
    val unlocked: Boolean? = null,
    val info: List<Pair<String, String>> = emptyList(),
    val logs: List<LogLine> = emptyList(),
    val checks: Int = 0, // 5 maddelik hazırlık listesi (bit maskesi)
)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val fbc = FastbootClient(usb)
    private val adbLazy = lazy { AdbClient(usb, AdbKeys(app.filesDir)) }
    private val prefs = app.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    private val permAction = "${app.packageName}.USB_PERMISSION"
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val _ui = MutableStateFlow(UiState(checks = prefs.getInt("checks", 0)))
    val ui = _ui.asStateFlow()
    @Volatile private var permAnswer: Boolean? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                permAction -> permAnswer = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> { closeAll(); log(Kind.SYS, "Cihaz bağlantısı kesildi") }
            }
        }
    }

    init {
        val f = IntentFilter(permAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        ContextCompat.registerReceiver(app, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onCleared() { app.unregisterReceiver(receiver); closeAll() }

    // ---------- yardımcılar ----------
    fun log(kind: Kind, text: String) =
        _ui.update { it.copy(logs = it.logs + LogLine(fmt.format(Date()), kind, text)) }

    fun clearLogs() = _ui.update { it.copy(logs = emptyList()) }

    fun toggleCheck(i: Int) {
        val v = _ui.value.checks xor (1 shl i)
        prefs.edit().putInt("checks", v).apply()
        _ui.update { it.copy(checks = v) }
    }

    private fun closeAll() {
        fbc.close()
        if (adbLazy.isInitialized()) adbLazy.value.close()
        _ui.update { it.copy(mode = Mode.NONE, info = emptyList(), unlocked = null) }
    }

    private fun task(block: suspend () -> Unit) = viewModelScope.launch {
        if (_ui.value.busy) return@launch
        _ui.update { it.copy(busy = true) }
        try { block() } finally { _ui.update { it.copy(busy = false, progress = null) } }
    }

    private suspend fun io(block: () -> Unit) = withContext(Dispatchers.IO) { block() }

    // ---------- bağlantı ----------
    fun connect() = task {
        if (_ui.value.mode != Mode.NONE) return@task
        log(Kind.SYS, "USB aygıtı aranıyor…")
        val dev = usb.deviceList.values.firstOrNull { UsbDetect.modeOf(it) != null }
        if (dev == null) {
            log(Kind.ERR, "Uygun cihaz bulunamadı. Hedef telefonda USB hata ayıklamayı aç ya da telefonu bootloader moduna alıp OTG ile bağla.")
            return@task
        }
        if (ensurePermission(dev)) openInternal(dev)
    }

    fun disconnect() { closeAll(); log(Kind.SYS, "Bağlantı kapatıldı") }

    private suspend fun ensurePermission(dev: UsbDevice): Boolean {
        if (usb.hasPermission(dev)) return true
        permAnswer = null
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(dev, PendingIntent.getBroadcast(app, 0, Intent(permAction).setPackage(app.packageName), flags))
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            delay(300)
            if (usb.hasPermission(dev)) return true
            if (permAnswer == false) { log(Kind.ERR, "USB izni reddedildi"); return false }
        }
        log(Kind.ERR, "USB izni zaman aşımına uğradı")
        return false
    }

    private suspend fun openInternal(dev: UsbDevice) {
        when (UsbDetect.modeOf(dev)) {
            Mode.FASTBOOT -> {
                if (withContext(Dispatchers.IO) { fbc.open(dev) }) {
                    _ui.update { it.copy(mode = Mode.FASTBOOT) }
                    log(Kind.OK, "Fastboot cihazı bağlandı")
                } else log(Kind.ERR, "Fastboot arayüzü açılamadı. Kabloyu ve OTG desteğini kontrol et.")
            }
            Mode.ADB -> {
                log(Kind.SYS, "ADB bağlanıyor…")
                val ok = withContext(Dispatchers.IO) {
                    val a = adbLazy.value
                    a.open(dev) && a.connect { log(Kind.SYS, it) }
                }
                if (ok) {
                    _ui.update { it.copy(mode = Mode.ADB) }
                    log(Kind.OK, "ADB bağlandı")
                    adbReadInfo()
                } else {
                    adbLazy.value.close()
                    log(Kind.ERR, "ADB bağlantısı kurulamadı. Hedef telefonda USB hata ayıklama iznini onayla ve tekrar dene.")
                }
            }
            else -> {}
        }
    }

    private suspend fun reconnect(target: Mode) {
        log(Kind.SYS, "Cihazın yeniden bağlanması bekleniyor…")
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            delay(800)
            val d = usb.deviceList.values.firstOrNull { UsbDetect.modeOf(it) == target }
            if (d != null) { if (ensurePermission(d)) openInternal(d); return }
        }
        log(Kind.ERR, "Cihaz beklenen modda görünmedi. Cihaz sekmesinden elle bağlan.")
    }

    // ---------- ADB ----------
    private suspend fun adbShell(cmd: String, timeoutMs: Int = 8000): String =
        withContext(Dispatchers.IO) { adbLazy.value.shell(cmd, timeoutMs) }

    private suspend fun adbReadInfo() {
        log(Kind.CMD, "adb shell getprop")
        val out = adbShell("for p in ro.product.manufacturer ro.product.model ro.build.version.release ro.boot.flash.locked sys.oem_unlock_allowed; do echo \$p=\$(getprop \$p); done")
        val m = out.lines().mapNotNull { val i = it.indexOf('='); if (i > 0) it.substring(0, i) to it.substring(i + 1).trim() else null }.toMap()
        val locked = m["ro.boot.flash.locked"]
        val oem = m["sys.oem_unlock_allowed"]
        _ui.update {
            it.copy(
                unlocked = when (locked) { "0" -> true; "1" -> false; else -> null },
                info = listOfNotNull(
                    m["ro.product.manufacturer"]?.takeIf { v -> v.isNotEmpty() }?.let { v -> "Üretici" to v },
                    m["ro.product.model"]?.takeIf { v -> v.isNotEmpty() }?.let { v -> "Model" to v },
                    m["ro.build.version.release"]?.takeIf { v -> v.isNotEmpty() }?.let { v -> "Android" to v },
                    "Bootloader kilidi" to when (locked) { "0" -> "Açık"; "1" -> "Kilitli"; else -> "Bilinmiyor" },
                    "OEM kilit açma izni" to when (oem) { "1" -> "Açık"; "0" -> "Kapalı"; else -> "Bilinmiyor" },
                ),
            )
        }
        log(Kind.OK, "Cihaz bilgileri okundu")
    }

    fun adbRefresh() = task { adbReadInfo() }

    fun adbToBootloader() = task {
        log(Kind.CMD, "adb reboot bootloader")
        adbShell("reboot bootloader", 2500)
        log(Kind.SYS, "Cihaz bootloader moduna geçiyor…")
        closeAll()
        reconnect(Mode.FASTBOOT)
    }

    fun adbReboot(target: String) = task {
        log(Kind.CMD, "adb reboot $target".trim())
        adbShell("reboot $target".trim(), 2500)
        closeAll()
        log(Kind.SYS, "Cihaz yeniden başlıyor")
    }

    // ---------- Fastboot ----------
    private suspend fun fb(cmd: String, timeoutMs: Int = 5000, lines: MutableList<String>? = null): FbResult {
        log(Kind.CMD, "fastboot $cmd")
        val r = withContext(Dispatchers.IO) { fbc.command(cmd, timeoutMs) { lines?.add(it); log(Kind.OUT, it) } }
        when (r) {
            is FbResult.Okay -> log(Kind.OK, "OKAY ${r.msg}".trim())
            is FbResult.Fail -> log(Kind.ERR, "FAIL ${r.msg}".trim())
            is FbResult.Error -> log(Kind.ERR, r.msg)
            is FbResult.Data -> {}
        }
        return r
    }

    fun readInfo() = task {
        val l = mutableListOf<String>()
        if (fb("getvar all", 8000, l) is FbResult.Okay) {
            val m = l.mapNotNull {
                val s = it.removePrefix("(bootloader)").trim()
                val i = s.indexOf(':'); if (i > 0) s.substring(0, i).trim() to s.substring(i + 1).trim() else null
            }.toMap()
            val unlocked = m["unlocked"]?.let { it == "yes" || it == "true" }
            _ui.update {
                it.copy(
                    unlocked = unlocked,
                    info = listOfNotNull(
                        m["product"]?.let { v -> "Ürün" to v },
                        m["version-bootloader"]?.let { v -> "Bootloader" to v },
                        unlocked?.let { u -> "Kilit durumu" to if (u) "Açık" else "Kilitli" },
                        m["secure"]?.let { v -> "Güvenli önyükleme" to v },
                        m["current-slot"]?.let { v -> "Aktif slot" to v },
                    ),
                )
            }
        }
    }

    private fun unlockBlocked(lines: List<String>) =
        lines.any { Regex("""unlock_ability:\s*0""").containsMatchIn(it) || it.trim() == "0" }

    fun checkUnlockAbility() = task {
        val l = mutableListOf<String>()
        val r = fb("flashing get_unlock_ability", 5000, l)
        if (r is FbResult.Okay) {
            if (unlockBlocked(l)) log(Kind.ERR, "Kilit açma kapalı. Hedef telefonda Geliştirici seçenekleri’nden “OEM kilit açma”yı aç.")
            else log(Kind.OK, "Kilit açma izni var")
        } else log(Kind.SYS, "Bu cihaz izin kontrolünü desteklemiyor olabilir.")
    }

    /** variant: "flashing unlock" veya "oem unlock" */
    fun unlock(variant: String) = task {
        if (variant == "flashing unlock") {
            val l = mutableListOf<String>()
            if (fb("flashing get_unlock_ability", 5000, l) is FbResult.Okay && unlockBlocked(l)) {
                log(Kind.ERR, "Kilit açma kapalı. Geliştirici seçenekleri’nden “OEM kilit açma”yı aç.")
                return@task
            }
        }
        log(Kind.SYS, "Telefon ekranında onay istenirse ses tuşlarıyla onayla.")
        if (fb(variant, 60_000) is FbResult.Okay) {
            _ui.update { it.copy(unlocked = true) }
            log(Kind.SYS, "Bootloader kilidi açıldı. Telefon verileri siliyor ve yeniden başlayabilir.")
        }
    }

    fun flash(uri: Uri, partition: String) = task {
        if (_ui.value.unlocked == false) { log(Kind.ERR, "Bootloader kilitli. Önce kilidi aç."); return@task }
        val size = app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (size <= 0) { log(Kind.ERR, "Dosya boyutu okunamadı"); return@task }
        log(Kind.CMD, "fastboot flash $partition (${size / 1024} KB)")
        val r = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.use { s ->
                fbc.flashImage(partition, s, size) { p -> _ui.update { it.copy(progress = p) } }
            } ?: FbResult.Error("Dosya açılamadı")
        }
        when (r) {
            is FbResult.Okay -> log(Kind.OK, "$partition yüklendi")
            is FbResult.Fail -> log(Kind.ERR, "FAIL ${r.msg}")
            is FbResult.Error -> log(Kind.ERR, r.msg)
            is FbResult.Data -> log(Kind.ERR, "Beklenmeyen yanıt")
        }
    }

    fun fbReboot() = task {
        if (fb("reboot") is FbResult.Okay) { closeAll(); log(Kind.SYS, "Cihaz yeniden başlıyor") }
    }

    fun fbRebootBootloader() = task {
        if (fb("reboot-bootloader") is FbResult.Okay) { closeAll(); reconnect(Mode.FASTBOOT) }
    }
}
