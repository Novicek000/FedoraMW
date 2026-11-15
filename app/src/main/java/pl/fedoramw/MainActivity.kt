// SPDX-License-Identifier: GPL-2.0-or-later
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package pl.fedoramw

import android.Manifest
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if (dark) darkColorScheme(primary=Color(0xffb7c4ff),secondaryContainer=Color(0xff344923),tertiaryContainer=Color(0xff344923))
                else lightColorScheme(primary=Color(0xff2946b6),onPrimary=Color.White,primaryContainer=Color(0xffe0e5ff),onPrimaryContainer=Color(0xff142775),secondaryContainer=Color(0xffdcf5bf),onSecondaryContainer=Color(0xff263b13),tertiaryContainer=Color(0xffdcf5bf),surface=Color(0xfff8f9ff),surfaceContainer=Color(0xffeef0fa),surfaceContainerHigh=Color(0xffe6e9f4))
            MaterialExpressiveTheme(colorScheme=colors) { PocketApp() }
        }
    }
    @Composable private fun PocketApp() {
        val manager = remember { getSystemService(UsbManager::class.java) }
        val scope = rememberCoroutineScope()
        val operation by WriterState.operation.collectAsStateWithLifecycle()
        var releases by remember { mutableStateOf<List<Release>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var catalogError by remember { mutableStateOf<String?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        var devices by remember { mutableStateOf<List<UsbDevice>>(emptyList()) }
        var selectedDevice by remember { mutableStateOf<UsbDevice?>(null) }
        var capacity by remember { mutableStateOf<Long?>(null) }
        var usbError by remember { mutableStateOf<String?>(null) }
        var probing by remember { mutableStateOf(false) }
        var probeJob by remember { mutableStateOf<Job?>(null) }
        var selectedEdition by rememberSaveable { mutableStateOf<String?>(null) }
        var selectedVersion by rememberSaveable { mutableStateOf<String?>(null) }
        var confirm by remember { mutableStateOf(false) }
        var info by remember { mutableStateOf(false) }
        val editions = EditionCatalog.choices(releases)
        val edition = editions.find { it.id == selectedEdition } ?: editions.firstOrNull()
        val versions = edition?.let { EditionCatalog.versions(releases,it.id) }.orEmpty()
        val release = versions.find { it.version == selectedVersion } ?: versions.firstOrNull()
        val usbAction = "$packageName.USB_PERMISSION"
        fun scan() {
            devices = manager.deviceList.values.filter { storageInterface(it) != null }.sortedBy { it.deviceName }
            if (selectedDevice != null && devices.none { it.deviceName == selectedDevice?.deviceName }) {
                selectedDevice = null; capacity = null; confirm = false; probeJob?.cancel()
            }
        }
        fun probe(device: UsbDevice) {
            probeJob?.cancel()
            selectedDevice = device; capacity = null; usbError = null; probing = true
            probeJob = scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) { UsbDisk.open(manager,device).use { it.blocks*it.blockSize } }
                    if (selectedDevice == device) capacity = bytes
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { usbError = e.message ?: "Błąd USB." }
                finally { probing = false }
            }
        }
        fun choose(device: UsbDevice) {
            if (operation.busy || probing) return
            selectedDevice = device; capacity = null; usbError = null
            if (manager.hasPermission(device)) probe(device)
            else {
                val intent = Intent(usbAction).setPackage(packageName)
                val pending = PendingIntent.getBroadcast(this@MainActivity,device.deviceId,intent,PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                manager.requestPermission(device,pending)
            }
        }
        val latestScan by rememberUpdatedState(newValue = { scan() })
        val permissionResult by rememberUpdatedState(newValue = { granted: Boolean, device: UsbDevice? ->
            if (granted && device != null && !operation.busy) probe(device)
            else if (!operation.busy) usbError = "Nie udzielono dostępu do pendrive’a. Wybierz go ponownie."
        })
        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == usbAction) {
                        val device = if(Build.VERSION.SDK_INT>=33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE,UsbDevice::class.java)
                            else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        permissionResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false),device)
                    }
                    latestScan()
                }
            }
            val filter = IntentFilter().apply { addAction(usbAction); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
            ContextCompat.registerReceiver(this@MainActivity,receiver,filter,ContextCompat.RECEIVER_EXPORTED)
            scan()
            onDispose { unregisterReceiver(receiver) }
        }
        LaunchedEffect(refresh) {
            loading = true; catalogError = null
            try { releases = withContext(Dispatchers.IO) { FedoraRepository.catalog() }; if(releases.isEmpty()) catalogError="Katalog nie zawiera stabilnych obrazów ISO." }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { catalogError = e.message }
            finally { loading = false }
        }
        // Re-probe after an operation: never retain readiness for a reattached target.
        LaunchedEffect(operation.busy) { if(!operation.busy) { scan(); if(selectedDevice != null && !probing) probe(selectedDevice!!) } }
        val colors = MaterialTheme.colorScheme
        Scaffold(containerColor=colors.surface, topBar={
            CenterAlignedTopAppBar(title={ Text("FedoraMW",fontWeight=FontWeight.Bold,letterSpacing=(-0.6).sp) },
                navigationIcon={ Icon(Icons.Rounded.OfflineBolt,null,tint=colors.primary,modifier=Modifier.padding(start=20.dp).size(28.dp)) },
                actions={ IconButton(onClick={info=true}) { Icon(Icons.Rounded.Info,"O aplikacji") } })
        }) { padding ->
            LazyColumn(modifier=Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                item {
                    Surface(color=colors.primaryContainer,shape=RoundedCornerShape(32.dp,32.dp,32.dp,8.dp)) {
                        Column(Modifier.fillMaxWidth().padding(26.dp)) {
                            Surface(color=colors.primary,shape=RoundedCornerShape(20.dp)) { Text("LINUX. Z TELEFONU.",Modifier.padding(horizontal=12.dp,vertical=7.dp),color=colors.onPrimary,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold,letterSpacing=1.sp) }
                            Spacer(Modifier.height(22.dp))
                            Text("Mały pendrive.\nWielkie możliwości.",fontSize=38.sp,lineHeight=40.sp,fontWeight=FontWeight.ExtraBold,letterSpacing=(-1.5).sp,color=colors.onPrimaryContainer)
                            Spacer(Modifier.height(14.dp))
                            Text("Pobierz Fedorę. Podłącz USB.\nZabierz swój system ze sobą.",style=MaterialTheme.typography.bodyLarge,color=colors.onPrimaryContainer)
                            Spacer(Modifier.height(22.dp))
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                AssistChip(onClick={info=true},label={ Text("Bez roota") },leadingIcon={Icon(Icons.Rounded.VerifiedUser,null,Modifier.size(18.dp))})
                                AssistChip(onClick={info=true},label={ Text("USB OTG") },leadingIcon={Icon(Icons.Rounded.Usb,null,Modifier.size(18.dp))})
                            }
                        }
                    }
                }
                item { StepHeading("01","Wybierz swoją Fedorę","Obraz dla komputera, na którym uruchomisz system") }
                item {
                    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        if(loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Pobieranie katalogu Fedory…",style=MaterialTheme.typography.bodySmall) }
                        catalogError?.let { message ->
                            StatusCard(message,true)
                            OutlinedButton(onClick={refresh++},enabled=!loading) { Icon(Icons.Rounded.Refresh,null); Text("Spróbuj ponownie") }
                        }
                        if (edition != null && release != null) {
                            var editionExpanded by remember { mutableStateOf(false) }
                            var versionExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded=editionExpanded,onExpandedChange={ if(!operation.busy) editionExpanded=it }) {
                                OutlinedTextField(value=edition.name,onValueChange={},readOnly=true,label={Text("Edycja")},
                                    trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(editionExpanded)},
                                    modifier=Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                    shape=RoundedCornerShape(20.dp),singleLine=true,enabled=!operation.busy)
                                ExposedDropdownMenu(expanded=editionExpanded,onDismissRequest={editionExpanded=false}) {
                                    EditionGroup.entries.forEach { group ->
                                        val members = editions.filter { it.group == group }
                                        if(members.isNotEmpty()) {
                                            Text(group.label,modifier=Modifier.padding(horizontal=16.dp,vertical=12.dp),
                                                style=MaterialTheme.typography.labelLarge,color=colors.primary,fontWeight=FontWeight.Bold)
                                            members.forEach { choice ->
                                                DropdownMenuItem(text={Text(choice.name)},
                                                    leadingIcon={if(choice.id==edition.id) Icon(Icons.Rounded.Check,null) else Spacer(Modifier.size(24.dp))},
                                                    onClick={selectedEdition=choice.id;selectedVersion=null;editionExpanded=false})
                                            }
                                            if(group != editions.last().group) HorizontalDivider(Modifier.padding(vertical=4.dp))
                                        }
                                    }
                                }
                            }
                            ExposedDropdownMenuBox(expanded=versionExpanded,onExpandedChange={ if(!operation.busy) versionExpanded=it }) {
                                OutlinedTextField(value="Fedora ${release.version}",onValueChange={},readOnly=true,label={Text("Wydanie")},
                                    trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(versionExpanded)},
                                    modifier=Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                                    shape=RoundedCornerShape(20.dp),singleLine=true,enabled=!operation.busy)
                                ExposedDropdownMenu(expanded=versionExpanded,onDismissRequest={versionExpanded=false}) {
                                    versions.forEach { choice ->
                                        DropdownMenuItem(text={Text("Fedora ${choice.version}")},
                                            trailingIcon={if(choice.version==versions.first().version) Text("Najnowsze",style=MaterialTheme.typography.labelSmall)},
                                            onClick={selectedVersion=choice.version;versionExpanded=false})
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Text("${formatBytes(release.size)} · ISO · Intel / AMD",style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant)
                                Text("SHA-256 ✓",style=MaterialTheme.typography.labelMedium,color=colors.primary)
                            }
                        }
                    }
                }
                item { StepHeading("02","Podłącz pendrive","Przez przejściówkę USB OTG lub złącze USB-C") }
                item {
                    Surface(color=if(capacity!=null) colors.secondaryContainer else colors.surfaceContainer,shape=RoundedCornerShape(26.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                                FilledTonalIconButton(onClick={scan()},enabled=!operation.busy&&!probing,modifier=Modifier.size(52.dp)) { Icon(Icons.Rounded.Usb,"Odśwież urządzenia USB") }
                                Column(Modifier.weight(1f)) {
                                    Text(if(capacity!=null) "Pendrive połączony" else "Czekam na Twój USB",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
                                    Text(if(capacity!=null) "${formatBytes(capacity!!)} · gotowy do zapisu" else "Podłącz nośnik i zezwól na dostęp",style=MaterialTheme.typography.bodySmall)
                                }
                            }
                            devices.forEach { device ->
                                OutlinedButton(onClick={choose(device)},enabled=!operation.busy&&!probing,modifier=Modifier.fillMaxWidth()) {
                                    if(selectedDevice==device && capacity!=null) Icon(Icons.Rounded.CheckCircle,null,Modifier.size(18.dp))
                                    Text(usbLabel(device),Modifier.padding(start=8.dp),maxLines=2)
                                }
                            }
                            if(probing) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if(devices.isEmpty()) Text("Wykrywane są nośniki USB Mass Storage (BOT).",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                            usbError?.let { Text(it,color=colors.error,style=MaterialTheme.typography.bodySmall) }
                            if(capacity!=null && release!=null && capacity!! < release.size) Text("Ten pendrive jest za mały na wybrany obraz.",color=colors.error)
                        }
                    }
                }
                item {
                    AnimatedContent(targetState=operation.busy,label="operation") { busy ->
                        if(busy) Surface(color=colors.surfaceContainerHigh,shape=RoundedCornerShape(26.dp)) {
                            Column(Modifier.fillMaxWidth().padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    LoadingIndicator(Modifier.size(42.dp))
                                    Text(operation.stage,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
                                }
                                LinearWavyProgressIndicator(progress={operation.fraction},modifier=Modifier.fillMaxWidth())
                                Text("${(operation.fraction*100).toInt()}% · ${formatBytes(operation.done)} / ${formatBytes(operation.total)}",style=MaterialTheme.typography.labelMedium)
                                Text("Nie odłączaj pendrive’a. Możesz wygasić ekran.",style=MaterialTheme.typography.bodySmall)
                                TextButton(onClick={startService(Intent(this@MainActivity,WriterService::class.java).setAction("cancel"))}) { Text("Anuluj operację") }
                            }
                        } else Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            if(operation.error || operation.success) StatusCard(operation.stage,operation.error)
                            Button(onClick={confirm=true},enabled=release!=null && capacity!=null && capacity!! >= release.size && !probing,
                                modifier=Modifier.fillMaxWidth().height(64.dp),shape=RoundedCornerShape(24.dp)) {
                                Icon(Icons.Rounded.Download,null); Spacer(Modifier.width(12.dp)); Text("Pobierz i nagraj Fedorę",fontSize=17.sp,fontWeight=FontWeight.Bold)
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.padding(horizontal=6.dp)) {
                        Icon(Icons.Rounded.Shield,null,tint=colors.onSurfaceVariant,modifier=Modifier.size(18.dp))
                        Text("Oficjalne obrazy Fedory. Kontrola sumy ISO i pełna weryfikacja USB po zapisie.",style=MaterialTheme.typography.bodySmall,color=colors.onSurfaceVariant)
                    }
                }
            }
        }
        if(confirm && release!=null && selectedDevice!=null) AlertDialog(onDismissRequest={confirm=false},icon={Icon(Icons.Rounded.Warning,null)},title={Text("Wymazać pendrive?")},text={
            Text("${usbLabel(selectedDevice!!)} · ${formatBytes(capacity ?: 0)}\n\nWszystkie dane i partycje na tym nośniku zostaną zastąpione obrazem Fedora ${release.version} ${release.edition} (${release.arch}).\n\nZrób kopię plików. Pobieranie wykorzysta ${formatBytes(release.size)} transferu i miejsca w telefonie.")
        },confirmButton={ TextButton(onClick={
            confirm=false
            val r=release; val d=selectedDevice ?: return@TextButton
            if(Build.VERSION.SDK_INT>=33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            val intent = Intent(this@MainActivity,WriterService::class.java).putExtra("device",d.deviceName)
                .putExtra("version",r.version).putExtra("edition",r.edition).putExtra("arch",r.arch).putExtra("url",r.url).putExtra("hash",r.sha256).putExtra("size",r.size)
            ContextCompat.startForegroundService(this@MainActivity,intent)
        }) { Text("Wymaż i nagraj",color=colors.error) } },dismissButton={TextButton(onClick={confirm=false}) { Text("Wróć") }})
        if(info) AlertDialog(onDismissRequest={info=false},title={Text("FedoraMW")},text={Text("Niezależna aplikacja inspirowana Fedora Media Writer. Android 8+, USB Host / OTG i pendrive BOT/SCSI. Bez roota.\n\nWybierz architekturę docelowego komputera, nie telefonu. Nagranie USB nie instaluje Fedory na telefonie.\n\nObrazy i sumy SHA-256 pochodzą z fedoraproject.org przez HTTPS. Nie wykonujemy weryfikacji podpisu OpenPGP.\n\nPo udanym zapisie i weryfikacji USB pobrane ISO jest automatycznie usuwane z telefonu. Po błędzie lub anulowaniu pozostaje do ponownej próby. Możesz je też usunąć przyciskiem poniżej.")},confirmButton={TextButton(onClick={info=false}) {Text("Rozumiem")}},dismissButton={TextButton(enabled=!operation.busy,onClick={
            scope.launch { withContext(Dispatchers.IO) { java.io.File(filesDir,"images").deleteRecursively() }; info=false }
        }) {Text("Usuń pobrane ISO")}})
    }
}
@Composable private fun StepHeading(number: String, title: String, subtitle: String) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(14.dp)) {
            Text(number,Modifier.padding(12.dp),fontWeight=FontWeight.ExtraBold,color=MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column { Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold); Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable private fun StatusCard(message: String, error: Boolean) {
    Surface(color=if(error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Icon(if(error) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,null)
            Text(message,style=MaterialTheme.typography.bodyMedium)
        }
    }
}
