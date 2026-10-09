package architecturefixtures.good.application.port.output.source;

import architecturefixtures.good.domain.Value;

public interface ContentSource {
    Value fetch();
}
