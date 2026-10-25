package io.smartdata.foundation.runtime.workspace

import io.smartdata.foundation.service.OptimisticStateException
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

fun interface ModelBudgetPort {
    /** Reserve before any network operation. Failed calls are deliberately never refunded. */
    fun reserve(tenantId: String, units: Long, maxCalls: Int, maxUnits: Long): Boolean
}

/** Shares the private ACL, process lock and atomic replacement guarantees of the local workspace store. */
class LocalModelBudget(private val directory: Path, private val clock: Clock) : ModelBudgetPort {
    override fun reserve(tenantId: String, units: Long, maxCalls: Int, maxUnits: Long): Boolean {
        val day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString()
        val store = LocalWorkspaceStore(directory.resolve(day))
        repeat(3) {
            val state = store.read(tenantId)
            val reservations = state.modelBudgetReservations
            require(units > 0 && reservations.all { it.reservedTokens in 1..100000000 }) { "模型额度账本无效" }
            val used = reservations.fold(0L) { total, item -> Math.addExact(total, item.reservedTokens) }
            if (reservations.size >= maxCalls || units > maxUnits - used) return false
            val reservation = ModelBudgetReservation(UUID.randomUUID().toString(), units)
            try {
                store.mutate(tenantId, state.stateVersion, reservation.id, digest(reservation.toString())) {
                    it.modelBudgetReservations += reservation
                }
                return true
            } catch (_: OptimisticStateException) {
                // Another admission consumed quota. Re-read; never send before durable admission.
            }
        }
        return false
    }
}
