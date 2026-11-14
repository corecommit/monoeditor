package dev.mono.app

import android.app.Application
import dev.mono.app.session.AppContainer

class MonoApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }

    override fun onTerminate() {
        container.shutdown()
        super.onTerminate()
    }
}
