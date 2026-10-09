package architecturefixtures.bad.adapter.outbound.persistence

import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class ExplicitTransactionRepository {
    fun save(): Int = transaction { 1 }
}
