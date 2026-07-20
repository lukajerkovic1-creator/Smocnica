package hr.smocnica.core.data.repository

import android.content.Context
import hr.smocnica.core.data.local.SmocnicaDatabase
import hr.smocnica.core.data.remote.FirebaseCallableClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FirebaseSessionRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `account deletion calls backend before clearing local data`() = runTest {
        val context = mockk<Context>()
        val database = mockk<SmocnicaDatabase>(relaxed = true)
        val client = mockk<FirebaseCallableClient>()
        every { context.cacheDir } returns temporaryFolder.root
        coEvery { client.call("deleteAccount") } returns mapOf("status" to "DELETED")
        val repository = FirebaseSessionRepository(context, database, client)

        repository.deleteAccount()

        coVerify(exactly = 1) { client.call("deleteAccount") }
        verify(exactly = 1) { database.clearAllTables() }
    }

    @Test
    fun `failed backend deletion keeps local data for safe retry`() = runTest {
        val context = mockk<Context>()
        val database = mockk<SmocnicaDatabase>(relaxed = true)
        val client = mockk<FirebaseCallableClient>()
        coEvery { client.call("deleteAccount") } throws IllegalStateException("offline")
        val repository = FirebaseSessionRepository(context, database, client)

        runCatching { repository.deleteAccount() }

        verify(exactly = 0) { database.clearAllTables() }
    }
}
