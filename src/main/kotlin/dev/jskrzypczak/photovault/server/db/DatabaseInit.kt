package dev.jskrzypczak.photovault.server.db

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.jskrzypczak.photovault.server.db.tables.Categories
import dev.jskrzypczak.photovault.server.db.tables.Labels
import dev.jskrzypczak.photovault.server.db.tables.PhotoCategories
import dev.jskrzypczak.photovault.server.db.tables.PhotoLabels
import dev.jskrzypczak.photovault.server.db.tables.PhotoTags
import dev.jskrzypczak.photovault.server.db.tables.Photos
import dev.jskrzypczak.photovault.server.db.tables.RefreshTokens
import dev.jskrzypczak.photovault.server.db.tables.Tags
import dev.jskrzypczak.photovault.server.db.tables.Uploads
import dev.jskrzypczak.photovault.server.db.tables.Users
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory
import java.time.Instant

private val log = LoggerFactory.getLogger("DatabaseInit")

private val allTables = arrayOf(
    Users, Photos, Tags, Categories, Labels,
    PhotoTags, PhotoCategories, PhotoLabels,
    Uploads, RefreshTokens
)

fun initDatabase() {
    transaction {
        @Suppress("DEPRECATION")
        SchemaUtils.createMissingTablesAndColumns(*allTables)
        exec("CREATE INDEX IF NOT EXISTS idx_photos_cursor ON photos (uploaded_at DESC, id DESC)")
        log.info("Database schema up to date")
        seedLabels()
        seedAdminUser()
    }
}

fun seedLabels() {
    val labels = listOf(
        Triple("label-red", "red", "#E53935"),
        Triple("label-orange", "orange", "#FB8C00"),
        Triple("label-yellow", "yellow", "#FDD835"),
        Triple("label-green", "green", "#43A047"),
        Triple("label-blue", "blue", "#1E88E5"),
        Triple("label-purple", "purple", "#8E24AA"),
    )
    for ((id, name, colorHex) in labels) {
        Labels.upsert(Labels.id) {
            it[Labels.id] = id
            it[Labels.name] = name
            it[Labels.colorHex] = colorHex
        }
    }
    log.info("Seeded 6 labels")
}

fun seedAdminUser() {
    if (Users.selectAll().count() == 0L) {
        val hash = BCrypt.withDefaults().hashToString(12, "password123".toCharArray())
        Users.insert {
            it[id] = "user-admin"
            it[username] = "admin"
            it[displayName] = "Admin"
            it[passwordHash] = hash
            it[createdAt] = Instant.now()
        }
        log.info("Seeded admin user")
    }
}
