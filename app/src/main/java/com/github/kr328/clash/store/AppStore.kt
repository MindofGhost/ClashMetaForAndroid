package com.github.kr328.clash.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider

class AppStore(context: Context) {
    private val store = Store(
        context
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .asStoreProvider()
    )

    var updatedAt: Long by store.long(
        key = "updated_at",
        defaultValue = -1,
    )

    var batteryOptimizationRequestShown: Boolean by store.boolean(
        key = "battery_optimization_request_shown",
        defaultValue = false,
    )

    var batteryOptimizationWasIgnored: Boolean by store.boolean(
        key = "battery_optimization_was_ignored",
        defaultValue = false,
    )

    companion object {
        private const val FILE_NAME = "app"
    }
}
