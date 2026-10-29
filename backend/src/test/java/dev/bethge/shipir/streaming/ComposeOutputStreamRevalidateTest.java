package dev.bethge.shipir.streaming;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageChannel;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Revalidation of deploy-output subscriptions must only evaluate the predicate for runs the user is on. */
class ComposeOutputStreamRevalidateTest {

    private final ComposeOutputStreamService service = new ComposeOutputStreamService(mock(MessageChannel.class),
            JsonMapper.builder().build(), new StreamProperties(100, Duration.ofMinutes(10), 10), Clock.systemUTC());

    @Test
    void revalidateAsksOnlyForRunsTheUserIsSubscribedTo() {
        service.registerRun("mine", UUID.randomUUID(), UUID.randomUUID());
        service.registerRun("theirs", UUID.randomUUID(), UUID.randomUUID());
        service.subscribe("mine", "alice", "s1", "a");
        service.subscribe("theirs", "bob", "s2", "b");
        List<Optional<ComposeOutputStreamService.RunTarget>> asked = new ArrayList<>();

        service.revalidate("alice", target -> {
            asked.add(target);
            return false;
        });

        assertThat(asked).containsExactly(service.targetOf("mine"));
        assertThat(service.subscriberCount("mine")).isZero();
        assertThat(service.subscriberCount("theirs")).isEqualTo(1);
    }
}
