// SPDX-License-Identifier: GPL-2.0-or-later
package pl.fedoramw

import android.hardware.usb.*
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun storageInterface(device: UsbDevice): UsbInterface? = (0 until device.interfaceCount)
    .map { device.getInterface(it) }.firstOrNull {
        it.interfaceClass == 8 && it.interfaceSubclass == 6 && it.interfaceProtocol == 0x50
    }
fun usbLabel(device: UsbDevice): String = listOfNotNull(device.manufacturerName, device.productName)
    .distinct().joinToString(" ").ifBlank { "Pendrive USB ${device.vendorId}:${device.productId}" }

class UsbDisk private constructor(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val bot: BotProtocol,
    override val blockSize: Int,
    override val blocks: Long
) : BlockDisk, Closeable {
    override fun write(lba: Long, data: ByteArray) {
        bounds(lba, data.size)
        bot.command(cdb(0x2a, lba, data.size), data.size, data)
    }
    override fun read(lba: Long, size: Int): ByteArray {
        bounds(lba, size)
        return bot.command(cdb(0x28, lba, size), size)
    }
    private fun bounds(lba: Long, size: Int) {
        require(size > 0 && size % blockSize == 0 && size / blockSize <= 65535)
        require(lba >= 0 && lba + size / blockSize <= blocks)
    }
    private fun cdb(op: Int, lba: Long, size: Int) = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        .put(op.toByte()).put(0).putInt(lba.toInt()).put(0).putShort((size / blockSize).toShort()).put(0).array()
    override fun sync(): CacheSyncResult = synchronizeCache(bot)
    override fun close() { connection.releaseInterface(intf); connection.close() }
    companion object {
        fun open(manager: UsbManager, device: UsbDevice): UsbDisk {
            check(manager.hasPermission(device)) { "Brak zgody Androida na dostęp do USB." }
            val intf = storageInterface(device) ?: error("Wymagany pendrive USB Mass Storage BOT/SCSI. UAS nie jest obsługiwany.")
            val endpoints = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            val input = endpoints.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN } ?: error("Brak wejścia USB.")
            val output = endpoints.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT } ?: error("Brak wyjścia USB.")
            val connection = manager.openDevice(device) ?: error("Nie można otworzyć pendrive’a.")
            try {
                check(connection.claimInterface(intf, true)) { "Pendrive jest zajęty przez inną aplikację." }
                // GET_MAX_LUN may stall for a single-LUN device; LUN 0 is always used.
                val lun = ByteArray(1)
                val count = connection.controlTransfer(0xa1, 0xfe, 0, intf.id, lun, 1, 2000)
                check(count != 1 || lun[0].toInt() == 0) { "Czytniki z wieloma nośnikami LUN nie są obsługiwane." }
                val transport = object : BulkTransport {
                    override fun send(data: ByteArray) { transfer(output, data, true) }
                    override fun receive(size: Int): ByteArray {
                        val bytes = ByteArray(size)
                        val got = transfer(input, bytes, false)
                        return bytes.copyOf(got)
                    }
                    private fun transfer(endpoint: UsbEndpoint, bytes: ByteArray, writing: Boolean): Int {
                        var offset = 0
                        while (offset < bytes.size) {
                            val length = minOf(16384, bytes.size - offset)
                            val n = connection.bulkTransfer(endpoint, bytes, offset, length, 15000)
                            if (n < 0 || (n == 0 && writing)) throw IOException("USB nie odpowiada. Sprawdź przewód OTG i zasilanie pendrive’a.")
                            if (n == 0) break // A zero-length IN packet terminates the data phase.
                            offset += n
                            if (n < length && !writing) break
                        }
                        return offset
                    }
                }
                val bot = BotProtocol(transport)
                var ready = false
                repeat(6) {
                    if (!ready) try { bot.command(byteArrayOf(0,0,0,0,0,0)); ready = true }
                    catch (e: ScsiFailure) {
                        bot.command(byteArrayOf(3,0,0,0,18,0),18,allowShortInput=true)
                        Thread.sleep(250)
                    }
                }
                check(ready) { "Pendrive nie jest gotowy." }
                val capacity = ByteBuffer.wrap(bot.command(byteArrayOf(0x25,0,0,0,0,0,0,0,0,0),8)).order(ByteOrder.BIG_ENDIAN)
                val last = capacity.int.toLong() and 0xffffffffL
                val block = capacity.int
                check(last != 0xffffffffL) { "Ten nośnik wymaga READ CAPACITY(16); wybierz mniejszy pendrive." }
                check(block in listOf(512,1024,2048,4096)) { "Nieobsługiwany rozmiar sektora: $block." }
                check(last > 0) { "Pendrive nie ma dostępnego miejsca." }
                return UsbDisk(connection,intf,bot,block,last+1)
            } catch (e: Exception) { connection.releaseInterface(intf); connection.close(); throw e }
        }
    }
}
