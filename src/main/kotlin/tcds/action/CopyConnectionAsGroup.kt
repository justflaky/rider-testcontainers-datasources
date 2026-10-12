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
import tcds.connstr.parseJdbcUrl
import tcds.ds.DsOwnership
import java.awt.datatransfer.StringSelection

/**
 * "Copy Connection As..." submenu in the Database view context menu.
 * Shown where a supported data source is related to the clicked node - plugin-owned
 * (Testcontainers) or foreign ones of a supported engine - never for root folders.
 */
class CopyConnectionAsGroup : DefaultActionGroup() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = relatedDataSources(e).any { connData(it) != null }
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val related = e?.let(::relatedDataSources) ?: return emptyArray()
        val children = mutableListOf<AnAction>()
        for (ds in related) {
            val data = connData(ds) ?: continue
            if (related.size > 1) children += Separator.create(ds.name)
            children += connectionFormats(data).map { CopyTextAction(it) }
        }
        return children.toTypedArray()
    }

    /**
     * Data sources related to the clicked tree node, matched by name against the manager
     * (names are unique per project). Empty when the popup is on a root folder.
     */
    private fun relatedDataSources(e: AnActionEvent): List<LocalDataSource> {
        val project = e.project ?: return emptyList()
        val nodes = e.getData(DatabaseView.DATABASE_RELATED_DATA_SOURCES) ?: return emptyList()
        val byName = LocalDataSourceManager.getInstance(project).dataSources
            .filterIsInstance<LocalDataSource>()
            .associateBy { it.name }
        return nodes.mapNotNull { byName[it.name] }.distinct()
    }

    /**
     * Plugin data sources carry structured tcds.* properties; foreign (user-created) data
     * sources of a supported engine are derived from their JDBC URL + username.
     */
    private fun connData(ds: LocalDataSource): ConnData? {
        if (ds.getAdditionalProperty(DsOwnership.MARKER_KEY) != null) {
            val dbms = ds.getAdditionalProperty(DsOwnership.KEY_DBMS)
                ?.let { runCatching { TcDbms.valueOf(it) }.getOrNull() } ?: return null
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
        val url = ds.url.orEmpty()
        val parsed = parseJdbcUrl(url) ?: return null
        return ConnData(
            dbms = parsed.engine,
            host = parsed.host,
            port = parsed.port,
            database = parsed.database,
            user = parsed.user ?: ds.username.orEmpty(),
            password = parsed.password.orEmpty(),
            jdbcUrl = url,
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
