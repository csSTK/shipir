package dev.bethge.shipir.host;

import dev.bethge.shipir.host.dto.SearchResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Open to every authenticated role; results are filtered by the user's permissions in the service. */
@RestController
@RequestMapping("/api/search")
class SearchController {

    private final SearchService service;

    SearchController(SearchService service) {
        this.service = service;
    }

    @GetMapping
    SearchResponse search(@RequestParam(name = "q", defaultValue = "") String q) {
        return service.search(q);
    }
}
