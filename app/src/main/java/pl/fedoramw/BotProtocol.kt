// SPDX-License-Identifier: GPL-2.0-or-later
package pl.fedoramw

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface BulkTransport {
    fun send(data: ByteArray)
    fun receive(size: Int): ByteArray
}
class ScsiFailure(val status: Int, val opcode: Int = -1, val sense: ScsiSense? = null, cause: Throwable? = null) :
    IOException("Pendrive odrzucił polecenie SCSI ${scsiCommandName(opcode)} (status $status" +
        (sense?.let { "; sense ${it.description}" } ?: "") + ").", cause)

private fun scsiCommandName(opcode: Int): String = when(opcode) {
    0x35 -> "SYNCHRONIZE CACHE(10)"
    0x28 -> "READ(10)"
    0x2a -> "WRITE(10)"
    0x03 -> "REQUEST SENSE"
    0x25 -> "READ CAPACITY(10)"
    0x00 -> "TEST UNIT READY"
    else -> "0x%02X".format(java.util.Locale.ROOT,opcode)
}

/** USB Mass Storage Bulk-Only Transport. Each command is serialized by its owner. */
class BotProtocol(private val transport: BulkTransport) {
    private var tag = 0
    fun command(cdb: ByteArray, size: Int = 0, output: ByteArray? = null, allowShortInput: Boolean = false): ByteArray {
        require(cdb.size in 1..16 && size >= 0)
        require(output == null || output.size == size)
        require(!allowShortInput || (output == null && size > 0))
        val id = ++tag
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x43425355).putInt(id).putInt(size)
            .put(if (output == null && size > 0) 0x80.toByte() else 0)
            .put(0).put(cdb.size.toByte()).put(cdb).array()
        transport.send(cbw)
        val input = if (output != null) { transport.send(output); ByteArray(0) }
                    else if (size > 0) transport.receive(size) else ByteArray(0)
        val status = transport.receive(13)
        if (status.size != 13) throw IOException("Niepełny status USB.")
        val csw = ByteBuffer.wrap(status).order(ByteOrder.LITTLE_ENDIAN)
        if (csw.int != 0x53425355 || csw.int != id) throw IOException("Błędny identyfikator odpowiedzi USB. Odłącz i podłącz pendrive.")
        val residue = csw.int.toLong() and 0xffffffffL
        val result = csw.get().toInt() and 255
        if (result != 0) throw ScsiFailure(result,cdb[0].toInt() and 255)
        val validLength = if(allowShortInput) input.size <= size && residue == (size-input.size).toLong()
                          else residue == 0L && (output != null || input.size == size)
        if (!validLength) throw IOException("Niepełny transfer USB.")
        return input
    }
}

interface BlockDisk {
    val blockSize: Int
    val blocks: Long
    fun write(lba: Long, data: ByteArray)
    fun read(lba: Long, size: Int): ByteArray
    fun sync(): CacheSyncResult
}
