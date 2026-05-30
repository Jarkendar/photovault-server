package dev.jskrzypczak.photovault.server

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.jskrzypczak.photovault.server.db.initDatabase
import dev.jskrzypczak.photovault.server.db.seedLabels
import dev.jskrzypczak.photovault.server.db.tables.Labels
import dev.jskrzypczak.photovault.server.db.tables.Photos
import dev.jskrzypczak.photovault.server.db.tables.RefreshTokens
import dev.jskrzypczak.photovault.server.db.tables.Tags
import dev.jskrzypczak.photovault.server.db.tables.Uploads
import dev.jskrzypczak.photovault.server.db.tables.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseTest {

    companion object {
        @JvmStatic
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    @BeforeAll
    fun setup() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username,
            password = postgres.password,
        )
        initDatabase()
    }

    @Test
    fun `schema creation succeeds and tables are queryable`() {
        transaction {
            assertEquals(0L, Photos.selectAll().count())
            assertEquals(0L, Tags.selectAll().count())
            assertEquals(0L, Uploads.selectAll().count())
            assertEquals(0L, RefreshTokens.selectAll().count())
        }
    }

    @Test
    fun `exactly 6 labels are seeded`() {
        transaction {
            assertEquals(6L, Labels.selectAll().count())
        }
    }

    @Test
    fun `label seed is idempotent`() {
        transaction {
            seedLabels()
            seedLabels()
            assertEquals(6L, Labels.selectAll().count())
        }
    }

    @Test
    fun `admin user is seeded with bcrypt-hashed password`() {
        transaction {
            val row = Users.selectAll().where { Users.username eq "admin" }.firstOrNull()
            assertNotNull(row, "admin user must exist after seed")
            assertTrue(BCrypt.verifyer().verify("password123".toCharArray(), row[Users.passwordHash]).verified)
        }
    }
}
