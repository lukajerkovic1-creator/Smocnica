package hr.smocnica.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class BottomNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun everyTabDiscardsNestedScreensAndReselectionCreatesFreshSection() {
        lateinit var controller: NavHostController
        val sections = listOf("home", "scanner", "shopping", "shelves", "menu")
        compose.setContent {
            controller = rememberNavController()
            NavHost(controller, startDestination = "home") {
                (sections + listOf("stocks", "scanner/context", "settings")).forEach { route ->
                    composable(route) { Text(route) }
                }
            }
        }
        compose.runOnIdle {
            fun tab(route: String) = controller.navigate(route, sectionNavigationOptions(controller.graph.findStartDestination().id))
            sections.forEach { route ->
                tab(route)
                controller.navigate("stocks")
                controller.navigate("scanner/context")
                tab(route)
                assertEquals(route, controller.currentDestination?.route)
                // Returning to a previously visited tab must not restore its scanner.
                tab("menu")
                controller.navigate("settings")
                tab(route)
                assertEquals(route, controller.currentDestination?.route)
                if (route != "home") {
                    val previousEntry = controller.currentBackStackEntry!!.id
                    tab(route)
                    assertNotEquals(previousEntry, controller.currentBackStackEntry!!.id)
                    assertEquals("home", controller.previousBackStackEntry?.destination?.route)
                }
            }
        }
    }
}
