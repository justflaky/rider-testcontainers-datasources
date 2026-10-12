package tcds

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import tcds.catalog.JdbcTarget

object TcNotifications {

    private const val GROUP_ID = "TC DataSources"

    fun dataSourceAdded(project: Project, name: String, target: JdbcTarget) {
        if (!TcSettings.getInstance().notifyOnAdded) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification("Data source created: $name", target.url, NotificationType.INFORMATION)
            .addAction(openDatabaseToolWindowAction())
            .notify(project)
    }

    fun dataSourceRemoved(project: Project, name: String) {
        if (!TcSettings.getInstance().notifyOnRemoved) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification("Data source removed: $name", "The container is no longer running.", NotificationType.INFORMATION)
            .notify(project)
    }

    fun dockerUnavailable(project: Project) {
        if (!TcSettings.getInstance().notifyDockerUnavailable) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                "Docker is not available",
                "Testcontainers data sources will not be tracked until the Docker daemon is reachable.",
                NotificationType.WARNING,
            )
            .notify(project)
    }

    private fun openDatabaseToolWindowAction(): AnAction =
        object : AnAction("Open Database Tool Window") {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                val project = e.project ?: return
                ToolWindowManager.getInstance(project).getToolWindow("Database")?.activate(null)
            }
        }
}
