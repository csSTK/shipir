package dev.bethge.shipir.host;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.UUID;

/**
 * Log download of one container (all roles that may see the container, see {@link ContainerLogDownloadService}).
 * The body is written on the request thread, so validation errors still become normal JSON error responses and
 * no async timeout cuts a long download.
 */
@RestController
@RequestMapping("/api/hosts/{hostId}/containers/{containerId}/logs")
class ContainerLogDownloadController {

    private final ContainerLogDownloadService service;

    ContainerLogDownloadController(ContainerLogDownloadService service) {
        this.service = service;
    }

    @GetMapping("/download")
    void download(@PathVariable UUID hostId, @PathVariable String containerId,
                  @RequestParam(required = false) String since, @RequestParam(required = false) String tail,
                  @RequestParam(required = false) String timestamps, HttpServletResponse response) throws IOException {
        ContainerLogDownloadService.Download d = service.open(hostId, containerId, since, tail, timestamps);
        try {
            response.setContentType("text/plain;charset=UTF-8");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName()).build().toString());
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            d.body().writeTo(response.getOutputStream());
        } finally {
            d.release().run(); // idempotent; covers a failure before the body ran (open() already took the permit)
        }
    }
}
