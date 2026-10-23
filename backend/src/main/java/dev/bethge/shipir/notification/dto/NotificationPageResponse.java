package dev.bethge.shipir.notification.dto;

import java.util.List;

public record NotificationPageResponse(List<NotificationResponse> content, long totalElements) {
}
