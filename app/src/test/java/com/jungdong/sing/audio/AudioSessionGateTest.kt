package com.jungdong.sing.audio

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AudioSessionGateTest {
    @Test fun cancelledMiddleWaiterCannotBypassAnOlderOwnersFinalization() = runBlocking {
        val gate = AudioSessionGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseResource = CompletableDeferred<Unit>()
        val thirdEntered = CompletableDeferred<Unit>()
        val first = launch {
            gate.run {
                firstEntered.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { releaseResource.await() } }
            }
        }
        try {
            withTimeout(2000) { firstEntered.await() }
            val second = launch { gate.run { fail("A cancelled waiter must not become the owner") } }
            yield(); second.cancelAndJoin()
            first.cancel()
            val third = launch { gate.run { thirdEntered.complete(Unit) } }
            yield()
            assertFalse(thirdEntered.isCompleted)
            releaseResource.complete(Unit)
            first.join()
            withTimeout(2000) { thirdEntered.await() }
            third.join()
        } finally { releaseResource.complete(Unit); first.cancel() }
    }
}
