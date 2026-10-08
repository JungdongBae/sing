package com.jungdong.sing.audio

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A cancelled waiter cannot bypass an older owner's non-cancellable resource finalization. */
class AudioSessionGate {
    private val mutex = Mutex()
    suspend fun run(block: suspend () -> Unit) { mutex.withLock { block() } }
}
