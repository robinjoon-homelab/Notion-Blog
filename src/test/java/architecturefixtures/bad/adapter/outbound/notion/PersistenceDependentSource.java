package architecturefixtures.bad.adapter.outbound.notion;

import architecturefixtures.bad.adapter.outbound.persistence.BadPersistence;

class PersistenceDependentSource {
    private final BadPersistence persistence;

    PersistenceDependentSource(BadPersistence persistence) {
        this.persistence = persistence;
    }
}
