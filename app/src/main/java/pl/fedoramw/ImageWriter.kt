// SPDX-License-Identifier: GPL-2.0-or-later
package pl.fedoramw

import java.io.File
import java.io.IOException
import java.security.MessageDigest

object ImageWriter {
    fun flash(file: File, expectedHash: String, disk: BlockDisk, checkActive: () -> Unit, progress: (String, Long, Long) -> Unit) {
        val size = file.length()
        require(size > 0) { "Obraz jest pusty." }
        val padded = ((size + disk.blockSize - 1) / disk.blockSize) * disk.blockSize
        require(padded / disk.blockSize <= disk.blocks) { "Pendrive jest za mały na ten obraz." }
        // Verify the file again immediately before the first destructive command.
        val before = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1024 * 1024)
            var done = 0L
            while (true) {
                checkActive()
                val n = stream.read(buffer)
                if (n < 0) break
                before.update(buffer,0,n); done += n
                progress("Sprawdzanie ISO",done,size)
            }
        }
        require(before.digest().hex().equals(expectedHash,true)) { "Suma SHA-256 obrazu jest nieprawidłowa. Pobierz obraz ponownie." }
        val chunkSize = (1024 * 1024 / disk.blockSize) * disk.blockSize
        file.inputStream().buffered().use { stream ->
            var done = 0L
            while (done < size) {
                checkActive()
                val n = minOf(chunkSize.toLong(),size-done).toInt()
                val data = ByteArray(((n+disk.blockSize-1)/disk.blockSize)*disk.blockSize)
                var got = 0
                while (got < n) {
                    val read = stream.read(data,got,n-got)
                    if (read < 0) throw IOException("Obraz został przerwany.")
                    got += read
                }
                progress("Zapisywanie USB",done,size)
                disk.write(done/disk.blockSize,data)
                done += n; progress("Zapisywanie USB",done,size)
            }
        }
        checkActive(); disk.sync()
        val after = MessageDigest.getInstance("SHA-256")
        var done = 0L
        while (done < size) {
            checkActive()
            val n = minOf(chunkSize.toLong(),size-done).toInt()
            val data = disk.read(done/disk.blockSize,((n+disk.blockSize-1)/disk.blockSize)*disk.blockSize)
            if (data.size < n) throw IOException("Niepełny odczyt weryfikacyjny.")
            after.update(data,0,n); done += n
            progress("Weryfikacja pendrive’a",done,size)
        }
        if (!after.digest().hex().equals(expectedHash,true)) throw IOException("Weryfikacja USB nie powiodła się. Nagraj pendrive ponownie.")
    }
}
fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
