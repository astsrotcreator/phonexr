package dev.chessar.liveprobe

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.polidea.rxandroidble3.RxBleClient
import com.topstep.wearkit.abmate.WKAbMateKit
import com.topstep.wearkit.apis.WKWearKit
import com.topstep.wearkit.base.ProcessLifecycleManager
import com.topstep.wearkit.core.buildWKWearKit
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import timber.log.Timber

/** AB_MATE adapter is tested first; hardware support of MT5+ is not guaranteed. */
class ChessApp : Application() {
    lateinit var wearKit: WKWearKit
        private set

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        val tracker = ForegroundTracker()
        registerActivityLifecycleCallbacks(tracker)
        wearKit = buildWKWearKit(
            arrayListOf<WKWearKit.Builder>(
                WKAbMateKit.Builder(this, tracker, RxBleClient.create(this))
            )
        )
        val ignored = wearKit.rxJavaPluginsIgnoreExceptions()
        RxJavaPlugins.setErrorHandler { throwable ->
            val cause = throwable.cause ?: throwable
            if (ignored.any { it.isAssignableFrom(cause.javaClass) })
                Timber.w(throwable, "WearKit undeliverable error")
            else
                Timber.e(throwable, "WearKit error")
        }
    }
}

private class ForegroundTracker : ProcessLifecycleManager(), Application.ActivityLifecycleCallbacks {
    private var active = 0
    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityStarted(a: Activity) { active++ }
    override fun onActivityResumed(a: Activity) { if (active > 0) setForeground(true) }
    override fun onActivityPaused(a: Activity) {}
    override fun onActivityStopped(a: Activity) {
        active = (active - 1).coerceAtLeast(0)
        if (active == 0) setForeground(false)
    }
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}
