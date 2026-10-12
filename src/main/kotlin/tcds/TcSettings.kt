package tcds

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(name = "TcDataSourcesSettings", storages = [Storage("tcds.xml")])
class TcSettings : PersistentStateComponent<TcSettings> {

    var enabled: Boolean = true

    /** Data source name prefix; also used to recognize plugin-owned data sources after a restart. */
    var namePrefix: String = "TC"

    // notification preferences
    var notifyOnAdded: Boolean = true
    var notifyOnRemoved: Boolean = true
    var notifyDockerUnavailable: Boolean = true

    override fun getState(): TcSettings = this

    override fun loadState(state: TcSettings) = XmlSerializerUtil.copyBean(state, this)

    companion object {
        fun getInstance(): TcSettings = com.intellij.openapi.application.ApplicationManager.getApplication().getService(TcSettings::class.java)
    }
}
