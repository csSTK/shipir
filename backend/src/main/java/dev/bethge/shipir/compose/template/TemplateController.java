package dev.bethge.shipir.compose.template;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/templates")
class TemplateController {

    private final TemplateCatalog catalog;

    TemplateController(TemplateCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    List<AppTemplate> list() {
        return catalog.all();
    }
}
