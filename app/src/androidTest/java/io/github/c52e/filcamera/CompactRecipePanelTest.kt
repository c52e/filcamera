package io.github.c52e.filcamera

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.c52e.filcamera.strings.R as Texts
import io.github.c52e.filcamera.strings.tr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CompactRecipePanelTest {
    @get:Rule(order=0) val foreground=foregroundActivityRule(ComponentActivity::class.java)
    @get:Rule(order=1) val ui=createAndroidComposeRule<ComponentActivity>()

    @Test fun cameraEffectsKeepTheSliderBelowOptionsAndReadyToUse() {
        var recipe by mutableStateOf(FilmRecipe())
        var group by mutableIntStateOf(0)
        var height by mutableStateOf(216.dp)
        ui.setContent {
            MaterialTheme {
                CompactRecipePanel(recipe,{recipe=it},group,{group=it},Modifier.width(360.dp).height(height))
            }
        }

        ui.onNodeWithText(tr(Texts.string.effects)).performClick()
        ui.onNodeWithText(tr(Texts.string.final_photo_only)).assertIsDisplayed()
        val grainSlider=ui.onNodeWithContentDescription(tr(Texts.string.grain))
        val diffusion=tr(Texts.string.print_diffusion)
        val diffusionOption=ui.onNodeWithContentDescription(tr(Texts.string.control_value,diffusion))
        diffusionOption.assertIsDisplayed()
        assertTrue(diffusionOption.getUnclippedBoundsInRoot().bottom<=grainSlider.getUnclippedBoundsInRoot().top)

        ui.runOnIdle {height=120.dp}
        grainSlider.assertIsDisplayed().performSemanticsAction(SemanticsActions.SetProgress) {it(1200f)}
        diffusionOption.performScrollTo().performClick()
        ui.onNodeWithContentDescription(diffusion).assertIsDisplayed()
        ui.onNodeWithContentDescription(tr(Texts.string.back_to_controls)).assertDoesNotExist()
        ui.runOnIdle {assertEquals(1200,recipe.grainStrength)}
    }

    @Test fun bothDiffusionSlidersReachTwoInShortPanel() {
        var recipe by mutableStateOf(FilmRecipe())
        ui.setContent {
            MaterialTheme {
                CompactRecipePanel(recipe,{recipe=it},2,{},Modifier.width(240.dp).height(120.dp),editing=true)
            }
        }

        fun setDiffusionToTwo(label: Int) {
            val diffusion=tr(label)
            ui.onNodeWithContentDescription(tr(Texts.string.control_value,diffusion)).performScrollTo().performClick()
            ui.onNodeWithContentDescription(diffusion).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.SetProgress) {it(2000f)}
        }

        ui.onNodeWithText(tr(Texts.string.next_page)).performScrollTo().performClick()
        setDiffusionToTwo(Texts.string.film_diffusion)
        ui.onNodeWithContentDescription(tr(Texts.string.back_to_controls)).performClick()
        setDiffusionToTwo(Texts.string.print_diffusion)
        ui.runOnIdle {
            assertEquals(2000,recipe.filmDiffusionStrength)
            assertEquals(2000,recipe.printDiffusionStrength)
        }
    }
}
