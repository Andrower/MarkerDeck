package com.andrower.markerdeck

import android.content.Context

/** Keeps the embedded host independent from any individual Activity instance. */
object MarkerDeckHostRuntime {
    @Volatile
    private var controllerInstance: HostLifecycleController? = null

    fun controller(context: Context): HostLifecycleController =
        controllerInstance ?: synchronized(this) {
            controllerInstance ?: HostLifecycleController(context.applicationContext) {
                MarkerDeckHostService.stop(context.applicationContext)
            }.also { controllerInstance = it }
        }

    fun currentController(): HostLifecycleController? = controllerInstance

    fun stopController() {
        controllerInstance?.stop()
    }
}
