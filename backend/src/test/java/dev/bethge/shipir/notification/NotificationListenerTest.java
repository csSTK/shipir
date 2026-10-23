package dev.bethge.shipir.notification;

import dev.bethge.shipir.host.HostStatusChangedEvent;
import dev.bethge.shipir.host.dto.HostOverviewResponse.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationListenerTest extends NotificationTestBase {

    @Autowired
    ApplicationEventPublisher publisher;

    private void publish(Status prev, Status cur, UUID hostId) {
        publisher.publishEvent(new HostStatusChangedEvent(hostId, "prod", prev, cur,
                cur == Status.DOWN ? "UNREACHABLE" : null, Instant.now()));
    }

    @Test
    void createsOneNotificationPerUserDefaultingToEnabled() {
        publish(Status.UP, Status.DOWN, null);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT n.type, n.host_name, n.message, n.read_at FROM notifications n");
        assertThat(rows).hasSize((int) jdbc.queryForObject("SELECT count(*) FROM users", Long.class).longValue());
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("type")).isEqualTo("HOST_DOWN");
            assertThat(r.get("host_name")).isEqualTo("prod");
            assertThat((String) r.get("message")).contains("prod");
            assertThat(r.get("read_at")).isNull();
        });
    }

    @Test
    void skipsUsersWhoDisabledThatType() {
        jdbc.update("INSERT INTO notification_preferences (user_id, type, in_app_enabled) VALUES (?, 'HOST_UP', false)",
                userId("viewer"));
        jdbc.update("INSERT INTO notification_preferences (user_id, type, in_app_enabled) VALUES (?, 'HOST_DOWN', false)",
                userId("operator"));
        publish(Status.DOWN, Status.UP, null);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ?", Long.class, userId("viewer"))).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'HOST_UP'", Long.class,
                userId("operator"))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ?", Long.class, userId("admin"))).isEqualTo(1);
    }
}
