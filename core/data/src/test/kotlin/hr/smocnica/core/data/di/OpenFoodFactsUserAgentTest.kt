package hr.smocnica.core.data.di

import hr.smocnica.core.domain.AppMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenFoodFactsUserAgentTest {
    @Test
    fun `uses release version and real project contact`() {
        val metadata = AppMetadata(
            versionName = "1.0.0-rc28",
            projectUrl = "https://github.com/lukajerkovic1-creator/Smocnica",
            supportEmail = "luka.jerkovic1@gmail.com",
        )

        assertEquals(
            "Smocnica/1.0.0-rc28 (https://github.com/lukajerkovic1-creator/Smocnica; luka.jerkovic1@gmail.com)",
            openFoodFactsUserAgent(metadata),
        )
    }
}
