package architecturefixtures.bad.adapter.outbound.persistence;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;

class TransactionTemplateRepository {
    private final TransactionOperations transactions;

    TransactionTemplateRepository(TransactionOperations transactions) {
        this.transactions = transactions;
    }

    public Integer save() {
        return transactions.execute(status -> 1);
    }
}

interface RepositoryTransactionManager extends PlatformTransactionManager {}

class TransactionManagerRepository {
    private final RepositoryTransactionManager transactions;

    TransactionManagerRepository(RepositoryTransactionManager transactions) {
        this.transactions = transactions;
    }
}
