package architecturefixtures.good.application.model;

import java.net.URI;

public class ResourceLocation {
    private final URI location;

    public ResourceLocation(URI location) {
        this.location = location;
    }

    public URI location() {
        return location;
    }
}
