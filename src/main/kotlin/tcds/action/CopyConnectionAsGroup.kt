package tcds.action

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.view.DatabaseView
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
 * Shown only where a plugin-owned data source is actually related to the clicked node
 * (the data source itself or anything inside its subtree) - not for root folders,
 * foreign data sources, or unrelated nodes.
 */
class CopyConnectionAsGroup : DefaultActionGroup() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = relatedOwnedDataSources(e).isNotEmpty()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val related = e?.let(::relatedOwnedDataSources) ?: return emptyArray()
        val children = mutableListOf<AnAction>()
        for (ds in related) {
            val data = connData(ds) ?: continue
            if (related.size > 1) children += Separator.create(ds.name)
            children += connectionFormats(data).map { CopyTextAction(it) }
        }
        return children.toTypedArray()
    }

    /**
     * Plugin-owned data sources related to the clicked tree node, matched by name against
     * the manager (names are unique per project). Empty when the popup is on a root
     * folder or inside a non-plugin data source.
     */
    private fun relatedOwnedDataSources(e: AnActionEvent): List<LocalDataSource> {
        val project = e.project ?: return emptyList()
        val nodes = e.getData(DatabaseView.DATABASE_RELATED_DATA_SOURCES) ?: return emptyList()
        val ownedByName = LocalDataSourceManager.getInstance(project).dataSources
            .filterIsInstance<LocalDataSource>()
            .filter { it.getAdditionalProperty(DsOwnership.MARKER_KEY) != null }
            .associateBy { it.name }
        return nodes.mapNotNull { ownedByName[it.name] }.distinct()
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
