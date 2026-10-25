package io.smartdata.foundation.runtime.postgres

import java.time.Clock
import javax.sql.DataSource

/** Explicit production-storage capability, never a simulator or an authorization fallback. */
class PostgresRepositories(dataSource:DataSource,val clock:Clock=Clock.systemUTC(),queryTimeoutSeconds:Int=5) {
    val unitOfWork=PostgresUnitOfWork(dataSource,queryTimeoutSeconds)
    private val references=PostgresReferences()
    val cases=PostgresCaseRepository(unitOfWork,references)
    val recommendations=PostgresRecommendationRepository(unitOfWork,references)
    val actions=PostgresActionRequestRepository(unitOfWork,references)
    val receipts=PostgresActionReceiptRepository(unitOfWork,references)
    val verifications=PostgresVerificationRepository(unitOfWork,references)
    val memories=PostgresMemoryRepository(unitOfWork,references)
    val audits=PostgresAuditRepository(unitOfWork)
    val outbox=PostgresOutbox(unitOfWork)
    val commands=PostgresCommandJournal(unitOfWork)
}
