package architecturefixtures.bad.adapter.outbound.notion;

import architecturefixtures.bad.config.ComposedTransaction;
import architecturefixtures.bad.config.TransactionSources;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ClassTransactionalSource {}

class MethodTransactionalSource {
    @Transactional
    public void execute() {}
}

@ComposedTransaction
class ComposedClassTransactionalSource {}

class ComposedMethodTransactionalSource {
    @ComposedTransaction
    public void execute() {}
}

class InheritedClassTransactionalSource extends TransactionSources.ClassTransactionalBase {}

class InheritedMethodTransactionalSource extends TransactionSources.MethodTransactionalBase {
    @Override
    public void execute() {}
}

class InterfaceClassTransactionalSource implements TransactionSources.ClassTransactionalContract {
    @Override
    public void execute() {}
}

class InterfaceMethodTransactionalSource implements TransactionSources.MethodTransactionalContract {
    @Override
    public void execute() {}
}
