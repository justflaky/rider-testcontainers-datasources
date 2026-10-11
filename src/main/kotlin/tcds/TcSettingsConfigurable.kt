package tcds

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import java.awt.FlowLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

class TcSettingsConfigurable : Configurable {

    private val enabledBox = JCheckBox("Watch for Testcontainers containers")
    private val prefixField = JTextField(10)
    private val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
        add(enabledBox)
        add(JLabel("Data source name prefix (applies to new data sources):"))
        add(prefixField)
    }

    override fun getDisplayName(): String = "Testcontainers Data Sources"

    override fun createComponent(): JComponent = panel

    override fun isModified(): Boolean {
        val s = TcSettings.getInstance()
        return enabledBox.isSelected != s.enabled || prefixField.text.trim() != s.namePrefix
    }

    override fun reset() {
        val s = TcSettings.getInstance()
        enabledBox.isSelected = s.enabled
        prefixField.text = s.namePrefix
    }

    override fun apply() {
        val s = TcSettings.getInstance()
        s.enabled = enabledBox.isSelected
        s.namePrefix = prefixField.text.trim().ifEmpty { "TC" }
        // live reaction: watcher stops/starts without a project reopen
        ApplicationManager.getApplication().messageBus
            .syncPublisher(TC_SETTINGS_TOPIC).settingsChanged(s.enabled)
    }
}
