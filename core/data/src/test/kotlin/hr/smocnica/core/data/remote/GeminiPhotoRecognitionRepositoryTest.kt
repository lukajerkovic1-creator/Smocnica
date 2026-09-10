package hr.smocnica.core.data.remote

import hr.smocnica.core.model.PackageUnit
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiPhotoRecognitionRepositoryTest {
    @Test fun sendsPhotoOnlyToAuthenticatedCallableAndParsesProposal() = runTest {
        val callable = mockk<FirebaseCallableClient>()
        coEvery { callable.call("recognizeProductPhoto", any()) } returns mapOf(
            "name" to "Glatko brašno", "manufacturer" to "", "packageAmount" to "1", "packageUnit" to "KG",
        )
        val result = GeminiPhotoRecognitionRepository(callable).recognize("p1", byteArrayOf(-1, -40, -1, -39))
        assertEquals(PackageUnit.KG, result.packageUnit)
        coVerify(exactly = 1) { callable.call("recognizeProductPhoto", match { it["pantryId"] == "p1" && it["photoBase64"] == "/9j/2Q==" }) }
    }
}
