package com.ifsp.action

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Unit tests for [RightClickViewFileSize].
 *
 * IntelliJ Platform classes (NotificationGroupManager, ApplicationManager, etc.)
 * are statically mocked so the tests run outside of a full IDE environment.
 *
 * Dependencies required (add to build.gradle / pom.xml):
 *   - org.mockito:mockito-core
 *   - org.mockito.kotlin:mockito-kotlin
 *   - org.junit.jupiter:junit-jupiter
 */
class RightClickViewFileSizeTest {

    // -------------------------------------------------------------------------
    // Mocks & static-mock handles
    // -------------------------------------------------------------------------

    private lateinit var event: AnActionEvent
    private lateinit var project: Project
    private lateinit var virtualFile: VirtualFile
    private lateinit var notificationGroupManager: NotificationGroupManager
    private lateinit var notificationGroup: NotificationGroup
    private lateinit var notification: Notification

    private lateinit var mockStaticNGM: MockedStatic<NotificationGroupManager>

    private lateinit var action: RightClickViewFileSize

    @BeforeEach
    fun setUp() {
        event                 = mock(AnActionEvent::class.java)
        project               = mock(Project::class.java)
        virtualFile           = mock(VirtualFile::class.java)
        notificationGroupManager = mock(NotificationGroupManager::class.java)
        notificationGroup     = mock(NotificationGroup::class.java)
        notification          = mock(Notification::class.java)

        // Wire NotificationGroupManager static factory
        mockStaticNGM = mockStatic(NotificationGroupManager::class.java)
        mockStaticNGM
            .`when`<NotificationGroupManager> { NotificationGroupManager.getInstance() }
            .thenReturn(notificationGroupManager)

        `when`(notificationGroupManager.getNotificationGroup("FileSizePlugin"))
            .thenReturn(notificationGroup)
        `when`(
            notificationGroup.createNotification(
                anyString(), anyString(), org.mockito.kotlin.any<NotificationType>()
            )
        ).thenReturn(notification)

        // Common event stubs
        `when`(event.project).thenReturn(project)
        `when`(event.getData(CommonDataKeys.VIRTUAL_FILE)).thenReturn(virtualFile)
        `when`(virtualFile.exists()).thenReturn(true)

        action = RightClickViewFileSize()
    }

    @AfterEach
    fun tearDown() {
        mockStaticNGM.close()
    }

    @Test
    fun `actionPerformed does nothing when virtual file is null`() {
        `when`(event.getData(CommonDataKeys.VIRTUAL_FILE)).thenReturn(null)

        action.actionPerformed(event)

        verify(notificationGroup, never())
            .createNotification(anyString(), anyString(), org.mockito.kotlin.any<NotificationType>())
    }

    @Test
    fun `actionPerformed does nothing when file does not exist`() {
        `when`(virtualFile.exists()).thenReturn(false)

        action.actionPerformed(event)

        verify(notificationGroup, never())
            .createNotification(anyString(), anyString(), org.mockito.kotlin.any<NotificationType>())
    }

    @Test
    fun `actionPerformed shows ERROR notification for directory`() {
        `when`(virtualFile.isDirectory).thenReturn(true)
        `when`(virtualFile.name).thenReturn("myDir")

        action.actionPerformed(event)

        verify(notificationGroup).createNotification(
            eq("myDir"),
            eq("Directory selected! Must select a file."),
            eq(NotificationType.ERROR)
        )
        verify(notification).notify(project)
    }

    @Test
    fun `actionPerformed returns early after directory error – no further notifications`() {
        `when`(virtualFile.isDirectory).thenReturn(true)
        `when`(virtualFile.name).thenReturn("someDir")

        action.actionPerformed(event)

        // createNotification must be called exactly once (for the error, not an info one)
        verify(notificationGroup, times(1))
            .createNotification(anyString(), anyString(), org.mockito.kotlin.any<NotificationType>())
    }


    @Test
    fun `actionPerformed shows INFORMATION notification for a regular file`() {
        `when`(virtualFile.isDirectory).thenReturn(false)
        `when`(virtualFile.name).thenReturn("readme.txt")
        `when`(virtualFile.extension).thenReturn("txt")
        `when`(virtualFile.length).thenReturn(2048L)      // 2 KB

        action.actionPerformed(event)

        val messageCaptor = argumentCaptor<String>()
        verify(notificationGroup).createNotification(
            eq("readme.txt"),
            messageCaptor.capture(),
            eq(NotificationType.INFORMATION)
        )
        assertTrue(messageCaptor.firstValue.contains("2.00 KB"),
            "Notification should mention formatted file size")
        verify(notification).notify(project)
    }

    @Test
    fun `actionPerformed message does NOT contain unzipped size for non-JAR file`() {
        `when`(virtualFile.isDirectory).thenReturn(false)
        `when`(virtualFile.name).thenReturn("data.csv")
        `when`(virtualFile.extension).thenReturn("csv")
        `when`(virtualFile.length).thenReturn(512L)

        action.actionPerformed(event)

        val messageCaptor = argumentCaptor<String>()
        verify(notificationGroup).createNotification(
            anyString(), messageCaptor.capture(), org.mockito.kotlin.any<NotificationType>()
        )
        assertFalse(messageCaptor.firstValue.contains("Unzipped"),
            "Non-JAR notification must not contain unzipped size")
    }

