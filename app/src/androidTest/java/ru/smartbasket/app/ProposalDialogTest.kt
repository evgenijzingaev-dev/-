package ru.smartbasket.app

import android.content.Context
import android.graphics.Rect
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.view.View
import org.hamcrest.Matcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProposalDialogTest {
    @Test fun scrollToLastNutritionLineThenDeclineAndApply() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = context.resources.configuration
        check(config.screenWidthDp in 300..400 && config.screenHeightDp <= 700) {
            "Use a small portrait emulator (360 × 640 dp)."
        }
        val prefs = context.getSharedPreferences("basket", Context.MODE_PRIVATE)
        fun savedItems() = JSONArray(prefs.getString("items", "[]"))
        check(savedItems().length() == 0) { "Use an empty disposable emulator basket." }
        // Suppress the unrelated startup update dialog on the disposable test image.
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit()
            .putLong("checked", System.currentTimeMillis()).apply()
        val rows = JSONArray()
        for (index in 1..100) {
            rows.put(JSONObject().put("name", "Продукт $index").put("amount", index).put("unit", "г"))
        }
        val proposal = proposeBasket(JSONObject().put("items", rows), emptyList(), null, 0)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (button in listOf("Отклонить", "Применить")) {
                scenario.onActivity { activity ->
                    activity.showAssistantProposal(proposal, emptyList(),
                        prefs.getLong("budget", 300000), prefs.getLong("delivery", 0))
                }
                onView(withId(android.R.id.message)).inRoot(isDialog()).perform(revealLastNutrition())
                for (label in listOf("Отклонить", "Применить")) {
                    onView(withText(label)).inRoot(isDialog()).check(matches(isCompletelyDisplayed()))
                }
                onView(withText(button)).inRoot(isDialog()).perform(click())
                if (button == "Отклонить") assertEquals(0, savedItems().length())
            }
            val applied = savedItems()
            assertEquals(100, applied.length())
            assertEquals("Продукт 100", applied.getJSONObject(99).getString("name"))
        }
    }

    private fun revealLastNutrition() = object : ViewAction {
        override fun getDescription() = "Scroll the dialog until the entire last nutrition line is visible"
        override fun getConstraints(): Matcher<View> = isAssignableFrom(TextView::class.java)
        override fun perform(controller: UiController, view: View) {
            val message = view as TextView
            val marker = "КБЖУ на 100 г: нет данных"
            val start = message.text.indexOf(marker)
            assertTrue("Missing final nutrition line", start >= 0)
            val layout = message.layout
            val top = message.totalPaddingTop + layout.getLineTop(layout.getLineForOffset(start))
            val bottom = message.totalPaddingTop + layout.getLineBottom(layout.getLineForOffset(start + marker.length - 1))
            val line = Rect(message.paddingLeft, top, message.width - message.paddingRight, bottom)
            val visible = Rect()
            assertTrue(message.getLocalVisibleRect(visible))
            assertTrue("Expected final line below viewport", line.top >= visible.bottom)
            assertTrue("Dialog did not scroll", message.requestRectangleOnScreen(Rect(line), true))
            controller.loopMainThreadUntilIdle()
            assertTrue(message.getLocalVisibleRect(visible))
            assertTrue("Final line clipped: $line outside $visible", visible.contains(line))
        }
    }
}
