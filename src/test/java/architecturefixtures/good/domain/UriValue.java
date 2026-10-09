package architecturefixtures.good.domain;

import java.net.URI;

public class UriValue {
    private final URI value;

    public UriValue(String value) {
        this.value = URI.create(value).normalize();
    }

    public URI value() {
        return value;
    }
}
