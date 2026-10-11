package tcds.docker

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tcds.TC_SETTINGS_TOPIC
import tcds.TcNotifications
import tcds.TcSettings
import tcds.TcSettingsChangedListener
import tcds.ds.DataSourceRegistrar
import java.util.concurrent.TimeUnit

/**
 * Watches Docker for Testcontainers containers: keeps a `docker events` stream open,
 * falls back to periodic daemon checks + `docker ps` snapshots when the stream dies.
 * All blocking docker CLI calls run on [Dispatchers.IO]; the loop lives in the project
 * service coroutine scope and dies with it.
 */
@Service(Service.Level.PROJECT)
class TcWatcherService(
    private val project: Project,
    private val cs: CoroutineScope,
) : Disposable {

    private val cli = DockerCli()
    private val registrar = DataSourceRegistrar(project, cs)

    @Volatile private var eventsProcess: Process? = null
    @Volatile private var stopped = false
    @Volatile private var streamAlive = false
    @Volatile private var started = false
    @Volatile private var dockerWarned = false

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(TC_SETTINGS_TOPIC, TcSettingsChangedListener { enabled ->
                LOG.info("tcds: settings changed (enabled=$enabled)")
                if (enabled) start() else stopWatching()
            })
    }

    fun start() {
        if (stopped || project.isDisposed) return
        if (!TcSettings.getInstance().enabled) {
            LOG.info("tcds: watcher start requested but disabled in settings (project=${project.name})")
            return
        }
        if (started) return
        started = true
        LOG.info("tcds: watcher start requested (project=${project.name})")
        cs.launch { watchLoop() }
    }

    /** Live disable from the settings page: kills the stream; re-enable reopens it via start(). */
    fun stopWatching() {
        streamAlive = false
        eventsProcess?.destroyForcibly()
        eventsProcess = null
        LOG.info("tcds: watching stopped (disabled in settings)")
    }

    private suspend fun watchLoop() {
        while (!stopped && !project.isDisposed) {
            if (!TcSettings.getInstance().enabled) {
                delay(ENABLED_POLL_MS)
                continue
            }
            val version = withContext(Dispatchers.IO) { runCatching { cli.daemonVersion() }.getOrNull() }
            if (version == null) {
                LOG.info("tcds: docker daemon not reachable, retrying in ${DAEMON_RETRY_SECONDS}s")
                if (!dockerWarned) {
                    dockerWarned = true
                    TcNotifications.dockerUnavailable(project)
                }
                delay(DAEMON_RETRY_SECONDS * 1000)
                continue
            }
            dockerWarned = false
            LOG.info("tcds: docker daemon up (server=$version)")
            withContext(Dispatchers.IO) { runCatching { reconcileSnapshot() } }
            openStreamAndAwait()
            delay(RECONNECT_SECONDS * 1000)
        }
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

    private suspend fun openStreamAndAwait() {
        val dockerBin = System.getenv("TCDS_DOCKER_BINARY") ?: "docker"
        val process = try {
            ProcessBuilder(
                dockerBin, "events",
                "--filter", "type=container",
                "--filter", "label=${DockerJson.TC_LABEL}=true",
                "--format", "json",
            ).start()
        } catch (e: Exception) {
            LOG.warn("tcds: failed to open docker events: ${e.message}")
            streamAlive = false
            return
        }
        eventsProcess = process
        streamAlive = true
        LOG.info("tcds: docker events stream opened")
        try {
            withContext(Dispatchers.IO) {
                val reader = process.inputStream.bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    handleEventLine(line)
                }
            }
        } catch (e: Exception) {
            LOG.warn("tcds: events stream reader failed: ${e.message}")
        } finally {
            streamAlive = false
            eventsProcess = null
            runCatching { process.destroyForcibly() }
            LOG.info("tcds: events stream ended, reconnecting in ${RECONNECT_SECONDS}s")
        }
    }

    private suspend fun handleEventLine(line: String) {
        if (stopped || project.isDisposed) return
        val event = DockerJson.parseEvent(line) ?: return
        when (event.action) {
            "start" -> {
                val info = withContext(Dispatchers.IO) { cli.inspect(event.containerId) }
                LOG.info("tcds: container ${event.containerId.take(7)} started (image=${info?.image}, ports=${info?.hostPorts})")
                info?.let(registrar::registerContainer)
            }
            "die", "destroy" -> registrar.removeContainer(event.containerId)
        }
    }

    override fun dispose() {
        stopped = true
        eventsProcess?.destroyForcibly()
    }

    companion object {
        private val LOG = Logger.getInstance(TcWatcherService::class.java)
        private const val DAEMON_RETRY_SECONDS = 30L
        private const val RECONNECT_SECONDS = 10L
        private const val ENABLED_POLL_MS = 5_000L

        fun getInstance(project: Project): TcWatcherService = project.service<TcWatcherService>()
    }
}
