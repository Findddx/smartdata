package io.smartdata.foundation.runtime.workspace

import io.smartdata.foundation.service.BusinessValidationException
import io.smartdata.foundation.service.IdempotencyConflictException
import io.smartdata.foundation.service.OptimisticStateException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Local persistence is a deliberate deployment mode, not a substitute for production PostgreSQL. */
interface WorkspaceStorePort {
    fun read(tenantId: String): PersistedWorkspace
    fun mutate(tenantId: String, expectedVersion: Long, key: String, fingerprint: String, mutation: (PersistedWorkspace) -> Unit): PersistedWorkspace
}

class LocalWorkspaceStore(private val root: Path) : WorkspaceStorePort {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val processOwner: UserPrincipal by lazy {
        root.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
    }

    init {
        Files.createDirectories(root)
        require(!Files.isSymbolicLink(root)) { "工作区数据目录不能是符号链接" }
        protect(root, directory = true)
    }

    override fun read(tenantId: String): PersistedWorkspace = locked(tenantId) { load(tenantId) }

    override fun mutate(tenantId: String, expectedVersion: Long, key: String, fingerprint: String, mutation: (PersistedWorkspace) -> Unit): PersistedWorkspace = locked(tenantId) {
        require(expectedVersion >= 0) { "expectedStateVersion 不能为负数" }
        val current = load(tenantId)
        val commandId = digest("$tenantId\u0000$key")
        current.commands[commandId]?.let { previous ->
            if (previous != fingerprint) throw IdempotencyConflictException()
            // Rehydrate the latest authorized view after restart rather than storing a sensitive HTTP response.
            return@locked current
        }
        if (current.stateVersion != expectedVersion) throw OptimisticStateException("workspace", tenantId, expectedVersion, current.stateVersion)
        if (current.commands.size >= 10000) throw BusinessValidationException("本地工作区达到 10000 条命令保留上限，请迁移持久化适配器后继续")
        mutation(current)
        current.stateVersion += 1
        current.commands[commandId] = fingerprint
        save(tenantId, current)
        current
    }

    private fun <T> locked(tenantId: String, operation: () -> T): T {
        require(tenantId.isNotBlank() && tenantId.length <= 128)
        val filename = digest(tenantId)
        return locks.computeIfAbsent(filename) { ReentrantLock() }.withLock {
            val lockPath = root.resolve("$filename.lock")
            require(!Files.isSymbolicLink(lockPath)) { "工作区锁文件不能是符号链接" }
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                protect(lockPath, directory = false)
                val processLock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                    ?: throw BusinessValidationException("另一个工作区进程正在更新，请稍后重试")
                processLock.use { operation() }
            }
        }
    }

    private fun load(tenantId: String): PersistedWorkspace {
        val file = root.resolve("${digest(tenantId)}.json")
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return PersistedWorkspace(tenantId = tenantId)
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)) { "工作区状态不是普通文件" }
        protect(file, directory = false)
        require(Files.size(file) <= MAX_STORE_BYTES) { "本地工作区文件超过 32 MiB 上限" }
        val state = mapper.readValue(Files.readAllBytes(file), PersistedWorkspace::class.java)
        require(state.tenantId == tenantId && state.formatVersion == 1) { "工作区租户或格式校验失败，拒绝加载" }
        return state
    }

    private fun save(tenantId: String, state: PersistedWorkspace) {
        val bytes = mapper.writeValueAsBytes(state)
        require(bytes.size <= MAX_STORE_BYTES) { "本地工作区超过 32 MiB 上限，请缩小数据或迁移持久化适配器" }
        val target = root.resolve("${digest(tenantId)}.json")
        require(!Files.isSymbolicLink(target)) { "工作区状态不能是符号链接" }
        val temporary = Files.createTempFile(root, ".workspace-", ".tmp")
        try {
            // Protect before writing the first byte. The parent directory was
            // already restricted, so even creation is inside a private boundary.
            protect(temporary, directory = false)
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            // Atomic replacement is mandatory: unsupported filesystems fail rather than risking a partial state.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            protect(target, directory = false)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Refuse filesystems where private local state cannot actually be enforced. */
    private fun protect(path: Path, directory: Boolean) {
        require(!Files.isSymbolicLink(path)) { "工作区私有存储不能使用符号链接" }
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        if (posix != null) {
            val permissions = PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------")
            posix.setPermissions(permissions)
            require(posix.readAttributes().permissions() == permissions) { "无法强制工作区仅当前用户可访问" }
            return
        }
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: throw BusinessValidationException("当前文件系统不支持私有权限控制，拒绝保存业务快照")
        val entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(processOwner)
            .setPermissions(AclEntryPermission.values().toSet()).also {
                if (directory) it.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
            }.build()
        try {
            if (acl.owner != processOwner) acl.owner = processOwner
            acl.acl = listOf(entry)
            require(acl.owner == processOwner && acl.acl.isNotEmpty() &&
                acl.acl.all { it.type() == AclEntryType.ALLOW && it.principal() == processOwner }) {
                "工作区权限仍包含其他主体，拒绝读取或保存原始业务数据"
            }
        } catch (failure: Exception) {
            throw BusinessValidationException("无法建立当前用户专用的工作区访问权限，拒绝读取或保存业务快照")
        }
    }

    private companion object { const val MAX_STORE_BYTES = 32 * 1024 * 1024 }
}

internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
