package architecturefixtures.good.adapter.outbound.presentation;

import architecturefixtures.good.application.port.output.presentation.AssetCatalog;
import java.net.URI;

public class ClasspathCatalog implements AssetCatalog {
    @Override
    public URI resolve() {
        return URI.create("/assets/theme.css");
    }
}
