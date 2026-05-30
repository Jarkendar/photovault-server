package dev.jskrzypczak.photovault.server.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object RefreshTokens : Table("refresh_tokens") {
    val jti = varchar("jti", 128)
    val userId = varchar("user_id", 64).references(Users.id, onDelete = ReferenceOption.CASCADE)
    val expiresAt = timestamp("expires_at")
    val revoked = bool("revoked").default(false)

    override val primaryKey = PrimaryKey(jti)
}
