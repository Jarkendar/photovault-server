package dev.jskrzypczak.photovault.server.users

import dev.jskrzypczak.photovault.server.db.tables.Users
import dev.jskrzypczak.photovault.server.dto.UserDto
import dev.jskrzypczak.photovault.server.dto.UserListResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Read-only service for the user list endpoint (`GET /v1/users`). */
class UserService {

    /** Returns all users ordered by creation time ascending (admin first). */
    fun list(): UserListResponse = transaction {
        Users.selectAll()
            .orderBy(Users.createdAt, SortOrder.ASC)
            .map { it.toUserDto() }
            .let { UserListResponse(items = it) }
    }

    private fun ResultRow.toUserDto() = UserDto(
        id = this[Users.id],
        username = this[Users.username],
        displayName = this[Users.displayName],
        createdAt = this[Users.createdAt].toString(),
    )
}
