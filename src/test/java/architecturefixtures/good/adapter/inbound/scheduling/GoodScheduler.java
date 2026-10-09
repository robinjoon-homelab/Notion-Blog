package architecturefixtures.good.adapter.inbound.scheduling;

import architecturefixtures.good.application.port.input.Input;
import org.springframework.scheduling.annotation.Scheduled;

public class GoodScheduler {
    private final Input input;

    public GoodScheduler(Input input) {
        this.input = input;
    }

    @Scheduled(fixedDelay = 1000)
    public void execute() {
        input.get();
    }
}
