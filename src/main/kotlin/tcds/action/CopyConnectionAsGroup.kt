package tcds.action

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import tcds.catalog.TcDbms
import tcds.connstr.ConnData
import tcds.connstr.CopyFormat
import tcds.connstr.FormatKind
import tcds.connstr.connectionFormats
import tcds.ds.DsOwnership
import java.awt.datatransfer.StringSelection

/**
 * "Copy Connection As..." submenu in the Database view context menu.
 * Lists plugin-owned data sources, grouped with named separators when several are live;
 * entries carry type icons (JDBC/.NET vs CLI) for faster scanning.
 */
class CopyConnectionAsGroup : DefaultActionGroup() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = ownedDataSources(e).isNotEmpty()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val owned = e?.let(::ownedDataSources) ?: return emptyArray()
        val children = mutableListOf<AnAction>()
        for (ds in owned) {
            val data = connData(ds) ?: continue
            if (owned.size > 1) children += Separator.create(ds.name)
            children += connectionFormats(data).map { CopyTextAction(it) }
        }
        return children.toTypedArray()
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

    private class CopyTextAction(private val format: CopyFormat) :
        AnAction(format.label, format.text, iconFor(format.kind)) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            CopyPasteManager.getInstance().setContents(StringSelection(format.text))
            val project = e.getData(PlatformDataKeys.PROJECT)
            NotificationGroupManager.getInstance()
                .getNotificationGroup("TC DataSources")
                .createNotification("Copied: ${format.label}", NotificationType.INFORMATION)
                .notify(project)
        }
    }

    companion object {
        private fun iconFor(kind: FormatKind) = when (kind) {
            FormatKind.CLI -> com.intellij.icons.AllIcons.Nodes.Console
            else -> com.intellij.icons.AllIcons.Nodes.DataTables
        }
    }
}
