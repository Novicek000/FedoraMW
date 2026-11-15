// SPDX-License-Identifier: GPL-2.0-or-later
package pl.fedoramw

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CacheSyncTest {
    private data class Reply(val opcode: Int, val status: Int=0, val data: ByteArray?=null, val residue: Int=0)
    private class ScriptedTransport(vararg replies: Reply) : BulkTransport {
        private val queue=ArrayDeque(replies.toList())
        val commands=mutableListOf<Int>()
        private lateinit var current: Reply
        private var tag=0
        private var dataSent=false
        override fun send(data: ByteArray) {
            assertEquals(31,data.size)
            val cbw=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x43425355,cbw.int)
            tag=cbw.int
            val opcode=data[15].toInt() and 255
            commands+=opcode
            current=queue.removeFirst()
            assertEquals(current.opcode,opcode)
            dataSent=false
        }
        override fun receive(size: Int): ByteArray {
            val payload = current.data
            if(payload != null && !dataSent) {
                assertEquals(18,size)
                dataSent=true
                return payload
            }
            assertEquals(13,size)
            return ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x53425355).putInt(tag).putInt(current.residue).put(current.status.toByte()).array()
        }
    }
    private fun fixed(key: Int=5, asc: Int=0x20, ascq: Int=0, code: Int=0x70) = ByteArray(18).apply {
        this[0]=code.toByte(); this[2]=key.toByte(); this[7]=10; this[12]=asc.toByte(); this[13]=ascq.toByte()
    }
    private fun botWithSense(data: ByteArray, residue: Int=18-data.size) = BotProtocol(ScriptedTransport(
        Reply(0x35,status=1), Reply(0x03,data=data,residue=residue)))

    @Test fun successfulSyncDoesNotRequestSense() {
        val transport=ScriptedTransport(Reply(0x35))
        assertEquals(CacheSyncResult.SYNCED,synchronizeCache(BotProtocol(transport)))
        assertEquals(listOf(0x35),transport.commands)
    }
    @Test fun currentUnsupportedOpcodeAndCdbAllowVerification() {
        for(asc in listOf(0x20,0x24))
            assertEquals(CacheSyncResult.UNSUPPORTED,synchronizeCache(botWithSense(fixed(asc=asc))))
    }
    @Test fun acceptsShortDescriptorSenseWithMatchingCswResidue() {
        val descriptor=byteArrayOf(0x72,5,0x20,0,0,0,0,0)
        assertEquals(CacheSyncResult.UNSUPPORTED,synchronizeCache(botWithSense(descriptor)))
    }
    @Test fun acceptsFixedSenseWithValidBit() {
        assertEquals(CacheSyncResult.UNSUPPORTED,synchronizeCache(botWithSense(fixed(code=0xf0))))
    }
    @Test fun mediumAndHardwareErrorsKeepTheirDiagnosticsAndFail() {
        for(sense in listOf(fixed(key=3,asc=0x0c),fixed(key=4,asc=0x44))) {
            val failure=assertThrows(ScsiFailure::class.java) { synchronizeCache(botWithSense(sense)) }
            assertEquals(0x35,failure.opcode)
            assertEquals(ScsiSense.parse(sense),failure.sense)
            assertTrue(failure.message!!.contains("SYNCHRONIZE CACHE(10)"))
            assertTrue(failure.message!!.contains(failure.sense!!.description))
        }
    }
    @Test fun deferredSenseNeverDowngradesAnEarlierError() {
        for(sense in listOf(fixed(code=0x71),byteArrayOf(0x73,5,0x20,0,0,0,0,0)))
            assertThrows(ScsiFailure::class.java) { synchronizeCache(botWithSense(sense)) }
    }
    @Test fun illegalRequestAloneIsNotEnoughToIgnoreFailure() {
        for(sense in listOf(fixed(asc=0),fixed(ascq=1),fixed(key=4)))
            assertThrows(ScsiFailure::class.java) { synchronizeCache(botWithSense(sense)) }
    }
    @Test fun malformedAndTruncatedSenseRemainFailures() {
        for(sense in listOf(ByteArray(18),fixed().copyOf(8),fixed().apply { this[7]=0 }))
            assertThrows(ScsiFailure::class.java) { synchronizeCache(botWithSense(sense)) }
    }
    @Test fun failedSenseRequestRetainsOriginalFailure() {
        val transport=ScriptedTransport(Reply(0x35,status=1),Reply(3,status=1,data=ByteArray(18)))
        val failure=assertThrows(ScsiFailure::class.java) { synchronizeCache(BotProtocol(transport)) }
        assertEquals(0x35,failure.opcode)
        assertEquals(1,failure.suppressed.size)
        assertEquals(3,(failure.suppressed[0] as ScsiFailure).opcode)
    }
    @Test fun inconsistentShortTransferResidueIsNotIgnored() {
        val failure=assertThrows(ScsiFailure::class.java) {
            synchronizeCache(botWithSense(byteArrayOf(0x72,5,0x20,0,0,0,0,0),residue=0))
        }
        assertTrue(failure.suppressed.single() is IOException)
    }
    @Test fun botPhaseErrorNeverRequestsSenseOrReportsUnsupported() {
        val transport=ScriptedTransport(Reply(0x35,status=2))
        val failure=assertThrows(ScsiFailure::class.java) { synchronizeCache(BotProtocol(transport)) }
        assertEquals(2,failure.status)
        assertEquals(listOf(0x35),transport.commands)
    }
}
