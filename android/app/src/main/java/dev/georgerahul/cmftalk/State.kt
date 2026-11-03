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

    fun clear() {
        connected = false
        anc = null; battery = null; charging = false; spatial = null
    }
}
