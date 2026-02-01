package com.ifsp.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.thisLogger

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.isFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class RightClickViewFileSize : AnAction("View File Size") {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    override fun actionPerformed(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)

        if (file != null && file.exists()) {

            if (file.isDirectory) {
                val notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup("FileSizePlugin")
                    .createNotification(
                        file.name,
                        "Directory selected! Must select a file.",
                        NotificationType.ERROR
                    )

                notification.notify(e.project)

                scheduler.schedule(
                    {
                        ApplicationManager.getApplication().invokeLater {
                            notification.expire()
                        }
                    },
                    3,
                    TimeUnit.SECONDS
                )
                return
            }

            val project = e.project;

            thisLogger().warn("Right-click action invoked on file: ${file.path}")


            var message = "Original file size : ${formatSize(file.length)}<br>".trimIndent();

            if (file.extension == "jar") {
                message+="Unzipped : ${formatSize(calculateUnzippedJarSize(file.path))}<br>"
            }

            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("FileSizePlugin")
                .createNotification(
                    file.name,
                    message,
                    NotificationType.INFORMATION
                )

            notification.notify(project)

            scheduler.schedule(
                {
                    ApplicationManager.getApplication().invokeLater {
                        notification.expire()
                    }
                },
                10,
                TimeUnit.SECONDS
            )
        } else {
            thisLogger().warn("Right-click action invoked, but no file selected")
        }
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> "%.2f GB".format(gb)
            mb >= 1 -> "%.2f MB".format(mb)
            kb >= 1 -> "%.2f KB".format(kb)
            else -> "$bytes bytes"
        }
    }

    private fun calculateUnzippedJarSize(jarPath: String): Long {
        ZipFile(jarPath).use { zip ->
            var totalSize = 0L
            val entries = zip.entries()

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    totalSize += entry.size
                }
            }
            return totalSize
        }
    }
}
