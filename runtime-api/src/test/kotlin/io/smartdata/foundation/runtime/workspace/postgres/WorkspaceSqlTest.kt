package io.smartdata.foundation.runtime.workspace.postgres

import org.jooq.DSLContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class WorkspaceSqlTest {
    @Test fun `real snapshot and asset digest column names are accepted`() {
        assertDoesNotThrow { WorkspaceSql.validateIdentifiers("workspace_snapshot_payload",setOf("tenant_id","sha256","canonical_json")) }
        assertDoesNotThrow { WorkspaceSql.validateIdentifiers("workspace_asset_revision",setOf("tenant_id","id","scan_id","evidence_sha256","content_fingerprint")) }
    }

    @Test fun `empty leading numeric and SQL symbol column names are rejected`() {
        listOf("", "1sha256", "sha256;drop table tenant", "sha256 --", "sha256)", "sha256\u0000", "sha256.name").forEach { column ->
            assertThrows(IllegalArgumentException::class.java) { WorkspaceSql.validateIdentifiers("workspace_snapshot_payload",setOf("tenant_id",column)) }
        }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceSql.validateIdentifiers("workspace_snapshot_payload",emptySet()) }
    }

    @Test fun `table prefix never substitutes for a registered table whitelist`() {
        listOf("workspace_unregistered", "workspace_source;delete from tenant", "workspace_source join tenant", "tenant", "").forEach { table ->
            assertThrows(IllegalArgumentException::class.java) { WorkspaceSql.validateIdentifiers(table,setOf("tenant_id")) }
        }
    }

    @Test fun `a hostile value stays outside SQL text and is passed as a bound parameter`() {
        val statements=mutableListOf<Pair<String,List<Any?>>>()
        val database=Mockito.mock(DSLContext::class.java) { call ->
            val sql=call.arguments.firstOrNull() as? String
            if(sql!=null && call.method.name in setOf("fetchOne","execute")) {
                val bindings=(call.arguments.getOrNull(1) as? Array<*>)?.toList()?:call.arguments.drop(1)
                statements+=sql to bindings
            }
            when(call.method.name) { "fetchOne" -> null; "execute" -> 1; else -> Mockito.RETURNS_DEFAULTS.answer(call) }
        }
        val hostile="x'); DROP TABLE enterprise_member; --"
        WorkspaceSql.save(database,"workspace_snapshot_payload",linkedMapOf("tenant_id" to "tenant-a","sha256" to "a".repeat(64),"canonical_json" to hostile),listOf("tenant_id","sha256"))
        val insert=statements.single { it.first.startsWith("insert into") }
        assertFalse(insert.first.contains(hostile))
        assertEquals(3,insert.first.count { it=='?' })
        assertEquals(3,insert.second.size)
        assertTrue(insert.second.contains(hostile))
    }
}
