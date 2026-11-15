// SPDX-License-Identifier: GPL-2.0-or-later
package pl.fedoramw

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class WriterTest {
    private class MemoryDisk(override val blockSize: Int = 512, override val blocks: Long = 8192) : BlockDisk {
        val bytes = ByteArray((blockSize*blocks).toInt())
        var writes = 0
        var synced = false
        var corrupt = false
        var syncResult = CacheSyncResult.SYNCED
        var syncFailure: IOException? = null
        var reads = 0
        override fun write(lba: Long, data: ByteArray) { assertEquals(0,data.size%blockSize); writes++; data.copyInto(bytes,(lba*blockSize).toInt()) }
        override fun read(lba: Long, size: Int): ByteArray = bytes.copyOfRange((lba*blockSize).toInt(),(lba*blockSize).toInt()+size).also { reads++; if(corrupt) it[0]=(it[0].toInt() xor 1).toByte() }
        override fun sync(): CacheSyncResult { synced=true; syncFailure?.let { throw it }; return syncResult }
    }
    private fun image(size: Int = 1024*1024+17, body: (File, String) -> Unit) {
        val file = File.createTempFile("fedora-test", ".iso")
        try { file.writeBytes(ByteArray(size) { (it%251).toByte() }); body(file,MessageDigest.getInstance("SHA-256").digest(file.readBytes()).hex()) }
        finally { file.delete() }
    }
    @Test fun writesMultipleChunksPadsLastSectorAndVerifies() = image { file,hash ->
        val disk = MemoryDisk()
        val stages = mutableSetOf<String>()
        ImageWriter.flash(file,hash,disk,{}, { stage,_,_ -> stages += stage })
        assertEquals(2,disk.writes); assertTrue(disk.synced)
        assertArrayEquals(file.readBytes(),disk.bytes.copyOf(file.length().toInt()))
        assertEquals(0,disk.bytes[file.length().toInt()].toInt())
        assertEquals(setOf("Sprawdzanie ISO","Zapisywanie USB","Finalizowanie zapisu","Weryfikacja pendrive’a"),stages)
    }
    @Test fun invalidHashNeverWrites() = image { file,_ ->
        val disk=MemoryDisk()
        assertThrows(IllegalArgumentException::class.java) { ImageWriter.flash(file,"0".repeat(64),disk,{}, {_,_,_->}) }
        assertEquals(0,disk.writes)
    }
    @Test fun tooSmallDriveNeverWrites() = image { file,hash ->
        val disk=MemoryDisk(blocks=1)
        assertThrows(IllegalArgumentException::class.java) { ImageWriter.flash(file,hash,disk,{}, {_,_,_->}) }
        assertEquals(0,disk.writes)
    }
    @Test fun detectsCorruptedReadback() = image { file,hash ->
        val disk=MemoryDisk().apply { corrupt=true }
        assertThrows(IOException::class.java) { ImageWriter.flash(file,hash,disk,{}, {_,_,_->}) }
        assertTrue(disk.writes>0)
    }
    @Test fun cancellationBeforeWriteLeavesDriveAlone() = image { file,hash ->
        val disk=MemoryDisk()
        assertThrows(CancellationException::class.java) { ImageWriter.flash(file,hash,disk,{throw CancellationException()},{_,_,_->}) }
        assertEquals(0,disk.writes)
    }
    @Test fun supports4096ByteSectors() = image(9001) { file,hash ->
        val disk=MemoryDisk(blockSize=4096,blocks=4)
        ImageWriter.flash(file,hash,disk,{}, {_,_,_->})
        assertArrayEquals(file.readBytes(),disk.bytes.copyOf(9001)); assertEquals(1,disk.writes)
    }
    @Test fun unsupportedCacheSyncStillRequiresFullReadback() = image { file,hash ->
        val disk=MemoryDisk().apply { syncResult=CacheSyncResult.UNSUPPORTED }
        val result=ImageWriter.flash(file,hash,disk,{}, {_,_,_->})
        assertEquals(CacheSyncResult.UNSUPPORTED,result)
        assertEquals(2,disk.reads)
        assertTrue(disk.synced)
    }
    @Test fun unsupportedCacheSyncDoesNotHideCorruption() = image { file,hash ->
        val disk=MemoryDisk().apply { syncResult=CacheSyncResult.UNSUPPORTED; corrupt=true }
        assertThrows(IOException::class.java) { ImageWriter.flash(file,hash,disk,{}, {_,_,_->}) }
        assertTrue(disk.reads>0)
    }
    @Test fun realCacheSyncErrorNeverReportsSuccess() = image { file,hash ->
        val failure=IOException("medium error")
        val disk=MemoryDisk().apply { syncFailure=failure }
        val thrown=assertThrows(IOException::class.java) { ImageWriter.flash(file,hash,disk,{}, {_,_,_->}) }
        assertSame(failure,thrown); assertTrue(disk.writes>0); assertEquals(0,disk.reads)
    }
    @Test fun cancellationAfterSyncNeverReportsSuccess() = image { file,hash ->
        val disk=MemoryDisk().apply { syncResult=CacheSyncResult.UNSUPPORTED }
        assertThrows(CancellationException::class.java) {
            ImageWriter.flash(file,hash,disk,{if(disk.synced) throw CancellationException()}, {_,_,_->})
        }
        assertTrue(disk.writes>0); assertEquals(0,disk.reads)
    }
    private class Transport : BulkTransport {
        val sent=mutableListOf<ByteArray>()
        var badTag=false
        var residue=0
        var status=0
        override fun send(data: ByteArray) { sent += data }
        override fun receive(size: Int): ByteArray {
            if(size!=13) return ByteArray(size) { 7 }
            val tag=ByteBuffer.wrap(sent.first()).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
            return ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN).putInt(0x53425355).putInt(if(badTag) tag+1 else tag).putInt(residue).put(status.toByte()).array()
        }
    }
    @Test fun encodesCbwWithLittleEndianAndReadFlag() {
        val transport=Transport()
        val data=BotProtocol(transport).command(byteArrayOf(0x25,0,0,0,0,0,0,0,0,0),8)
        val cbw=ByteBuffer.wrap(transport.sent[0]).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(31,cbw.capacity());assertEquals(0x43425355,cbw.int);assertEquals(1,cbw.int);assertEquals(8,cbw.int)
        assertEquals(0x80,cbw.get().toInt() and 255);assertEquals(8,data.size)
    }
    @Test fun rejectsWrongTag() { val transport=Transport().apply { badTag=true }; assertThrows(IOException::class.java) { BotProtocol(transport).command(byteArrayOf(0)) } }
    @Test fun rejectsNonzeroResidue() { val transport=Transport().apply { residue=1 }; assertThrows(IOException::class.java) { BotProtocol(transport).command(byteArrayOf(0)) } }
    @Test fun rejectsScsiCommandFailure() { val transport=Transport().apply { status=1 }; assertThrows(ScsiFailure::class.java) { BotProtocol(transport).command(byteArrayOf(0)) } }
    @Test fun sendsWritePayloadAndOutFlag() {
        val transport=Transport(); val payload=ByteArray(512) { 42 }
        BotProtocol(transport).command(byteArrayOf(0x2a,0,0,0,0,0,0,0,1,0),512,payload)
        assertEquals(0,transport.sent[0][12].toInt()); assertArrayEquals(payload,transport.sent[1])
    }
}
