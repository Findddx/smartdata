package io.smartdata.foundation.runtime.workspace

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

class LocalWorkspaceStoreTest {
    @TempDir lateinit var parent: Path

    @Test
    fun `directory state and lock files grant access only to the current local owner`() {
        val root = parent.resolve("private-workspace")
        val store = LocalWorkspaceStore(root)
        store.mutate("tenant-a", 0, "permission-test", "fingerprint") { state ->
            state.messages += AssistantMessage("message-1", "assistant-1", "operator", "question", "answer", emptyList(), "2026-10-25T08:00:00Z")
        }
        val paths = listOf(root) + Files.list(root).use { stream -> stream.toList() }
        assertTrue(paths.size >= 3)
        paths.forEach { path ->
            val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
            if (posix != null) {
                assertEquals(PosixFilePermissions.fromString(if (Files.isDirectory(path)) "rwx------" else "rw-------"), posix.readAttributes().permissions())
            } else {
                val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
                assertNotNull(acl)
                assertTrue(acl.acl.isNotEmpty())
                assertTrue(acl.acl.all { it.type() == AclEntryType.ALLOW && it.principal() == acl.owner }, "no inherited group or other-user access may remain")
            }
        }
        assertEquals(1, LocalWorkspaceStore(root).read("tenant-a").stateVersion)
    }
}
