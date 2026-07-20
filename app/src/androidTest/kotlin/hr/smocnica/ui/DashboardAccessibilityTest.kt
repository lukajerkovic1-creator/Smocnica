package hr.smocnica.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hr.smocnica.ui.theme.SmocnicaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DashboardAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun dashboardSummaryAndTileRemainUsableAtTwoHundredPercentFontOn360Dp() {
        var opened = ""
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                SmocnicaTheme(darkTheme = true) {
                    Box(Modifier.width(360.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            OverviewCard(2, 3, 12, { opened = "minimum" }, { opened = "shopping" }, { opened = "stocks" })
                            DashboardTile(
                                title = "Popis za kupnju",
                                subtitle = "3 stavki na popisu",
                                icon = Icons.Outlined.Inventory2,
                                route = "shopping",
                                modifier = Modifier.fillMaxWidth(),
                                navigate = { opened = it },
                                compact = true,
                            )
                        }
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("Ispod minimuma: 2").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("minimum", opened) }
        compose.onNodeWithContentDescription("Na popisu za kupnju: 3").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ukupno artikala: 12").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Popis za kupnju").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("shopping", opened) }
    }

    @Test
    fun dashboardSemanticColorsMeetDarkModeContrast() {
        var primary = Color.Unspecified
        var error = Color.Unspecified
        var tertiary = Color.Unspecified
        var surface = Color.Unspecified
        var primaryContainer = Color.Unspecified
        var onPrimaryContainer = Color.Unspecified
        compose.setContent {
            SmocnicaTheme(darkTheme = true) {
                primary = MaterialTheme.colorScheme.primary
                error = MaterialTheme.colorScheme.error
                tertiary = MaterialTheme.colorScheme.tertiary
                surface = MaterialTheme.colorScheme.surface
                primaryContainer = MaterialTheme.colorScheme.primaryContainer
                onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
            }
        }
        compose.runOnIdle {
            assertTrue("Primarna boja mora biti vidljiva na tamnoj podlozi.", contrast(primary, surface) >= 3.0)
            assertTrue("Boja upozorenja mora biti vidljiva na tamnoj podlozi.", contrast(error, surface) >= 3.0)
            assertTrue("Boja kupnje mora biti vidljiva na tamnoj podlozi.", contrast(tertiary, surface) >= 3.0)
            assertTrue("Tekst statusa mora zadovoljiti AA kontrast.", contrast(onPrimaryContainer, primaryContainer) >= 4.5)
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        val light = maxOf(first.luminance(), second.luminance()).toDouble()
        val dark = minOf(first.luminance(), second.luminance()).toDouble()
        return (light + 0.05) / (dark + 0.05)
    }
}
