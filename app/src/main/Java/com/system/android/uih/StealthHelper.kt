package com.system.android.uih

import android.content.Context

object StealthHelper {

    fun install(ctx: Context) {
        NativeHook.install()
    }

    fun uninstall() {
        NativeHook.uninstall()
    }

    object NativeHook {
        init { System.loadLibrary("stealth") }
        external fun install()
        external fun uninstall()
    }
}