package hr.smocnica.core.data.repository

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import hr.smocnica.core.domain.AppUpdate
import hr.smocnica.core.domain.UpdateRepository
import hr.smocnica.core.domain.VerifiedApk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitHubUpdateRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val json: Json,
) : UpdateRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun check(currentVersionCode: Long): AppUpdate? = withContext(Dispatchers.IO) {
        val apiUrl = configString("github_releases_api_url")
        require(apiUrl.startsWith("https://api.github.com/repos/") && apiUrl.endsWith("/releases/latest")) {
            "GitHub Releases API URL nije konfiguriran ili nije dopušten."
        }
        val release = getJson(apiUrl)
        val assets = release["assets"]?.jsonArray ?: error("GitHub izdanje nema assets polje.")
        val manifestUrl = assets.firstOrNull {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == "release-manifest.json"
        }?.jsonObject?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
            ?: error("Izdanje nema release-manifest.json.")
        val apkSize = assets.firstOrNull {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true
        }?.jsonObject?.get("size")?.jsonPrimitive?.longOrNull ?: 0L
        requireAllowedJsonUrl(manifestUrl)
        val manifest = getJson(manifestUrl)
        val versionCode = manifest.requiredLong("versionCode")
        if (versionCode <= currentVersionCode) return@withContext null
        AppUpdate(
            versionCode = versionCode,
            versionName = manifest.requiredString("versionName"),
            minimumSupportedVersionCode = manifest["minSupportedVersionCode"]?.jsonPrimitive?.longOrNull ?: 1,
            apkUrl = manifest.requiredString("apkUrl").also { requireAllowedDownloadUrl(it) },
            sha256 = manifest.requiredString("sha256").lowercase().also {
                require(it.matches(Regex("[0-9a-f]{64}"))) { "Manifest sadrži neispravan SHA-256." }
            },
            releaseNotes = manifest["releaseNotes"]?.jsonPrimitive?.contentOrNull
                ?: release["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            sizeBytes = apkSize,
            forceUpdate = manifest["forceUpdate"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    override suspend fun downloadAndVerify(update: AppUpdate): VerifiedApk = withContext(Dispatchers.IO) {
        requireAllowedDownloadUrl(update.apkUrl)
        val updateDir = File(context.cacheDir, "verified-updates").apply { mkdirs() }
        val destination = File(updateDir, "smocnica-${update.versionCode}.apk")
        val request = Request.Builder().url(update.apkUrl).header("User-Agent", "SmocnicaAndroid/1.0").build()
        try {
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Preuzimanje ažuriranja nije uspjelo (${response.code})." }
                requireAllowedDownloadUrl(response.request.url.toString())
                val body = response.body
                require(body.contentLength() <= MAX_APK_BYTES || body.contentLength() == -1L) { "APK je neočekivano velik." }
                destination.outputStream().buffered().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_APK_BYTES) { "APK je neočekivano velik." }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
        require(destination.length() in 1..MAX_APK_BYTES) { "Preuzeti APK nije valjan." }
        val actualHash = destination.inputStream().use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().toHex()
        }
        require(MessageDigest.isEqual(actualHash.toByteArray(), update.sha256.lowercase().toByteArray())) {
            destination.delete()
            "SHA-256 preuzetog APK-a ne odgovara manifestu."
        }
        verifyArchive(destination, update)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", destination)
        VerifiedApk(uri.toString(), update.versionCode)
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(apk: File, update: AppUpdate) {
        val expected = configString("expected_signer_sha256").filterNot { it == ':' }.lowercase()
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "Očekivani fingerprint potpisnika nije konfiguriran." }
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("Android ne može pročitati potpis APK-a.")
        require(info.packageName == context.packageName) {
            apk.delete()
            "Preuzeti APK nema očekivani applicationId."
        }
        require(info.longVersionCode == update.versionCode) {
            apk.delete()
            "VersionCode preuzetog APK-a ne odgovara manifestu izdanja."
        }
        val signatures = info.signingInfo?.let { signing ->
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        }.orEmpty()
        val actual = signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
        }
        require(actual.any { MessageDigest.isEqual(it.toByteArray(), expected.toByteArray()) }) {
            apk.delete()
            "APK nije potpisan očekivanim produkcijskim certifikatom."
        }
    }

    private fun getJson(url: String) = client.newCall(Request.Builder().url(url).header("Accept", "application/vnd.github+json").build())
        .execute().use { response ->
            check(response.isSuccessful) { "GitHub provjera nije uspjela (${response.code})." }
            requireAllowedJsonUrl(response.request.url.toString())
            val bytes = readLimited(response.body.byteStream(), MAX_JSON_BYTES)
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        }

    private fun requireAllowedDownloadUrl(value: String) {
        val url = value.toHttpUrl()
        require(url.isHttps && url.host in ALLOWED_HOSTS) { "APK URL nije dopušten." }
    }

    private fun requireAllowedJsonUrl(value: String) {
        val url = value.toHttpUrl()
        require(url.isHttps && url.host in ALLOWED_JSON_HOSTS) { "GitHub JSON URL nije dopušten." }
    }

    private fun readLimited(input: java.io.InputStream, maximum: Int): ByteArray = input.use { stream ->
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maximum) { "GitHub odgovor je neočekivano velik." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun configString(name: String): String {
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        return if (id == 0) "" else context.getString(id).trim()
    }

    private fun kotlinx.serialization.json.JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: error("Manifest nema polje $key.")

    private fun kotlinx.serialization.json.JsonObject.requiredLong(key: String): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: error("Manifest nema brojčano polje $key.")

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_APK_BYTES = 250L * 1024 * 1024
        const val MAX_JSON_BYTES = 2 * 1024 * 1024
        val ALLOWED_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "github-releases.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )
        val ALLOWED_JSON_HOSTS = ALLOWED_HOSTS + "api.github.com"
    }
}
