package com.cactus.assistant

import android.app.SearchManager
import android.content.Intent
import android.provider.AlarmClock
import com.cactus.assistant.engine.AssistantDecision
import com.cactus.assistant.routing.AssistantActionRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AssistantActionRouterTest {

    @Test
    fun buildSetTimerIntent_setsAlarmClockTimerSilently() {
        val context = RuntimeEnvironment.getApplication()
        val router = AssistantActionRouter(context)

        val intent = router.buildSetTimerIntent(durationSeconds = 900, label = "Tea")
        assertEquals(AlarmClock.ACTION_SET_TIMER, intent.action)
        assertEquals(900, intent.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
        assertEquals("Tea", intent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertTrue(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun buildSetAlarmIntent_setsAlarmClockAlarmSilently() {
        val context = RuntimeEnvironment.getApplication()
        val router = AssistantActionRouter(context)

        val intent = router.buildSetAlarmIntent(hour24 = 7, minute = 45, label = "Morning Workout")
        assertEquals(AlarmClock.ACTION_SET_ALARM, intent.action)
        assertEquals(7, intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(45, intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertEquals("Morning Workout", intent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertTrue(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun buildGoogleTasksIntent_targetsGoogleTasksAppPrefilled() {
        val context = RuntimeEnvironment.getApplication()
        val router = AssistantActionRouter(context)

        val intent = router.buildGoogleTasksIntent(
            title = "Buy groceries",
            notes = "Oat milk, avocados, coffee"
        )
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(AssistantActionRouter.GOOGLE_TASKS_PACKAGE, intent.`package`)
        assertEquals("Buy groceries", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("Buy groceries\n\nOat milk, avocados, coffee", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun buildGeminiIntent_targetsGoogleGeminiAppPrefilled() {
        val context = RuntimeEnvironment.getApplication()
        val router = AssistantActionRouter(context)

        val query = "How many moons does Jupiter have?"
        val intent = router.buildGeminiIntent(query)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("com.google.android.apps.bard", intent.`package`)
        assertEquals(query, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(query, intent.getStringExtra(SearchManager.QUERY))
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun execute_dispatchesTimerAlarmTasksAndGeminiIntents() {
        val app = RuntimeEnvironment.getApplication()
        val router = AssistantActionRouter(app)

        val timerResult = router.execute(
            AssistantDecision.SetTimer(
                durationSeconds = 600,
                label = "Eggs",
                confidence = 0.65,
                transcript = "set a 10 minute timer for eggs"
            )
        )
        val startedTimer = shadowOf(app).nextStartedActivity
        assertEquals(AlarmClock.ACTION_SET_TIMER, startedTimer.action)
        assertEquals(600, startedTimer.getIntExtra(AlarmClock.EXTRA_LENGTH, 0))
        assertTrue(startedTimer.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        assertTrue(timerResult.executed)

        val alarmResult = router.execute(
            AssistantDecision.SetAlarm(
                hour24 = 18,
                minute = 30,
                label = "Workout",
                confidence = 0.58,
                transcript = "set an alarm for 6:30 pm for workout"
            )
        )
        val startedAlarm = shadowOf(app).nextStartedActivity
        assertEquals(AlarmClock.ACTION_SET_ALARM, startedAlarm.action)
        assertEquals(18, startedAlarm.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(30, startedAlarm.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertTrue(startedAlarm.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        assertTrue(alarmResult.executed)

        val todoResult = router.execute(
            AssistantDecision.CreateTodo(
                title = "Buy milk",
                notes = null,
                confidence = 0.72,
                transcript = "add a todo to buy milk"
            )
        )
        val startedTodo = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_SEND, startedTodo.action)
        assertEquals(AssistantActionRouter.GOOGLE_TASKS_PACKAGE, startedTodo.`package`)
        assertEquals("Buy milk", startedTodo.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(todoResult.executed)

        val geminiResult = router.execute(
            AssistantDecision.FallbackToGemini(
                query = "Write a Python binary search",
                reason = "function_calls=[]"
            )
        )
        val startedGemini = shadowOf(app).nextStartedActivity
        assertEquals("com.google.android.apps.bard", geminiResult.targetPackage)
        assertEquals("com.google.android.apps.bard", startedGemini.`package`)
        assertEquals("Write a Python binary search", startedGemini.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun execute_emptyAudioAndErrorDoNotLaunchActivities() {
        val app = RuntimeEnvironment.getApplication()
        val router = AssistantActionRouter(app)

        val emptyRes = router.execute(AssistantDecision.EmptyAudio("Silence detected"))
        assertFalse(emptyRes.executed)
        assertNull(emptyRes.primaryIntent)
        assertNull(shadowOf(app).nextStartedActivity)

        val errRes = router.execute(AssistantDecision.Error("Whistle failed"))
        assertFalse(errRes.executed)
        assertNull(errRes.primaryIntent)
        assertTrue(errRes.summary.contains("Whistle failed"))
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
