package architecturefixtures.good.application.port.output.diagnostics;

public interface SnapshotFailureReporter {
    void report(RuntimeException failure);
}
