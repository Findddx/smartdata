package io.smartdata.foundation.compiler

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DomainPackCompilerTest {
    @TempDir
    lateinit var temp: Path

    private val compiler = DomainPackCompiler()

    @Test
    fun `compiles deterministic artifacts`() {
        val source = Path.of("../domain-packs/order-fulfillment/pack.yaml")
        val result = compiler.compile(source, temp.resolve("out"))

        assertThat(result.ir.packId).isEqualTo("order-fulfillment")
        assertThat(result.ir.actions.map { it.id }).containsExactly(
            "release_reservation",
            "request_stock_recount",
            "reserve_substitute_inventory",
        )
        assertThat(result.files).contains("domain-model.json", "mcp-tools.json", "generated/DomainContracts.kt")
        assertThat(Files.readString(temp.resolve("out/mcp-tools.json"))).contains("create_action_request__reserve_substitute_inventory")

        val secondOutput = temp.resolve("out-again")
        compiler.compile(source, secondOutput)
        assertThat(Files.readString(secondOutput.resolve("domain-model.json")))
            .isEqualTo(Files.readString(temp.resolve("out/domain-model.json")))
    }

    @Test
    fun `rejects write action without verifier and approval`() {
        val invalid = temp.resolve("invalid.yaml")
        Files.writeString(
            invalid,
            """
            id: invalid-pack
            version: 1.0.0
            name: Invalid
            objects:
              - id: order
                identityKeys: [id]
            actions:
              - id: unsafe_write
                target: order
                permission: missing
                approvalPermission: missing
                approvalRequired: false
                inputSchema:
                  type: object
                  additionalProperties: false
            """.trimIndent(),
        )

        assertThatThrownBy { compiler.compile(invalid, temp.resolve("bad")) }
            .isInstanceOf(DomainPackValidationException::class.java)
            .hasMessageContaining("/actions/0/approvalRequired")
            .hasMessageContaining("verifier")
    }

    @Test
    fun `rejects declarative workflow cycles`() {
        val source = temp.resolve("cycle.yaml")
        Files.writeString(source, minimalPack(version = "1.0.0", cycle = true))

        assertThatThrownBy { compiler.compile(source, temp.resolve("cycle-out")) }
            .isInstanceOf(DomainPackValidationException::class.java)
            .hasMessageContaining("declarative cycle")
    }

    @Test
    fun `requires a major version for breaking action schema changes`() {
        val v1 = temp.resolve("v1.yaml")
        Files.writeString(v1, minimalPack(version = "1.0.0"))
        compiler.compile(v1, temp.resolve("v1-out"))
        val previous = temp.resolve("v1-out/domain-model.json")

        val minor = temp.resolve("minor.yaml")
        Files.writeString(minor, minimalPack(version = "1.1.0", addRequiredField = true))
        assertThatThrownBy { compiler.compile(minor, temp.resolve("minor-out"), previous) }
            .isInstanceOf(DomainPackValidationException::class.java)
            .hasMessageContaining("new major version")

        val major = temp.resolve("major.yaml")
        Files.writeString(major, minimalPack(version = "2.0.0", addRequiredField = true))
        val result = compiler.compile(major, temp.resolve("major-out"), previous)
        assertThat(result.compatibility.compatible).isFalse()
        assertThat(result.compatibility.breakingChanges).anyMatch { it.contains("input schema changed") }
    }

    private fun minimalPack(
        version: String,
        addRequiredField: Boolean = false,
        cycle: Boolean = false,
    ): String {
        val extraRequired = if (addRequiredField) ", note" else ""
        val extraProperty = if (addRequiredField) "\n                    note: { type: string }" else ""
        val endKind = if (cycle) "DECISION" else "END"
        val endNext = if (cycle) "\n                    next: start" else ""
        val migration = if (version.startsWith("2.")) {
            "\n            migration:\n              fromVersion: 1.0.0\n              description: Add the required note field.\n              steps:\n                - Backfill note before activating version 2."
        } else ""
        return """
            id: minimal-pack
            version: $version
            name: Minimal
            objects:
              - id: order
                identityKeys: [order_id]
            permissions:
              - id: action.request
                roles: [order_specialist]
              - id: action.approve
                roles: [warehouse_approver]
            verifiers:
              - id: action_verified
                target: order
                mode: DETERMINISTIC
            actions:
              - id: do_action
                target: order
                risk: LOW
                permission: action.request
                approvalPermission: action.approve
                approvalRequired: true
                verifier: action_verified
                inputSchema:
                  type: object
                  additionalProperties: false
                  required: [orderId$extraRequired]
                  properties:
                    orderId: { type: string }$extraProperty
            workflows:
              - id: resolve_case
                start: start
                steps:
                  - id: start
                    kind: DECISION
                    next: finish
                  - id: finish
                    kind: $endKind$endNext
            views:
              - id: order_view
                objectType: order
                fields: [order_id]$migration
        """.trimIndent()
    }
}