    @Test
    fun `actionPerformed shows unzipped size for JAR file`(@TempDir tempDir: Path) {
        val jarFile = createTestJar(tempDir, mapOf("A.class" to ByteArray(1024), "B.class" to ByteArray(2048)))

        `when`(virtualFile.isDirectory).thenReturn(false)
        `when`(virtualFile.name).thenReturn("lib.jar")
        `when`(virtualFile.extension).thenReturn("jar")
        `when`(virtualFile.path).thenReturn(jarFile.absolutePath)
        // compressed on disk will be smaller; we report that as-is
        `when`(virtualFile.length).thenReturn(jarFile.length())

        action.actionPerformed(event)

        val messageCaptor = argumentCaptor<String>()
        verify(notificationGroup).createNotification(
            eq("lib.jar"),
            messageCaptor.capture(),
            eq(NotificationType.INFORMATION)
        )

        val message = messageCaptor.firstValue
        assertTrue(message.contains("Original file size"),  "Should contain original size label")
        assertTrue(message.contains("Unzipped"),            "Should contain unzipped size for JAR")
        // 1024 + 2048 = 3072 bytes = 3.00 KB unzipped
        assertTrue(message.contains("3.00 KB"),             "Unzipped size should be 3.00 KB")
    }

    @Test
    fun `formatSize returns bytes for values under 1 KB`() {
        stubRegularFile("tiny.bin", "bin", 512L)
        action.actionPerformed(event)
        assertMessageContains("512 bytes")
    }

    @Test
    fun `formatSize returns KB for values between 1 KB and 1 MB`() {
        stubRegularFile("medium.bin", "bin", 1024L)
        action.actionPerformed(event)
        assertMessageContains("1.00 KB")
    }

    @Test
    fun `formatSize returns MB for values between 1 MB and 1 GB`() {
        stubRegularFile("big.bin", "bin", 1024L * 1024)
        action.actionPerformed(event)
        assertMessageContains("1.00 MB")
    }

    @Test
    fun `formatSize returns GB for values 1 GB and above`() {
        stubRegularFile("huge.bin", "bin", 1024L * 1024 * 1024)
        action.actionPerformed(event)
        assertMessageContains("1.00 GB")
    }

    @Test
    fun `formatSize handles zero bytes`() {
        stubRegularFile("empty.bin", "bin", 0L)
        action.actionPerformed(event)
        assertMessageContains("0 bytes")
    }


    @Test
    fun `actionPerformed handles empty JAR gracefully`(@TempDir tempDir: Path) {
        val emptyJar = createTestJar(tempDir, emptyMap())

        `when`(virtualFile.isDirectory).thenReturn(false)
        `when`(virtualFile.name).thenReturn("empty.jar")
        `when`(virtualFile.extension).thenReturn("jar")
        `when`(virtualFile.path).thenReturn(emptyJar.absolutePath)
        `when`(virtualFile.length).thenReturn(emptyJar.length())

        // Should not throw
        assertDoesNotThrow { action.actionPerformed(event) }

        val messageCaptor = argumentCaptor<String>()
        verify(notificationGroup).createNotification(
            anyString(), messageCaptor.capture(), eq(NotificationType.INFORMATION)
        )
        assertTrue(messageCaptor.firstValue.contains("Unzipped"),
            "Even an empty JAR should show the unzipped line")
    }

    @Test
    fun `actionPerformed uses file name as notification title`() {
        `when`(virtualFile.isDirectory).thenReturn(false)
        `when`(virtualFile.name).thenReturn("special-name_123.txt")
        `when`(virtualFile.extension).thenReturn("txt")
        `when`(virtualFile.length).thenReturn(100L)

        action.actionPerformed(event)

        verify(notificationGroup).createNotification(
            eq("special-name_123.txt"),
            anyString(),
            org.mockito.kotlin.any<NotificationType>()
        )
    }

    @Test
    fun `actionPerformed notifies correct project`() {
        val anotherProject = mock(Project::class.java)
        `when`(event.project).thenReturn(anotherProject)
        `when`(virtualFile.isDirectory).thenReturn(false)
        `when`(virtualFile.name).thenReturn("file.txt")
        `when`(virtualFile.extension).thenReturn("txt")
        `when`(virtualFile.length).thenReturn(100L)

        action.actionPerformed(event)

        verify(notification).notify(anotherProject)
        verify(notification, never()).notify(project)
    }


    /** Stub [virtualFile] as an ordinary (non-JAR) file. */
    private fun stubRegularFile(name: String, ext: String, size: Long) {
        `when`(virtualFile.isDirectory).thenReturn(false)
        `when`(virtualFile.name).thenReturn(name)
        `when`(virtualFile.extension).thenReturn(ext)
        `when`(virtualFile.length).thenReturn(size)
    }

    /** Assert that the captured notification message contains [substring]. */
    private fun assertMessageContains(substring: String) {
        val captor = argumentCaptor<String>()
        verify(notificationGroup).createNotification(
            anyString(), captor.capture(), org.mockito.kotlin.any<NotificationType>()
        )
        assertTrue(captor.firstValue.contains(substring),
            "Expected message to contain '$substring' but was: '${captor.firstValue}'")
    }

    /**
     * Creates a real ZIP/JAR file on disk whose entries match [entries].
     * Keys are entry names, values are raw uncompressed bytes.
     */
    private fun createTestJar(tempDir: Path, entries: Map<String, ByteArray>): File {
        val jar = tempDir.resolve("test.jar").toFile()
        ZipOutputStream(jar.outputStream()).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return jar
    }
}