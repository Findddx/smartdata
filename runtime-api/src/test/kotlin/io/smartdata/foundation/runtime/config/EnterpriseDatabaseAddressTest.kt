package io.smartdata.foundation.runtime.config

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EnterpriseDatabaseAddressTest {
    private val configuration = EnterpriseWorkspaceConfiguration()

    @Test fun `loopback tests and verified remote database addresses are supported`() {
        listOf("jdbc:postgresql://127.0.0.1:5432/data", "jdbc:postgresql://localhost/data",
            "jdbc:postgresql://[::1]:5432/data", "jdbc:postgresql://database.example.invalid/data?sslmode=verify-full").forEach {
            assertDoesNotThrow { configuration.validateDatabaseUrl(it) }
        }
    }

    @Test fun `remote plaintext and URL credentials cannot weaken enterprise configuration`() {
        listOf("jdbc:postgresql://database.example.invalid/data", "jdbc:postgresql://database.example.invalid/data?sslmode=require",
            "jdbc:postgresql://localhost/data?user=owner", "jdbc:postgresql://localhost/data?password=placeholder",
            "jdbc:postgresql://localhost/data?p%61ssword=placeholder",
            "jdbc:postgresql://database.example.invalid/data?sslmode=verify-full&sslmode=disable", "jdbc:postgresql://user@localhost/data",
            "jdbc:postgresql:data", "jdbc:h2:mem:test").forEach {
            assertThrows(IllegalArgumentException::class.java) { configuration.validateDatabaseUrl(it) }
        }
    }
}
