package architecturefixtures.bad.adapter.inbound.scheduling;

import architecturefixtures.bad.config.ComposedTransaction;
import architecturefixtures.bad.config.TransactionSources;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ClassTransactionalScheduler {}

class MethodTransactionalScheduler {
    @Transactional
    public void execute() {}
}

@ComposedTransaction
class ComposedClassTransactionalScheduler {}

class ComposedMethodTransactionalScheduler {
    @ComposedTransaction
    public void execute() {}
}

class InheritedClassTransactionalScheduler extends TransactionSources.ClassTransactionalBase {}

class InheritedMethodTransactionalScheduler extends TransactionSources.MethodTransactionalBase {
    @Override
    public void execute() {}
}

class InterfaceClassTransactionalScheduler implements TransactionSources.ClassTransactionalContract {
    @Override
    public void execute() {}
}

class InterfaceMethodTransactionalScheduler implements TransactionSources.MethodTransactionalContract {
    @Override
    public void execute() {}
}
