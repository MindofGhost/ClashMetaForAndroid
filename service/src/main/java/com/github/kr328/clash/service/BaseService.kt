package com.github.kr328.clash.service

import android.app.Service
import com.github.kr328.clash.service.util.cancelAndJoinBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

abstract class BaseService : Service(), CoroutineScope by CoroutineScope(Dispatchers.Default) {
    override fun onCreate() {
        super.onCreate()

        if (this !is ProfileWorker) {
            ProfileWorker.requestUpdateStale(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        cancelAndJoinBlocking()
    }
}
