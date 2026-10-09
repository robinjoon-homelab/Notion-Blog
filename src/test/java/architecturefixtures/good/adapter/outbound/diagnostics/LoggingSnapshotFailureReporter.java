package architecturefixtures.good.adapter.outbound.diagnostics;

import architecturefixtures.good.application.port.output.diagnostics.SnapshotFailureReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingSnapshotFailureReporter implements SnapshotFailureReporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingSnapshotFailureReporter.class);

    @Override
    public void report(RuntimeException failure) {
        LOGGER.warn("Snapshot failure type: {}", failure.getClass().getName());
    }
}
