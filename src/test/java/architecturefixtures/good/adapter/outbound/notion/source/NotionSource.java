package architecturefixtures.good.adapter.outbound.notion.source;

import architecturefixtures.good.adapter.outbound.notion.client.NotionClient;
import architecturefixtures.good.application.port.output.source.ContentSource;
import architecturefixtures.good.domain.Value;

public class NotionSource implements ContentSource {
    private final NotionClient client;

    public NotionSource(NotionClient client) {
        this.client = client;
    }

    @Override
    public Value fetch() {
        return client.fetch();
    }
}
