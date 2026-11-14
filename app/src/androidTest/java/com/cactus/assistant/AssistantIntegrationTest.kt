package com.cactus.assistant

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnitTest
import androidx.test.platform.app.InstrumentationRegistry
import com.cactus.assistant.service.CactusRecognitionService
import com.cactus.assistant.service.CactusVoiceInteractionService
import com.cactus.assistant.service.CactusVoiceInteractionSessionService
import com.cactus.assistant.ui.AssistActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnitTest::class)
class AssistantIntegrationTest {

    @Test
    fun manifestRegistersVoiceInteractionServicesAndAssistActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager

        val viService = ComponentName(context, CactusVoiceInteractionService::class.java)
        val viInfo = pm.getServiceInfo(viService, PackageManager.GET_META_DATA)
        assertNotNull(viInfo)
        assertEquals("android.permission.BIND_VOICE_INTERACTION", viInfo.permission)

        val sessionService = ComponentName(context, CactusVoiceInteractionSessionService::class.java)
        val sessionInfo = pm.getServiceInfo(sessionService, 0)
        assertNotNull(sessionInfo)

        val recognitionService = ComponentName(context, CactusRecognitionService::class.java)
        val recInfo = pm.getServiceInfo(recognitionService, PackageManager.GET_META_DATA)
        assertNotNull(recInfo)

        val assistIntent = Intent(Intent.ACTION_ASSIST).apply {
            setPackage(context.packageName)
        }
        val resolved = pm.queryIntentActivities(assistIntent, 0)
        assertTrue(resolved.any { it.activityInfo.name == AssistActivity::class.java.name })
    }

    private fun assertEquals(expected: String, actual: String?) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
