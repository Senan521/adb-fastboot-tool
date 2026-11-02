
package com.example.bootloaderaraci

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { AppScreen(vm) } }
        if (savedInstanceState == null && intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) vm.connect()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) vm.connect()
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

val CHECKS = listOf(
    "USB hata ayıklama açık",
    "OEM kilit açma etkin (Geliştirici seçenekleri)",
    "Yedek alındı",
    "Şarj en az %50",
    "OTG kablosu takılı",
)

@Composable
fun AppScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var unlockDlg by rememberSaveable { mutableStateOf(false) }
    var flashUri by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { flashUri = it }
    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("Cihaz" to UsbIcon, "Komutlar" to Icons.Default.Build, "Konsol" to Icons.Default.Menu, "Hazırlık" to Icons.Default.Check)
                    .forEachIndexed { i, (l, ic) ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(ic, null) }, label = { Text(l) })
                    }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> DeviceTab(ui, vm)
                1 -> CommandsTab(ui, vm, { unlockDlg = true }, { picker.launch(arrayOf("*/*")) })
                2 -> ConsoleTab(ui, vm)
                else -> PrepTab(ui, vm)
            }
        }
    }
    if (unlockDlg) UnlockDialog(missing = 5 - Integer.bitCount(ui.checks), onDismiss = { unlockDlg = false }) { v -> unlockDlg = false; vm.unlock(v) }
    flashUri?.let { u -> FlashDialog(onDismiss = { flashUri = null }) { part -> flashUri = null; vm.flash(u, part) } }
}

fun nextStep(ui: UiState): Pair<String, String> {
    val missing = 5 - Integer.bitCount(ui.checks)
    return when {
        ui.mode == Mode.NONE && missing > 0 -> "Önce hazırlığı tamamla" to "Hazırlık sekmesinde $missing madde eksik. Yedek almadan devam etme."
        ui.mode == Mode.NONE -> "Hazırsın" to "“Cihaz bağla” düğmesine bas."
        ui.mode == Mode.ADB -> "Sıradaki adım" to "Komutlar sekmesinden bootloader’a yeniden başlat."
        ui.unlocked == true -> "Sıradaki adım" to "Recovery dosyanı (.img) seçip yükleyebilirsin."
        else -> "Sıradaki adım" to "Bilgileri oku ve kilit açma iznini kontrol et."
    }
}

@Composable
fun DeviceTab(ui: UiState, vm: MainViewModel) {
    val r by animateDpAsState(
        when (ui.mode) { Mode.NONE -> 48.dp; Mode.ADB -> 30.dp; Mode.FASTBOOT -> 14.dp },
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow), label = "shape",
    )
    val (title, sub) = when (ui.mode) {
        Mode.NONE -> "Bağlı cihaz yok" to "Hedef telefonu OTG ile bağla ve izin ver."
        Mode.ADB -> "Android bağlı" to "ADB hazır."
        Mode.FASTBOOT -> "Fastboot modunda" to "Bootloader komutlarını gönderebilirsin."
    }
    val (nt, nb) = nextStep(ui)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("ADB&Fastboot Tool", fontSize = 26.sp, fontWeight = FontWeight.Black) }
        item {
            Surface(shape = RoundedCornerShape(40.dp, 40.dp, 40.dp, 12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(24.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(r)), contentAlignment = Alignment.Center) {
                        if (ui.busy) CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary) else Icon(UsbIcon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Text(title, fontSize = 30.sp, fontWeight = FontWeight.Black, lineHeight = 34.sp)
                    Text(sub)
                    ui.progress?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) }
                    Button(onClick = { if (ui.mode == Mode.NONE) vm.connect() else vm.disconnect() }, enabled = !ui.busy) {
                        Text(if (ui.mode == Mode.NONE) "Cihaz bağla" else "Bağlantıyı kes")
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(28.dp, 28.dp, 12.dp, 28.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                Column(Modifier.padding(18.dp).fillMaxWidth()) { Text(nt, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp); Text(nb) }
            }
        }
        if (ui.info.isNotEmpty()) {
            item { Text("Cihaz bilgisi", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold) }
            items(ui.info) { (k, v) ->
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth()) {
                        Text(k, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun ActionCard(icon: ImageVector, title: String, desc: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val c = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    Card(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(28.dp)) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).background(c, if (danger) RoundedCornerShape(26.dp) else RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) { Icon(icon, null) }
            Column { Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp); Text(desc, fontSize = 14.sp) }
        }
    }
}

