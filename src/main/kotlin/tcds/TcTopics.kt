package tcds

import com.intellij.util.messages.Topic

fun interface TcSettingsChangedListener {
    /** Fired after settings are persisted; [enabled] reflects the new state. */
    fun settingsChanged(enabled: Boolean)
}

val TC_SETTINGS_TOPIC: Topic<TcSettingsChangedListener> =
    Topic.create("tcds.settings", TcSettingsChangedListener::class.java)
