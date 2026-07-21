package hr.smocnica.ui

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupImportReaderTest {
    @Test
    fun readsBoundedBackup() {
        val bytes = "{\"schemaVersion\":1}".encodeToByteArray()

        assertArrayEquals(bytes, readBackupImportBytes(ByteArrayInputStream(bytes), bytes.size.toLong(), 64))
    }

    @Test
    fun rejectsDeclaredOversizeBeforeReading() {
        val input = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                error("Sadržaj se ne smije čitati nakon provjere veličine.")
        }

        assertThrows(IllegalArgumentException::class.java) {
            readBackupImportBytes(input, declaredSize = 65, maximumBytes = 64)
        }
    }

    @Test
    fun rejectsOversizeWhenProviderDoesNotDeclareLength() {
        assertThrows(IllegalArgumentException::class.java) {
            readBackupImportBytes(ByteArrayInputStream(ByteArray(65)), declaredSize = null, maximumBytes = 64)
        }
    }
}
