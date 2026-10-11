package tcds

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import tcds.docker.TcWatcherService

class TcStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // registration self-check: a wrong plugin.xml tag makes the action vanish silently otherwise
        if (ActionManager.getInstance().getAction("Tcds.CopyConnectionAs") == null) {
            LOG.error("tcds: Copy Connection As action is not registered - check the <group> tag in plugin.xml")
        } else {
            LOG.info("tcds: Copy Connection As action registered")
        }
        TcWatcherService.getInstance(project).start()
    }

    private companion object {
        val LOG = Logger.getInstance(TcStartupActivity::class.java)
    }
}
