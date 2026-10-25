package dev.bethge.shipir.host;

import dev.bethge.shipir.host.dto.VolumeBackupDownload;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Volume backup download and restore upload. ADMIN/OPERATOR (matchers in SecurityConfig) plus host-level OPERATE in the service. */
@RestController
@RequestMapping("/api/hosts/{hostId}/volumes/{volume}")
class VolumeBackupController {

    private static final MediaType TAR = MediaType.parseMediaType("application/x-tar");

    private final VolumeBackupService service;

    VolumeBackupController(VolumeBackupService service) {
        this.service = service;
    }

    @GetMapping("/backup")
    ResponseEntity<InputStreamResource> backup(@PathVariable UUID hostId, @PathVariable String volume) {
        VolumeBackupDownload download = service.backup(hostId, volume);
        return ResponseEntity.ok()
                .contentType(TAR)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(download.content()));
    }

    /** Multipart part {@code file} (a tar) is extracted into the existing volume; {@code confirm=true} allows a volume in use. */
    @PostMapping(value = "/restore", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void restore(@PathVariable UUID hostId, @PathVariable String volume, @RequestParam("file") MultipartFile file,
                 @RequestParam(defaultValue = "false") boolean confirm) {
        service.restore(hostId, volume, file, confirm);
    }
}
