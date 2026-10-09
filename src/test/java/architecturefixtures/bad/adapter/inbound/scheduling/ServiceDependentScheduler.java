package architecturefixtures.bad.adapter.inbound.scheduling;

import architecturefixtures.bad.application.service.BadService;

class ServiceDependentScheduler {
    private final BadService service;

    ServiceDependentScheduler(BadService service) {
        this.service = service;
    }
}
