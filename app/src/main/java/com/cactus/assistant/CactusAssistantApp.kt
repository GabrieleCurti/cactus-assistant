package com.cactus.assistant

import android.app.Application
import com.cactus.assistant.engine.CactusAssistantEngine
import com.cactus.assistant.routing.AssistantActionRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CactusAssistantApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var engine: CactusAssistantEngine
        private set

    lateinit var actionRouter: AssistantActionRouter
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        engine = CactusAssistantEngine(this)
        actionRouter = AssistantActionRouter(this)

        appScope.launch {
            engine.initializeIfNeeded()
        }
    }

    companion object {
        @Volatile
        private var instance: CactusAssistantApp? = null

        fun getEngine(context: android.content.Context): CactusAssistantEngine {
            val app = (context as? CactusAssistantApp)
                ?: (context.applicationContext as? CactusAssistantApp)
                ?: instance
            return app?.engine ?: CactusAssistantEngine(context.applicationContext ?: context)
        }

        fun getRouter(context: android.content.Context): AssistantActionRouter {
            val app = (context as? CactusAssistantApp)
                ?: (context.applicationContext as? CactusAssistantApp)
                ?: instance
            return app?.actionRouter ?: AssistantActionRouter(context.applicationContext ?: context)
        }
    }
}
