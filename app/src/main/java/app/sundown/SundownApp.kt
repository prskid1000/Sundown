package app.sundown

import android.app.Application
import android.content.Context
import app.sundown.data.SundownDb
import app.sundown.schedule.Notifier
import app.sundown.schedule.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SundownApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifier.createChannels(this)
        // Re-arm on every start as well as on boot: a force-stop or a
        // reinstall drops every alarm and delivers no broadcast to say so.
        Graph.scope.launch { Scheduler.rearmAll(this@SundownApp) }
    }
}

/**
 * The app's few singletons. Small enough that a DI framework would be more
 * code than it saves.
 */
object Graph {
    lateinit var app: Context
        private set
    lateinit var db: SundownDb
        private set

    /** Work that must outlive a screen or a broadcast: saving, logging, re-arming. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        app = context.applicationContext
        db = SundownDb.build(app)
    }
}
