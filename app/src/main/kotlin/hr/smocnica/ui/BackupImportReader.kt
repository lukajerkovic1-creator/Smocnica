package hr.smocnica.ui

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal const val MAX_BACKUP_IMPORT_BYTES: Int = 20 * 1024 * 1024

internal fun readBackupImportBytes(
    input: InputStream,
    declaredSize: Long? = null,
    maximumBytes: Int = MAX_BACKUP_IMPORT_BYTES,
): ByteArray {
    require(maximumBytes > 0)
    if (declaredSize != null && declaredSize > maximumBytes) {
        throw IllegalArgumentException("JSON sigurnosna kopija ne smije biti veća od 20 MiB.")
    }

    val output = ByteArrayOutputStream(minOf(declaredSize?.toInt() ?: DEFAULT_BUFFER_SIZE, maximumBytes))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maximumBytes) {
            throw IllegalArgumentException("JSON sigurnosna kopija ne smije biti veća od 20 MiB.")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