@Composable
fun CommandsTab(ui: UiState, vm: MainViewModel, onUnlock: () -> Unit, onFlash: () -> Unit) {
    val on = !ui.busy
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Komutlar", fontSize = 26.sp, fontWeight = FontWeight.Black) }
        when (ui.mode) {
            Mode.NONE -> item { Text("Komutlar için önce Cihaz sekmesinden bağlan.") }
            Mode.ADB -> {
                item { ActionCard(Icons.Default.PlayArrow, "Bootloader’a yeniden başlat", "adb reboot bootloader · otomatik yeniden bağlanır", on) { vm.adbToBootloader() } }
                item { ActionCard(Icons.Default.Refresh, "Recovery’ye yeniden başlat", "adb reboot recovery", on) { vm.adbReboot("recovery") } }
                item { ActionCard(Icons.Default.Refresh, "Cihazı yeniden başlat", "Sistemi normal aç", on) { vm.adbReboot("") } }
                item { ActionCard(Icons.Default.Info, "Bilgileri yenile", "getprop", on) { vm.adbRefresh() } }
            }
            Mode.FASTBOOT -> {
                item { ActionCard(Icons.Default.Info, "Cihaz bilgilerini oku", "getvar all", on) { vm.readInfo() } }
                item { ActionCard(Icons.Default.Search, "Kilit açma iznini kontrol et", "flashing get_unlock_ability", on) { vm.checkUnlockAbility() } }
                item { ActionCard(Icons.Default.Lock, "Bootloader kilidini aç", "Tüm verileri siler", on, true, onUnlock) }
                item { ActionCard(Icons.Default.Add, "Görüntü yükle", "recovery, boot veya vendor_boot (.img)", on, onClick = onFlash) }
                item { ActionCard(Icons.Default.Refresh, "Bootloader’ı yeniden başlat", "reboot-bootloader", on) { vm.fbRebootBootloader() } }
                item { ActionCard(Icons.Default.PlayArrow, "Cihazı yeniden başlat", "Sistemi normal aç", on) { vm.fbReboot() } }
            }
        }
    }
}

@Composable
fun ConsoleTab(ui: UiState, vm: MainViewModel) {
    val ctx = LocalContext.current
    val state = rememberLazyListState()
    LaunchedEffect(ui.logs.size) { if (ui.logs.isNotEmpty()) state.animateScrollToItem(ui.logs.size - 1) }
    val text = ui.logs.joinToString("\n") { "${it.time} ${if (it.kind == Kind.CMD) "$ " else ""}${it.text}" }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Konsol", fontSize = 26.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(12.dp))
        Surface(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            LazyColumn(Modifier.padding(16.dp), state = state) {
                if (ui.logs.isEmpty()) item { Text("Henüz kayıt yok.") }
                items(ui.logs) { l ->
                    val col = when (l.kind) {
                        Kind.ERR -> MaterialTheme.colorScheme.error
                        Kind.CMD -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text("${l.time}  ${if (l.kind == Kind.CMD) "$ " else ""}${l.text}", color = col, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(enabled = ui.logs.isNotEmpty(), onClick = {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("log", text))
            }) { Text("Kopyala") }
            FilledTonalButton(enabled = ui.logs.isNotEmpty(), onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, null))
            }) { Text("Paylaş") }
            TextButton(enabled = ui.logs.isNotEmpty(), onClick = { vm.clearLogs() }) { Text("Temizle") }
        }
    }
}

