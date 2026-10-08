package tcds.docker

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import tcds.TcSettings
import tcds.ds.DataSourceRegistrar
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Watches Docker for Testcontainers containers: keeps a `docker events` stream open,
 * falls back to periodic daemon checks + `docker ps` snapshots when the stream dies.
 */
@Service(Service.Level.PROJECT)
class TcWatcherService(private val project: Project) : Disposable {

    private val cli = DockerCli()
    private val registrar = DataSourceRegistrar(project)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "tcds-scheduler").apply { isDaemon = true }
    }

    @Volatile private var eventsProcess: Process? = null
    @Volatile private var stopped = false
    @Volatile private var streamAlive = false

    fun start() {
        val settings = TcSettings.getInstance()
        LOG.info("tcds: watcher start requested (enabled=${settings.enabled}, project=${project.name})")
        if (!settings.enabled) return
        scheduler.execute(::ensureWatching)
    }

    private fun ensureWatching() {
        if (stopped || project.isDisposed) return
        val version = cli.daemonVersion()
        if (version == null) {
            LOG.info("tcds: docker daemon not reachable, retrying in ${DAEMON_RETRY_SECONDS}s")
            schedule(::ensureWatching, DAEMON_RETRY_SECONDS)
            return
        }
        LOG.info("tcds: docker daemon up (server=$version)")
        reconcileSnapshot()
        if (!streamAlive) startEventsStream()
    }

    /** Registers containers that are already up and drops data sources for dead ones. */
    private fun reconcileSnapshot() {
        val running = cli.runningContainerIds()
        LOG.info("tcds: snapshot — ${running.size} testcontainers container(s) running")
        if (running.isNotEmpty()) {
            running.mapNotNull { cli.inspect(it) }.forEach { registrar.registerContainer(it) }
        }
        registrar.reconcile(running.toSet())
    }

    private fun startEventsStream() {
        try {
            val dockerBin = System.getenv("TCDS_DOCKER_BINARY") ?: "docker"
            val process = ProcessBuilder(
                dockerBin, "events",
                "--filter", "type=container",
                "--filter", "label=${DockerJson.TC_LABEL}=true",
                "--format", "json",
            ).start()
            eventsProcess = process
            streamAlive = true
            LOG.info("tcds: docker events stream opened")
            Thread({
                try {
                    process.inputStream.bufferedReader().forEachLine(::handleEventLine)
                } catch (e: Exception) {
                    LOG.warn("tcds: events stream reader failed: ${e.message}")
                } finally {
                    streamAlive = false
                    eventsProcess = null
                    runCatching { process.destroyForcibly() }
                    LOG.info("tcds: events stream ended, reconnecting in ${RECONNECT_SECONDS}s")
                    schedule(::ensureWatching, RECONNECT_SECONDS)
                }
            }, "tcds-docker-events").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            LOG.warn("tcds: failed to open docker events: ${e.message}")
            streamAlive = false
            schedule(::ensureWatching, DAEMON_RETRY_SECONDS)
        }
    }

    private fun handleEventLine(line: String) {
        if (stopped || project.isDisposed) return
        val event = DockerJson.parseEvent(line) ?: return
        when (event.action) {
            "start" -> {
                val info = cli.inspect(event.containerId)
                LOG.info("tcds: container ${event.containerId.take(7)} started (image=${info?.image}, ports=${info?.hostPorts})")
                info?.let(registrar::registerContainer)
            }
            "die", "destroy" -> registrar.removeContainer(event.containerId)
        }
    }

    private fun schedule(task: () -> Unit, delaySeconds: Long) {
        scheduler.schedule({
            if (!stopped && !project.isDisposed) task()
        }, delaySeconds, TimeUnit.SECONDS)
    }

    override fun dispose() {
        stopped = true
        eventsProcess?.destroyForcibly()
        scheduler.shutdownNow()
    }

    companion object {
        private val LOG = Logger.getInstance(TcWatcherService::class.java)
        private const val DAEMON_RETRY_SECONDS = 30L
        private const val RECONNECT_SECONDS = 10L

        fun getInstance(project: Project): TcWatcherService = project.service<TcWatcherService>()
    }
}
