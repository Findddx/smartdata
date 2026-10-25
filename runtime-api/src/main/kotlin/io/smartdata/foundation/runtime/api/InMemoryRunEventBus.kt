package io.smartdata.foundation.runtime.api

import io.smartdata.foundation.domain.StableIds
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

@Component
@Profile("local")
class InMemoryRunEventBus(
    private val clock: Clock,
) {
    private data class RunKey(val tenantId: String, val runId: String)

    private val sequence = AtomicLong()
    private val history = ConcurrentHashMap<RunKey, ArrayDeque<RunEvent>>()
    private val subscribers = ConcurrentHashMap<RunKey, CopyOnWriteArrayList<SseEmitter>>()
    private val keyLocks = ConcurrentHashMap<RunKey, Any>()

    fun publish(tenantId: String, runId: String, type: String, data: Map<String, String> = emptyMap()): RunEvent {
        val key = RunKey(tenantId, runId)
        val number = sequence.incrementAndGet()
        val event = RunEvent(
            id = StableIds.from("run-event", tenantId, runId, number),
            tenantId = tenantId,
            runId = runId,
            type = type,
            occurredAt = clock.instant(),
            data = data,
        )
        val events = history.computeIfAbsent(key) { ArrayDeque() }
        synchronized(keyLocks.computeIfAbsent(key) { Any() }) {
            events.addLast(event)
            while (events.size > MAX_HISTORY) events.removeFirst()
        }
        // Network writes deliberately happen outside every shared lock. A slow
        // local-demo client can delay only this publisher, never another tenant.
        subscribers[key]?.toList().orEmpty().forEach { emitter -> sendOrRemove(key, emitter, event) }
        return event
    }

    fun subscribe(tenantId: String, runId: String): SseEmitter {
        val key = RunKey(tenantId, runId)
        val emitter = SseEmitter(30 * 60 * 1000L)
        val runSubscribers = subscribers.computeIfAbsent(key) { CopyOnWriteArrayList() }
        val existing: List<RunEvent>
        synchronized(keyLocks.computeIfAbsent(key) { Any() }) {
            if (runSubscribers.size >= MAX_SUBSCRIBERS_PER_RUN ||
                subscribers.asSequence()
                    .filter { it.key.tenantId == tenantId }
                    .sumOf { it.value.size } >= MAX_SUBSCRIBERS_PER_TENANT
            ) {
                throw IllegalStateException("本地演示事件订阅数已达到安全上限")
            }
            runSubscribers += emitter
            existing = history[key]?.toList().orEmpty()
        }
        emitter.onCompletion { removeSubscriber(key, emitter) }
        emitter.onTimeout { removeSubscriber(key, emitter); emitter.complete() }
        emitter.onError { removeSubscriber(key, emitter) }
        if (existing.isEmpty()) {
            sendOrRemove(key, emitter, RunEvent(
                id = StableIds.from("run-event", tenantId, runId, "connected"),
                tenantId = tenantId,
                runId = runId,
                type = "run.connected",
                occurredAt = clock.instant(),
                data = mapOf("messageZh" to "事件流已连接"),
            ))
        } else {
            existing.forEach { sendOrRemove(key, emitter, it) }
        }
        return emitter
    }

    private fun removeSubscriber(key: RunKey, emitter: SseEmitter) {
        subscribers[key]?.let { current ->
            current.remove(emitter)
            if (current.isEmpty()) subscribers.remove(key, current)
        }
    }

    private fun sendOrRemove(key: RunKey, emitter: SseEmitter, event: RunEvent) {
        try {
            emitter.send(SseEmitter.event().id(event.id).name(event.type).data(event))
        } catch (_: Exception) {
            removeSubscriber(key, emitter)
            emitter.complete()
        }
    }

    private companion object {
        const val MAX_HISTORY = 100
        const val MAX_SUBSCRIBERS_PER_RUN = 25
        const val MAX_SUBSCRIBERS_PER_TENANT = 100
    }
}
