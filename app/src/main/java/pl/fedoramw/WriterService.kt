// SPDX-License-Identifier: GPL-2.0-or-later
package pl.fedoramw

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

data class Operation(val busy: Boolean = false, val stage: String = "Gotowy do działania", val done: Long = 0, val total: Long = 0, val error: Boolean = false, val success: Boolean = false) {
    val fraction get() = if(total > 0) (done.toFloat()/total).coerceIn(0f,1f) else 0f
}
object WriterState { val operation = MutableStateFlow(Operation()) }

class WriterService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var deviceName: String? = null
    private var wake: PowerManager.WakeLock? = null
    private var writing = false
    private var writeComplete = false
    private var lastUpdate = 0L
    private var registered = false
    private val detached = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (device?.deviceName == deviceName) job?.cancel(CancellationException("Odłączono pendrive."))
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") { job?.cancel(CancellationException("Anulowano.")); if(job == null) stopSelf(); return START_NOT_STICKY }
        if (job?.isActive == true) return START_NOT_STICKY
        val manager = getSystemService(UsbManager::class.java)
        val name = intent?.getStringExtra("device")
        val device = manager.deviceList[name]
        val release = intent?.let { i -> runCatching {
            Release(i.getStringExtra("version")!!,i.getStringExtra("edition")!!,i.getStringExtra("arch")!!,
                i.getStringExtra("url")!!,i.getStringExtra("hash")!!,i.getLongExtra("size",0))
        }.getOrNull() }
        if (device == null || release == null || !manager.hasPermission(device)) {
            WriterState.operation.value = Operation(stage="Podłącz pendrive i udziel dostępu USB ponownie.",error=true)
            stopSelf(); return START_NOT_STICKY
        }
        deviceName = name
        writing = false
        writeComplete = false
        WriterState.operation.value = Operation(busy=true,stage="Przygotowanie")
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("write","Pobieranie i zapis USB",NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) startForeground(1,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1,notification())
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(detached,IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),RECEIVER_EXPORTED)
        else registerReceiver(detached,IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        registered = true
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"FedoraMW:write").apply { acquire(6*60*60*1000L) }
        job = scope.launch {
            val active: () -> Unit = { ensureActive() }
            try {
                // A read-only preflight catches an undersized target before a large download.
                UsbDisk.open(manager,device).use { disk ->
                    check((release.size+disk.blockSize-1)/disk.blockSize <= disk.blocks) { "Pendrive jest za mały." }
                }
                val image = FedoraRepository.download(release,java.io.File(filesDir,"images"),active) { done,total -> update("Pobieranie ISO",done,total) }
                active()
                check(manager.deviceList[name] == device && manager.hasPermission(device)) { "Pendrive został odłączony. Wybierz go ponownie." }
                val syncResult = UsbDisk.open(manager,device).use { disk ->
                    ImageWriter.flash(image,release.sha256,disk,active) { stage,done,total ->
                        if (stage == "Zapisywanie USB") writing = true
                        if (stage == "Finalizowanie zapisu") writeComplete = true
                        update(stage,done,total)
                    }
                }
                val message = if(syncResult == CacheSyncResult.UNSUPPORTED)
                    "Pendrive jest gotowy! Zapis zweryfikowany. Ten nośnik nie obsługuje potwierdzenia opróżnienia bufora."
                    else "Pendrive jest gotowy! Zapis zweryfikowany."
                WriterState.operation.value = Operation(stage=message,done=release.size,total=release.size,success=true)
            } catch (e: CancellationException) {
                WriterState.operation.value = Operation(stage=(e.message ?: "Anulowano.") + if(writeComplete) " Zapis danych zakończony; weryfikacja nie została ukończona."
                    else if(writing) " Zapis mógł zostać przerwany — nagraj pendrive ponownie." else " Możesz spróbować ponownie.",error=true)
            } catch (e: Exception) {
                WriterState.operation.value = Operation(stage=(e.message ?: "Nieznany błąd.") + if(writeComplete) " Zapis danych zakończony; nie potwierdzono poprawności nośnika."
                    else if(writing) " Zapis mógł zostać przerwany — nagraj pendrive ponownie." else "",error=true)
            } finally {
                if (wake?.isHeld == true) wake?.release()
                if (registered) { unregisterReceiver(detached); registered = false }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun update(stage: String, done: Long, total: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now-lastUpdate < 350 && WriterState.operation.value.stage == stage && done != total) return
        lastUpdate = now
        WriterState.operation.value = Operation(true,stage,done,total)
        getSystemService(NotificationManager::class.java).notify(1,notification())
    }
    private fun notification(): Notification {
        val state = WriterState.operation.value
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this,1,Intent(this,WriterService::class.java).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,"write").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("FedoraMW").setContentText(state.stage).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(100,(state.fraction*100).toInt(),state.total==0L)
            .addAction(0,"Anuluj",cancel).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { job?.cancel(CancellationException("Android zakończył limit czasu pracy w tle.")); stopSelf() }
    override fun onDestroy() {
        scope.cancel()
        if (registered) { unregisterReceiver(detached); registered = false }
        if (wake?.isHeld == true) wake?.release()
        super.onDestroy()
    }
}
