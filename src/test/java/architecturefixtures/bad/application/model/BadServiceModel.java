package architecturefixtures.bad.application.model;

import architecturefixtures.bad.application.service.BadService;

class BadServiceModel {
    private final BadService service;

    BadServiceModel(BadService service) {
        this.service = service;
    }
}
