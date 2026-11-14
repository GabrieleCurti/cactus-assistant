package com.cactus.assistant

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import com.cactus.assistant.service.CactusRecognitionService
import com.cactus.assistant.service.CactusVoiceInteractionService
import com.cactus.assistant.service.CactusVoiceInteractionSessionService
import com.cactus.assistant.ui.AssistActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class AssistFlowRobolectricTest {

    @Test
    fun manifest_exposesDigitalAssistantServicesAndAssistActivity() {
        val context = RuntimeEnvironment.getApplication()
        val pm = context.packageManager

        val viService = ComponentName(context, CactusVoiceInteractionService::class.java)
        val viInfo = pm.getServiceInfo(viService, PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_VOICE_INTERACTION", viInfo.permission)

        val sessionService = ComponentName(context, CactusVoiceInteractionSessionService::class.java)
        assertNotNull(pm.getServiceInfo(sessionService, 0))

        val recognitionService = ComponentName(context, CactusRecognitionService::class.java)
        assertNotNull(pm.getServiceInfo(recognitionService, PackageManager.GET_META_DATA))

        val assistIntent = Intent(Intent.ACTION_ASSIST).setPackage(context.packageName)
        val resolved = pm.queryIntentActivities(assistIntent, 0)
        assertTrue(resolved.any { it.activityInfo.name == AssistActivity::class.java.name })
    }

    @Test
    fun assistActivity_routesPrefilledTimerQueryToSilentAlarmClockIntent() {
        val intent = Intent(Intent.ACTION_ASSIST).apply {
            putExtra(Intent.EXTRA_TEXT, "set a 10 minute timer for pasta")
        }
        val started = launchAssistAndAwaitStartedActivity(intent)
        assertNotNull("Expected AssistActivity to fire ACTION_SET_TIMER", started)
        assertEquals(AlarmClock.ACTION_SET_TIMER, started!!.action)
        assertEquals(600, started.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
        assertTrue(started.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
    }

    @Test
    fun assistActivity_routesPrefilledAlarmQueryToSilentAlarmClockIntent() {
        val intent = Intent(Intent.ACTION_ASSIST).apply {
            putExtra(Intent.EXTRA_TEXT, "wake me up at 7:30 am")
        }
        val started = launchAssistAndAwaitStartedActivity(intent)
        assertNotNull("Expected AssistActivity to fire ACTION_SET_ALARM", started)
        assertEquals(AlarmClock.ACTION_SET_ALARM, started!!.action)
        assertEquals(7, started.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(30, started.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertTrue(started.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
    }

    @Test
    fun assistActivity_routesPrefilledTodoQueryToGoogleTasks() {
        val intent = Intent(Intent.ACTION_ASSIST).apply {
            putExtra(Intent.EXTRA_TEXT, "add a todo to buy coffee beans")
        }
        val started = launchAssistAndAwaitStartedActivity(intent)
        assertNotNull("Expected AssistActivity to forward todo to Google Tasks", started)
        assertEquals("com.google.android.apps.tasks", started!!.`package`)
        assertEquals("buy coffee beans", started.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun assistActivity_routesPrefilledGeneralQueryToGoogleGeminiPackage() {
        val intent = Intent(Intent.ACTION_ASSIST).apply {
            putExtra(Intent.EXTRA_TEXT, "What is the speed of light?")
        }
        val started = launchAssistAndAwaitStartedActivity(intent)
        assertNotNull("Expected AssistActivity to forward unmatched query to Gemini", started)
        assertEquals("com.google.android.apps.bard", started!!.`package`)
        assertEquals("What is the speed of light?", started.getStringExtra(Intent.EXTRA_TEXT))
    }

    private fun launchAssistAndAwaitStartedActivity(intent: Intent): Intent? {
        val controller = Robolectric.buildActivity(AssistActivity::class.java, intent).setup()
        return try {
            awaitStartedActivity(controller.get())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun awaitStartedActivity(
        activity: AssistActivity,
        timeoutMs: Long = 5_000L
    ): Intent? {
        val app = RuntimeEnvironment.getApplication()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            val next = shadowOf(activity).nextStartedActivity
                ?: shadowOf(activity.application).nextStartedActivity
                ?: shadowOf(app).nextStartedActivity
            if (next != null) return next
            Thread.sleep(25L)
        }
        val vm = androidx.lifecycle.ViewModelProvider(activity)[com.cactus.assistant.ui.AssistantViewModel::class.java]
        val s = vm.uiState.value
        throw AssertionError(
            "Timed out waiting for startedActivity. uiState={banner='${s.statusBanner}', lastResult=${s.lastResult}}"
        )
    }
}
