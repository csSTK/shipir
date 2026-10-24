package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.dto.ContainerFileEntry;
import dev.bethge.shipir.host.dto.ContainerFileDownload;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/** Files inside a container. ADMIN only (matcher in SecurityConfig), plus the host-level permission check in the service. */
@RestController
@RequestMapping("/api/hosts/{hostId}/containers/{containerId}/files")
class ContainerFilesController {

    private final ContainerFilesService service;

    ContainerFilesController(ContainerFilesService service) {
        this.service = service;
    }

    @GetMapping
    List<ContainerFileEntry> list(@PathVariable UUID hostId, @PathVariable String containerId,
                                  @RequestParam String path) {
        return service.list(hostId, containerId, path);
    }

    @GetMapping("/download")
    ResponseEntity<InputStreamResource> download(@PathVariable UUID hostId, @PathVariable String containerId,
                                                 @RequestParam String path) {
        ContainerFileDownload d = service.download(hostId, containerId, path);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(d.fileName(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff");
        if (d.size() >= 0) {
            response.contentLength(d.size());
        }
        return response.body(new InputStreamResource(d.content()));
    }

    /** Multipart part {@code file} is extracted into the existing directory {@code path}. */
    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void upload(@PathVariable UUID hostId, @PathVariable String containerId, @RequestParam String path,
                @RequestParam("file") MultipartFile file) {
        service.upload(hostId, containerId, path, file);
    }
}
