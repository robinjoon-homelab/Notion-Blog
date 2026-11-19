package xyz.robinjoon.notionblog.blog.adapter.outbound.persistence.exposed

import org.jetbrains.exposed.v1.exceptions.ExposedSQLException

internal inline fun postDataWrite(write: () -> Unit) {
    try {
        write()
    } catch (exception: ExposedSQLException) {
        // SQL data exceptions repeat with the same input. Let infrastructure errors reach the worker's retry boundary.
        if (exception.sqlState.startsWith("22")) {
            throw IllegalArgumentException("post data does not meet storage requirements", exception)
        }
        throw exception
    }
}
