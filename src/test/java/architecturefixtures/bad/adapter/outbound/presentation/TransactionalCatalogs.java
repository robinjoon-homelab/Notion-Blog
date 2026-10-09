package architecturefixtures.bad.adapter.outbound.presentation;

import architecturefixtures.bad.config.ComposedTransaction;
import architecturefixtures.bad.config.TransactionSources;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ClassTransactionalCatalog {}

class MethodTransactionalCatalog {
    @Transactional
    public void execute() {}
}

@ComposedTransaction
class ComposedClassTransactionalCatalog {}

class ComposedMethodTransactionalCatalog {
    @ComposedTransaction
    public void execute() {}
}

class InheritedClassTransactionalCatalog extends TransactionSources.ClassTransactionalBase {}

class InheritedMethodTransactionalCatalog extends TransactionSources.MethodTransactionalBase {
    @Override
    public void execute() {}
}

class InterfaceClassTransactionalCatalog implements TransactionSources.ClassTransactionalContract {
    @Override
    public void execute() {}
}

class InterfaceMethodTransactionalCatalog implements TransactionSources.MethodTransactionalContract {
    @Override
    public void execute() {}
}
