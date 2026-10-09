package architecturefixtures.bad.adapter.outbound.diagnostics;

import architecturefixtures.bad.config.ComposedTransaction;
import architecturefixtures.bad.config.TransactionSources;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ClassTransactionalReporter {}

class MethodTransactionalReporter {
    @Transactional
    public void execute() {}
}

@ComposedTransaction
class ComposedClassTransactionalReporter {}

class ComposedMethodTransactionalReporter {
    @ComposedTransaction
    public void execute() {}
}

class InheritedClassTransactionalReporter extends TransactionSources.ClassTransactionalBase {}

class InheritedMethodTransactionalReporter extends TransactionSources.MethodTransactionalBase {
    @Override
    public void execute() {}
}

class InterfaceClassTransactionalReporter implements TransactionSources.ClassTransactionalContract {
    @Override
    public void execute() {}
}

class InterfaceMethodTransactionalReporter implements TransactionSources.MethodTransactionalContract {
    @Override
    public void execute() {}
}
