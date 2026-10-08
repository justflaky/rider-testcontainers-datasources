package tcds.ds

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.util.LoaderContext
import com.intellij.database.util.performAutoSyncTask
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import tcds.TcNotifications
import tcds.TcSettings
import tcds.catalog.DatabaseImageCatalog
import tcds.model.ContainerInfo

/**
 * Creates/removes Database tool window data sources for tracked containers.
 * All mutations run on EDT. The container id is encoded in the data source name
 * ("$prefix <image> <shortId>") — user data is not available on LocalDataSource,
 * and the name survives IDE restarts, which [reconcile] relies on.
 */
class DataSourceRegistrar(private val project: Project) {

    private val manager: LocalDataSourceManager get() = LocalDataSourceManager.getInstance(project)

    fun registerContainer(info: ContainerInfo) {
        val target = DatabaseImageCatalog.resolve(info) ?: return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            try {
                tracked(info.id)?.let(manager::removeDataSource)

                val name = dsName(info)
                // bytecode-verified order in RD-262: create(name, driverClass, url, user);
                // password travels inside the JDBC URL (see DatabaseImageCatalog)
                val ds = LocalDataSource.create(name, target.driverClass, target.url, target.user)
                ds.setAutoSynchronize(true)
                runCatching { ds.ensureDriverConfigured() }
                    .onFailure { LOG.warn("tcds: ensureDriverConfigured failed: ${it.message}") }
                manager.addDataSource(ds)
                LOG.info("tcds: registered data source name='${ds.name}' url='${ds.url}' user='${ds.username}' container=${info.id}")

                scheduleSync(ds)
                TcNotifications.dataSourceAdded(project, name, target)
            } catch (e: Exception) {
                LOG.error("tcds: registration of ${info.shortId} failed", e)
            }
        }
    }

    fun removeContainer(id: String) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            tracked(id)?.let {
                val name = it.name
                manager.removeDataSource(it)
                LOG.info("tcds: removed data source '$name' (container $id gone)")
                TcNotifications.dataSourceRemoved(project, name)
            }
        }
    }

    /**
     * Drops plugin-owned data sources whose containers are gone. Called after each successful
     * `docker ps` snapshot — catches containers that died while the IDE was closed or the stream was down.
     */
    fun reconcile(runningIds: Set<String>) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val runningShort = runningIds.mapTo(mutableSetOf()) { it.take(7) }
            var removed = 0
            for (ds in dataSources()) {
                val shortId = pluginOwnedShortId(ds.name) ?: continue
                if (shortId !in runningShort) {
                    manager.removeDataSource(ds)
                    removed++
                }
            }
            if (removed > 0) LOG.info("tcds: reconcile removed $removed stale data source(s)")
        }
    }

    fun trackedNames(): List<String> = dataSources().map { it.name }

    private fun dataSources(): List<LocalDataSource> =
        manager.dataSources.filterIsInstance<LocalDataSource>()

    private fun tracked(id: String): LocalDataSource? {
        val shortId = id.take(7)
        return dataSources().firstOrNull { it.name.endsWith(" $shortId") && pluginOwnedShortId(it.name) != null }
    }

    private fun dsName(info: ContainerInfo): String =
        "${TcSettings.getInstance().namePrefix} ${info.imageBase} ${info.shortId}"

    /** Returns the 7-char container id if the data source name looks like one owned by this plugin. */
    private fun pluginOwnedShortId(name: String): String? {
        val prefix = TcSettings.getInstance().namePrefix
        if (!name.startsWith("$prefix ")) return null
        val shortId = name.substringAfterLast(' ')
        return if (shortId.length == 7 && shortId.all { it.isDigit() || it in 'a'..'f' }) shortId else null
    }

    /**
     * The database usually needs a few seconds after container start before it accepts connections;
     * trigger driver download + introspection afterwards.
     */
    private fun scheduleSync(ds: LocalDataSource) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                Thread.sleep(SYNC_DELAY_MS)
            } catch (_: InterruptedException) {
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                runCatching {
                    val ctx = LoaderContext.selectGeneralTask(project, ds)
                    performAutoSyncTask(ctx, true)
                }
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(DataSourceRegistrar::class.java)
        private const val SYNC_DELAY_MS = 3000L
    }
}
