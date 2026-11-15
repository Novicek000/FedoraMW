// SPDX-License-Identifier: GPL-2.0-or-later
package pl.fedoramw

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class Release(val version: String, val edition: String, val arch: String, val url: String, val sha256: String, val size: Long, val variant: String = "") {
    val id get() = "$version/$edition/$arch/$sha256"
}
object FedoraRepository {
    private val client = OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            if (!chain.request().url.isHttps) throw IOException("Odrzucono przekierowanie bez HTTPS.")
            chain.proceed(chain.request())
        }.build()
    private fun request(url: String) = client.newCall(Request.Builder().url(url).header("User-Agent","FedoraMW/0.1 Android (unofficial)").build()).execute()
    fun catalog(): List<Release> = request("https://fedoraproject.org/releases.json").use { response ->
        if (!response.isSuccessful) throw IOException("Katalog Fedory: HTTP ${response.code}")
        val json = JSONArray(response.body?.string() ?: error("Pusty katalog Fedory."))
        (0 until json.length()).mapNotNull { i ->
            val item = json.getJSONObject(i)
            val version = item.optString("version")
            val url = item.optString("link")
            val arch = item.optString("arch")
            val hash = item.optString("sha256")
            val size = item.optString("size").toLongOrNull() ?: 0
            val host = runCatching { URI(url).host }.getOrNull().orEmpty()
            if (version.toIntOrNull() == null || !url.endsWith(".iso") || !url.startsWith("https://") ||
                !(host == "fedoraproject.org" || host.endsWith(".fedoraproject.org")) ||
                arch !in listOf("x86_64","aarch64") || !hash.matches(Regex("[a-fA-F0-9]{64}")) || size <= 0) null
            else Release(version,item.optString("subvariant").ifBlank { item.optString("variant") },arch,url,hash,size,item.optString("variant"))
        }.distinctBy { it.id }.sortedWith(compareByDescending<Release> { it.version.toInt() }.thenBy { if(it.edition == "Workstation") "" else it.edition }.thenBy { it.arch })
    }
    fun download(release: Release, directory: File, active: () -> Unit, progress: (Long, Long) -> Unit): File {
        directory.mkdirs()
        val final = File(directory,"${release.sha256}.iso")
        if (final.exists() && final.length() == release.size) {
            val digest = MessageDigest.getInstance("SHA-256")
            final.inputStream().use { input ->
                val data = ByteArray(1024*1024)
                while (true) { active(); val n = input.read(data); if(n < 0) break; digest.update(data,0,n) }
            }
            if (digest.digest().hex().equals(release.sha256,true)) return final
        }
        directory.listFiles()?.filter { it.name.endsWith(".iso") && it != final }?.forEach { it.delete() }
        final.delete()
        val partial = File(directory,"image.part")
        if (directory.usableSpace < release.size + 128L*1024*1024) error("Za mało pamięci telefonu. Potrzeba ${formatBytes(release.size)} i 128 MB zapasu.")
        try {
            request(release.url).use { response ->
                if (!response.isSuccessful) throw IOException("Pobieranie: HTTP ${response.code}")
                val body = response.body ?: error("Serwer zwrócił pusty obraz.")
                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input -> partial.outputStream().buffered().use { output ->
                    val data = ByteArray(1024*1024)
                    var done = 0L
                    while (true) {
                        active()
                        val n = input.read(data)
                        if (n < 0) break
                        done += n
                        if (done > release.size) throw IOException("Obraz przekracza rozmiar z katalogu Fedory.")
                        output.write(data,0,n); digest.update(data,0,n); progress(done,release.size)
                    }
                    check(done == release.size) { "Niepełny obraz ISO." }
                } }
                check(digest.digest().hex().equals(release.sha256,true)) { "Suma SHA-256 pobranego obrazu nie zgadza się z katalogiem Fedory." }
            }
            check(partial.renameTo(final)) { "Nie można zapisać obrazu w pamięci telefonu." }
            return final
        } catch (e: Exception) { partial.delete(); throw e }
    }
}
fun formatBytes(bytes: Long): String = if (bytes >= 1024L*1024*1024) String.format(java.util.Locale.getDefault(),"%.1f GB",bytes.toDouble()/(1024*1024*1024)) else "${bytes/(1024*1024)} MB"
