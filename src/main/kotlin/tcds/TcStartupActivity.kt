package tcds

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import tcds.docker.TcWatcherService

class TcStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        TcWatcherService.getInstance(project).start()
    }
}
