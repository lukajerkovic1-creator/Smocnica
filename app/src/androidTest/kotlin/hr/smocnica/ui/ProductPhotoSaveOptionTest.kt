package hr.smocnica.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import hr.smocnica.ui.theme.SmocnicaTheme
import org.junit.Rule
import org.junit.Test

class ProductPhotoSaveOptionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun photoCanBeIncludedOrExcludedAndChoiceSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            var checked by rememberSaveable { mutableStateOf(true) }
            SmocnicaTheme { ProductPhotoSaveOption(checked) { checked = it } }
        }
        val option = compose.onNodeWithText("Prikaži ovu fotografiju na kartici")
        option.assertIsOn().performClick().assertIsOff()
        restoration.emulateSavedInstanceStateRestore()
        option.assertIsOff().performClick().assertIsOn()
    }
}
