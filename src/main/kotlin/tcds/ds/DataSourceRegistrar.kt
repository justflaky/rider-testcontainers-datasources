package tcds.ds

import com.intellij.database.dataSource.DatabaseDriver
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.util.LoaderContext
import com.intellij.database.util.performAutoSyncTask
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tcds.TcNotifications
import tcds.TcSettings
import tcds.catalog.DatabaseImageCatalog
import tcds.model.ContainerInfo

/**
 * Creates/updates/removes Database tool window data sources for tracked containers.
 * All mutations run on EDT.
 *
 * Ownership: the container id is stored as an additional property ([DsOwnership.MARKER_KEY],
 * persisted with the data source) with a name-shape fallback for pre-marker data sources;
 * [reconcile] uses it to sweep data sources whose containers are gone.
 */
class DataSourceRegistrar(
    private val project: Project,
    private val cs: CoroutineScope,
) {

    private val manager: LocalDataSourceManager get() = LocalDataSourceManager.getInstance(project)

    fun registerContainer(info: ContainerInfo) {
        val target = DatabaseImageCatalog.resolve(info) ?: return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            try {
                val existing = tracked(info.id)
                when (val decision = registrationDecision(existing?.url, existing?.username, target)) {
                    is RegistrationDecision.CreateNew -> create(info, target)
                    is RegistrationDecision.NoChange ->
                        LOG.info("tcds: data source for ${info.shortId} already up to date")
                    is RegistrationDecision.UpdateInPlace -> {
                        existing!!.url = decision.url
                        existing.username = decision.user
                        manager.saveDataSource(existing)
                        LOG.info("tcds: updated data source '${existing.name}' url='${existing.url}'")
                        scheduleSync(existing)
                    }
                }
            } catch (e: Exception) {
                LOG.error("tcds: registration of ${info.shortId} failed", e)
            }
        }
    }

    private fun create(info: ContainerInfo, target: tcds.catalog.JdbcTarget) {
        tracked(info.id)?.let(manager::removeDataSource)

        val name = dsName(info)
        // Attach the predefined driver definition (carries JDBC files + auto-download):
        // LocalDataSource.create() only sets a driver *class*, which yields
        // "No driver files provided". matchesUrl + createDataSource is the same
        // path the IDE's "New Data Source" menu uses.
        val driver = runCatching {
            DatabaseDriverManager.getInstance().drivers
                .filterIsInstance<DatabaseDriver>()
                .firstOrNull { runCatching { it.matchesUrl(target.url) }.getOrDefault(false) }
        }.getOrNull()

        val ds: LocalDataSource = if (driver != null) {
            driver.createDataSource(name).also {
                it.url = target.url
                it.username = target.user
            }
        } else {
            LOG.warn("tcds: no predefined driver matches ${target.url}, falling back to bare data source")
            LocalDataSource.create(name, "", target.url, target.user)
        }
        LOG.info(
            "tcds: driver for ${info.shortId}: name=${driver?.name} id=${driver?.id} " +
                "dbms=${ds.dbms} driverRef=${ds.driverRef}",
        )
        ds.setAdditionalProperty(DsOwnership.MARKER_KEY, info.id)
        ds.setAutoSynchronize(true)
        manager.addDataSource(ds)
        LOG.info("tcds: registered data source name='${ds.name}' url='${ds.url}' user='${ds.username}' container=${info.id}")

        scheduleSync(ds)
        TcNotifications.dataSourceAdded(project, name, target)
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
            var removed = 0
            for (ds in dataSources()) {
                val ownedId = DsOwnership.ownedContainerId(
                    ds.getAdditionalProperty(DsOwnership.MARKER_KEY),
                    ds.name,
                    TcSettings.getInstance().namePrefix,
                    DatabaseImageCatalog.knownImageBases,
                ) ?: continue
                // docker ids are prefix-comparable: `docker ps -q` yields 12 chars, the marker
                // stores the full 64, the name fallback stores 7
                val alive = DsOwnership.isAlive(ownedId, runningIds)
                if (!alive) {
                    LOG.info("tcds: reconcile removing stale '${ds.name}' (owned=$ownedId)")
                    manager.removeDataSource(ds)
                    removed++
                }
            }
            if (removed > 0) LOG.info("tcds: reconcile removed $removed stale data source(s)")
        }
    }

    private fun dataSources(): List<LocalDataSource> =
        manager.dataSources.filterIsInstance<LocalDataSource>()

    private fun tracked(id: String): LocalDataSource? {
        val shortId = id.take(7)
        return dataSources().firstOrNull { ds ->
            val owned = DsOwnership.ownedContainerId(
                ds.getAdditionalProperty(DsOwnership.MARKER_KEY),
                ds.name,
                TcSettings.getInstance().namePrefix,
                DatabaseImageCatalog.knownImageBases,
            )
            owned == id || owned == shortId
        }
    }

    private fun dsName(info: ContainerInfo): String =
        "${TcSettings.getInstance().namePrefix} ${info.imageBase} ${info.shortId}"

    /**
     * The database usually needs a few seconds after container start before it accepts
     * connections; trigger driver download + introspection afterwards.
     */
    private fun scheduleSync(ds: LocalDataSource) {
        cs.launch {
            delay(SYNC_DELAY_MS)
            launch(Dispatchers.EDT) {
                if (project.isDisposed) return@launch
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