@Composable
fun PrepTab(ui: UiState, vm: MainViewModel) {
    val done = Integer.bitCount(ui.checks)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Hazırlık", fontSize = 26.sp, fontWeight = FontWeight.Black) }
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(18.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("$done/5 tamamlandı", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    LinearProgressIndicator(progress = { done / 5f }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        items(CHECKS.size) { i ->
            val on = ui.checks and (1 shl i) != 0
            Surface(shape = RoundedCornerShape(20.dp), color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.clickable { vm.toggleCheck(i) }) {
                Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Checkbox(on, null); Text(CHECKS[i], fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item { Text("Marka notları", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 16.dp)) }
        val notes = listOf(
            "Pixel, Nothing, OnePlus" to "Çoğu zaman standart fastboot yeterli. Geliştirici seçeneklerinde “OEM kilit açma” açık olmalı.",
            "Xiaomi, Redmi, POCO" to "Mi hesabı ve resmî Mi Unlock aracı gerekir, ayrıca bekleme süresi olabilir. Yalnızca fastboot komutu yetmez.",
            "Motorola" to "Çoğu modelde üreticiden alınan kilit açma kodu gerekir.",
            "Samsung" to "Fastboot yerine Download modu kullanır. Bu uygulama Samsung için uygun değildir.",
            "Huawei, Honor" to "Resmî kilit açma kodları kapatıldı. Bu uygulama yardımcı olmayabilir.",
        )
        items(notes) { (t, d) ->
            var open by remember { mutableStateOf(false) }
            Card(onClick = { open = !open }, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(t, fontWeight = FontWeight.ExtraBold)
                    if (open) Text(d, Modifier.padding(top = 6.dp))
                }
            }
        }
        item { Text("Kilit açmak garantiyi etkileyebilir ve bazı bankacılık uygulamalarını kısıtlayabilir.", Modifier.padding(top = 12.dp), fontSize = 13.sp) }
    }
}

@Composable
fun UnlockDialog(missing: Int, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var ack by remember { mutableStateOf(false) }
    var variant by remember { mutableStateOf("flashing unlock") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, null) },
        title = { Text("Verilerin silinecek") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Kilit açılınca telefon fabrika ayarlarına döner, garanti etkilenebilir. Telefon ekranında onay istenirse ses tuşlarıyla onayla.")
                if (missing > 0) Text("Hazırlık listesinde $missing madde eksik.", color = MaterialTheme.colorScheme.error)
                listOf("flashing unlock", "oem unlock").forEach { v ->
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(variant == v, { variant = v }); Text(v) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(ack, { ack = it }); Text("Yedek aldım, silinmesini kabul ediyorum") }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(variant) }, enabled = ack) { Text("Kilidi aç") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
fun FlashDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var ack by remember { mutableStateOf(false) }
    var part by remember { mutableStateOf("recovery") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, null) },
        title = { Text("Görüntüyü yükle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Yanlış dosya telefonu açılmaz hale getirebilir. Dosya tam bu model için olmalı.")
                listOf("recovery", "boot", "vendor_boot").forEach { v ->
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(part == v, { part = v }); Text(v) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(ack, { ack = it }); Text("Dosyanın bu cihaza ait olduğundan eminim") }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(part) }, enabled = ack) { Text("Yükle") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

val UsbIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("Usb", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            androidx.compose.ui.graphics.vector.addPathNodes("M15 7v4h1v2h-3V5h2l-3-4-3 4h2v8H8v-2.07c.7-.37 1.2-1.08 1.2-1.93 0-1.21-.99-2.2-2.2-2.2-1.21 0-2.2.99-2.2 2.2 0 .85.5 1.56 1.2 1.93V13c0 1.11.89 2 2 2h3v3.05c-.71.37-1.2 1.1-1.2 1.95 0 1.22.99 2.2 2.2 2.2 1.21 0 2.2-.98 2.2-2.2 0-.85-.49-1.58-1.2-1.95V15h3c1.11 0 2-.89 2-2v-2h1V7h-4z"),
            fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
        )
        .build()
}
