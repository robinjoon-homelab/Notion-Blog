package architecturefixtures.bad.adapter.outbound.diagnostics;

import architecturefixtures.bad.adapter.outbound.persistence.BadPersistence;

class PersistenceDependentReporter {
    private final BadPersistence persistence;

    PersistenceDependentReporter(BadPersistence persistence) {
        this.persistence = persistence;
    }
}
