package io.github.arnonym.sip

import io.github.arnonym.log.log
import java.util.concurrent.ConcurrentLinkedQueue
import org.pjsip.pjsua2.Call as PjCall

object CallDisposal {
    private val pending = ConcurrentLinkedQueue<PjCall>()

    fun enqueue(call: PjCall) {
        pending.add(call)
    }

    fun drain() {
        if (pending.isEmpty()) return
        val stillActive = mutableListOf<PjCall>()
        while (true) {
            val call = pending.poll() ?: break
            try {
                if (call.isActive) stillActive.add(call) else call.delete()
            } catch (e: Exception) {
                log(null, "Warning: could not release native call object: ${e.message}")
            }
        }
        pending.addAll(stillActive)
    }
}
