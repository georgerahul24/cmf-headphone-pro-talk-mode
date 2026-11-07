package dev.georgerahul.cmftalk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Live headphone state, shared by the service and the screen. Main thread only. */
object HeadphoneState {
    var status by mutableStateOf("Not started")
    var connected by mutableStateOf(false)
    var anc by mutableStateOf<Int?>(null)
    var battery by mutableStateOf<Int?>(null)
    var charging by mutableStateOf(false)
    var spatial by mutableStateOf<Int?>(null)
    var talkActive by mutableStateOf(false)
    /** Noise control was turned off by the idle timer. */
    var idleOff by mutableStateOf(false)
    /** Wall-clock times (ms) for the screen. */
    var talkSince by mutableStateOf<Long?>(null)
    var playing by mutableStateOf(false)
    var quietSince by mutableStateOf<Long?>(null)
    var idleDeadline by mutableStateOf<Long?>(null)
    var idleOffSince by mutableStateOf<Long?>(null)
    /** EQ preset on the headphones now (an [Eq] id), and the app Auto saw playing. */
    var eqNow by mutableStateOf<String?>(null)
    var eqApp by mutableStateOf<String?>(null)

    fun clear() {
        connected = false
        anc = null; battery = null; charging = false; spatial = null; idleOff = false
        quietSince = null; idleDeadline = null; idleOffSince = null; eqNow = null
    }
}
