package dev.bethge.shipir.host;

import dev.bethge.shipir.host.dto.StatsHistoryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/hosts/{id}/stats/history")
class StatsHistoryController {

    private final StatsHistoryService service;

    StatsHistoryController(StatsHistoryService service) {
        this.service = service;
    }

    @GetMapping
    StatsHistoryResponse history(@PathVariable UUID id, @RequestParam(required = false) String container,
                                 @RequestParam(defaultValue = "1h") String range) {
        return service.history(id, container, range);
    }
}
