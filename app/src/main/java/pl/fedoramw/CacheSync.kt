// SPDX-License-Identifier: GPL-2.0-or-later
package pl.fedoramw

enum class CacheSyncResult { SYNCED, UNSUPPORTED }

data class ScsiSense(val key: Int, val asc: Int, val ascq: Int, val current: Boolean) {
    val description get() = "%02X/%02X/%02X".format(java.util.Locale.ROOT,key,asc,ascq)
    // Only a current ILLEGAL REQUEST for an unsupported opcode/CDB qualifies.
    // Medium, hardware, deferred and unknown errors must never be downgraded.
    val unsupportedCommand get() = current && key == 0x05 && asc in listOf(0x20,0x24) && ascq == 0
    companion object {
        fun parse(data: ByteArray): ScsiSense? {
            fun byte(i: Int) = data[i].toInt() and 255
            if(data.isEmpty()) return null
            return when(val code = byte(0) and 0x7f) {
                0x70,0x71 -> if(data.size >= 14 && byte(7) >= 6)
                    ScsiSense(byte(2) and 15,byte(12),byte(13),code == 0x70) else null
                0x72,0x73 -> if(data.size >= 8)
                    ScsiSense(byte(1) and 15,byte(2),byte(3),code == 0x72) else null
                else -> null
            }
        }
    }
}

fun synchronizeCache(bot: BotProtocol): CacheSyncResult {
    try {
        bot.command(byteArrayOf(0x35,0,0,0,0,0,0,0,0,0))
        return CacheSyncResult.SYNCED
    } catch (failure: ScsiFailure) {
        if(failure.status != 1) throw failure // BOT phase errors require recovery, not REQUEST SENSE.
        val sense = try {
            ScsiSense.parse(bot.command(byteArrayOf(3,0,0,0,18,0),18,allowShortInput=true))
        } catch (diagnosticFailure: Exception) {
            failure.addSuppressed(diagnosticFailure)
            throw failure
        }
        if(sense?.unsupportedCommand == true) return CacheSyncResult.UNSUPPORTED
        throw ScsiFailure(failure.status,failure.opcode,sense,failure)
    }
}
