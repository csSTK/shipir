package dev.bethge.shipir.backup;

import dev.bethge.shipir.backup.dto.BackupRequest;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** ADMIN only, enforced in {@code SecurityConfig}. POST (not GET) so the passphrase stays out of URLs and logs. */
@RestController
@RequestMapping("/api/backup")
class BackupController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final BackupService service;
    private final Clock clock;

    BackupController(BackupService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping
    ResponseEntity<StreamingResponseBody> export(@Valid @RequestBody BackupRequest request) {
        StreamingResponseBody body = service.export(request.passphrase().toCharArray());
        String filename = "shipir-backup-" + STAMP.format(clock.instant()) + ".tar.gz.enc";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
