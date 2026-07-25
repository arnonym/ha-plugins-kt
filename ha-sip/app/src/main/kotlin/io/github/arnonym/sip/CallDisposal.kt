package io.github.arnonym.sip

import io.github.arnonym.log.log
import java.util.concurrent.ConcurrentLinkedQueue
import org.pjsip.pjsua2.Call as PjCall

/**
 * Destroys finished pjsua2 call objects at a moment when doing so is harmless.
 *
 * Left to the garbage collector, they are not. `~Call()` uses the call id the object was
 * constructed with, and checks nothing about whether that id still refers to it:
 *
 * ```
 * Call::~Call() {
 *     if (id != PJSUA_INVALID_ID) pjsua_call_set_user_data(id, NULL);
 *     if (pjsua_get_state() < PJSUA_STATE_CLOSING && isActive()) hangup(prm);
 * }
 * ```
 *
 * pjsua hands out call ids from a small array and reuses a slot as soon as its call ends,
 * so with one call at a time every call gets id 0. A finalizer running at some arbitrary
 * later point therefore wipes the association of whichever *live* call inherited the slot
 * -- after which ha-sip never sees that call's state changes, it is stuck in the registry
 * forever, and `hangup` answers PJSIP_ESESSIONTERMINATED -- and then hangs it up, a BYE
 * from nowhere with nothing in the log to account for it.
 *
 * Both branches are defused once the slot is idle: the association is already null, and
 * `isActive()` is false so the hangup is skipped. That is the only condition this waits
 * for. It deliberately does *not* destroy the object from inside `onCallState`, which is
 * the obvious place and is wrong twice over -- pjsua has not released the slot yet, so
 * `isActive()` is still true and the destructor's `hangup()` runs on a half-destroyed
 * object (SIGSEGV in `pj::Call::hangup`), and the C++ frames of the director callback are
 * still on the stack below.
 */
object CallDisposal {
    private val pending = ConcurrentLinkedQueue<PjCall>()

    /** Hands over a disconnected call. The queue holds the only reference from here on. */
    fun enqueue(call: PjCall) {
        pending.add(call)
    }

    /**
     * Destroys every queued call whose id slot is idle, called from the main tick.
     *
     * Anything still active is put back rather than destroyed: its slot has been taken
     * over by a newer call, and it will be idle again once that one ends. Holding the
     * object here is what keeps the finalizer -- and the damage above -- from ever
     * running in the meantime.
     */
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
