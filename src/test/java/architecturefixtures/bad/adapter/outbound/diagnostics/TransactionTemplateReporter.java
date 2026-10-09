package architecturefixtures.bad.adapter.outbound.diagnostics;

import org.springframework.transaction.support.TransactionTemplate;

class TransactionTemplateReporter {
    private final TransactionTemplate transactions;

    TransactionTemplateReporter(TransactionTemplate transactions) {
        this.transactions = transactions;
    }

    public Integer report() {
        return transactions.execute(status -> 1);
    }
}
