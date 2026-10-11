package tcds.action

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import tcds.catalog.TcDbms
import tcds.connstr.ConnData
import tcds.connstr.connectionFormats
import tcds.ds.DsOwnership
import java.awt.datatransfer.StringSelection

/**
 * "Copy Connection As..." submenu in the Database view context menu.
 * Lists every plugin-owned data source with its connection string in the formats
 * people actually paste: JDBC, ADO.NET / EF Core provider strings, CLI invocations.
 */
class CopyConnectionAsGroup : DefaultActionGroup() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = ownedDataSources(e).isNotEmpty()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val owned = e?.let(::ownedDataSources) ?: return emptyArray()
        val many = owned.size > 1
        return owned.flatMap { ds ->
            val data = connData(ds) ?: return@flatMap emptyList<AnAction>()
            connectionFormats(data).map { f ->
                CopyTextAction(if (many) "[${ds.name}] ${f.label}" else f.label, f.text)
            }
        }.toTypedArray()
    }

    private fun ownedDataSources(e: AnActionEvent): List<LocalDataSource> {
        val project = e.project ?: return emptyList()
        return LocalDataSourceManager.getInstance(project).dataSources
            .filterIsInstance<LocalDataSource>()
            .filter { it.getAdditionalProperty(DsOwnership.MARKER_KEY) != null }
    }

    private fun connData(ds: LocalDataSource): ConnData? {
        val dbms = ds.getAdditionalProperty(DsOwnership.KEY_DBMS)?.let { runCatching { TcDbms.valueOf(it) }.getOrNull() }
            ?: return null
        val port = ds.getAdditionalProperty(DsOwnership.KEY_PORT)?.toIntOrNull() ?: return null
        return ConnData(
            dbms = dbms,
            host = ds.getAdditionalProperty(DsOwnership.KEY_HOST) ?: "localhost",
            port = port,
            database = ds.getAdditionalProperty(DsOwnership.KEY_DB),
            user = ds.getAdditionalProperty(DsOwnership.KEY_USER).orEmpty(),
            password = ds.getAdditionalProperty(DsOwnership.KEY_PASS).orEmpty(),
            jdbcUrl = ds.url.orEmpty(),
        )
    }

    private class CopyTextAction(private val label: String, private val text: String) : AnAction(label) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            CopyPasteManager.getInstance().setContents(StringSelection(text))
            val project = e.getData(PlatformDataKeys.PROJECT)
            NotificationGroupManager.getInstance()
                .getNotificationGroup("TC DataSources")
                .createNotification("Copied: $label", NotificationType.INFORMATION)
                .notify(project)
        }
    }
}
