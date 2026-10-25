import { vi } from 'vitest'
import { smartDataClient } from './client'

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('SmartData API client hydration', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('restores the latest action, receipt and independent verification from a case timeline', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('/cases/CASE-1/timeline')) {
        return json([
          { aggregateType: 'actionRequest', aggregateId: 'ACT-OLD', eventType: 'action.rejected', actorId: 'warehouse-approver', occurredAt: '2026-09-07T07:55:00Z', details: { caseId: 'CASE-1' } },
          { aggregateType: 'recommendation', aggregateId: 'REC-1', eventType: 'recommendation.created', actorId: 'order-specialist', occurredAt: '2026-09-07T08:00:00Z', details: { caseId: 'CASE-1' } },
          { aggregateType: 'actionRequest', aggregateId: 'ACT-1', eventType: 'verification.passed', actorId: 'auditor', occurredAt: '2026-09-07T08:05:00Z', details: { caseId: 'CASE-1' } },
        ])
      }
      if (path.endsWith('/action-requests/ACT-1')) {
        return json({
          id: 'ACT-1', caseId: 'CASE-1', recommendationId: 'REC-1', definitionId: 'reserve_substitute_inventory', definitionVersion: '1.0.0', input: {}, requestedBy: 'order-specialist', approvedBy: 'warehouse-approver', state: 'VERIFIED_SUCCEEDED', stateVersion: 6, boundCaseStateVersion: 2, updatedAt: '2026-09-07T08:05:00Z',
        })
      }
      if (path.endsWith('/action-requests/ACT-1/receipt')) {
        return json({
          id: 'RCP-1', externalRequestId: 'WMS-REQ-1', externalReceiptId: 'WMS-RSV-1', outcome: 'SUCCEEDED', attempts: [{ number: 1, startedAt: '2026-09-07T08:02:00Z', completedAt: '2026-09-07T08:03:00Z', outcome: 'SUCCEEDED', messageZh: '预留完成' }], verificationId: 'VRF-1', createdAt: '2026-09-07T08:02:00Z', updatedAt: '2026-09-07T08:05:00Z',
        })
      }
      if (path.endsWith('/verifications/ACT-1')) {
        return json({
          id: 'VRF-1', verifierId: 'reservation-readback@1', independentSource: 'WMS_READ_MODEL', outcome: 'PASSED', assertions: [{ name: 'reservation_exists', expected: 'true', observed: 'true', passed: true }], evidenceRefs: [{ id: 'EV-VRF-1', sourceSystem: 'WMS', sourceRecordId: 'WMS-RSV-1', contentHash: 'sha256:test', observedAt: '2026-09-07T08:05:00Z', classification: 'INTERNAL' }], observedFrom: '2026-09-07T08:03:00Z', observedUntil: '2026-09-07T08:05:00Z', antiGamingMetrics: { executor_self_report_weight: 0 }, createdAt: '2026-09-07T08:05:00Z',
        })
      }
      if (path.endsWith('/memory-candidates')) {
        return json([{ id: 'MEM-1', level: 'L1_ATOMIC_CANDIDATE', status: 'PROPOSED', titleZh: '已验证库存预留', contentZh: '从独立回读形成的候选经验。', sourceCaseIds: ['CASE-1'], sourceEvidenceIds: ['EV-VRF-1'], validFrom: '2026-09-07T08:05:00Z', ownerId: 'sales-owner', aclRef: 'warehouse:cn', modelVersion: 'deterministic-stub@1', stateVersion: 0, createdAt: '2026-09-07T08:05:00Z' }])
      }
      return json({ message: 'not found' }, 404)
    })
    vi.stubGlobal('fetch', fetchMock)

    const hydrated = await smartDataClient.getActionHydration('CASE-1')

    expect(hydrated.source).toBe('live')
    expect(hydrated.data.action).toMatchObject({ id: 'ACT-1', state: 'VERIFIED_SUCCEEDED', stateVersion: 6 })
    expect(hydrated.data.execution?.receipt).toMatchObject({ id: 'RCP-1', externalReceiptId: 'WMS-RSV-1' })
    expect(hydrated.data.verification?.verification).toMatchObject({ id: 'VRF-1', outcome: 'PASSED', independentSource: 'WMS_READ_MODEL' })
    expect(hydrated.data.verification?.memoryCandidate?.id).toBe('MEM-1')
    expect(fetchMock).toHaveBeenCalledTimes(5)
  })

  it('prefers the simulator warehouseCode claim while retaining warehouseId compatibility', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({
      id: 'CASE-2',
      tenantId: 'TENANT-DEMO',
      orderId: 'SO-2',
      titleZh: '订单履约异常：库存账实不符',
      priority: 'HIGH',
      status: 'OPEN',
      scenarioKinds: ['INVENTORY_MISMATCH'],
      gapCount: 0,
      stateVersion: 1,
      domainVersion: '1.0.0',
      policyVersion: '1.0.0',
      createdAt: '2026-09-07T08:00:00Z',
      updatedAt: '2026-09-07T08:01:00Z',
      claims: [
        {
          id: 'CLM-CODE', subjectType: 'Inventory', subjectId: 'INV-2', predicate: 'warehouseCode', value: 'WH-SH-02', evidenceRefs: [], validFrom: '2026-09-07T08:00:00Z', recordedAt: '2026-09-07T08:00:10Z', classification: 'INTERNAL', confidence: 1, status: 'ASSERTED',
        },
        {
          id: 'CLM-LEGACY', subjectType: 'Inventory', subjectId: 'INV-2', predicate: 'warehouseId', value: 'legacy-warehouse', evidenceRefs: [], validFrom: '2026-09-07T08:00:00Z', recordedAt: '2026-09-07T08:00:10Z', classification: 'INTERNAL', confidence: 1, status: 'ASSERTED',
        },
      ],
      knowledgeGaps: [],
    })))

    const result = await smartDataClient.getCase('CASE-2')

    expect(result.source).toBe('live')
    expect(result.data.destination).toBe('WH-SH-02')
  })
})
