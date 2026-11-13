package dev.bethge.shipir.backup;

import dev.bethge.shipir.auth.stepup.StepUpService;
import dev.bethge.shipir.backup.dto.BackupRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * ADMIN only, enforced in {@code SecurityConfig}. POST (not GET) so the passphrase stays out of URLs and logs. The
 * export also needs a step-up ({@link StepUpService}): a stolen session alone cannot take the whole database.
 */
@RestController
@RequestMapping("/api/backup")
class BackupController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final BackupService service;
    private final Clock clock;
    private final StepUpService stepUp;

    BackupController(BackupService service, Clock clock, StepUpService stepUp) {
        this.service = service;
        this.clock = clock;
        this.stepUp = stepUp;
    }

    @PostMapping
    ResponseEntity<StreamingResponseBody> export(@Valid @RequestBody BackupRequest request, Authentication authentication,
                                                  HttpServletRequest http) {
        stepUp.require(authentication, http, request.stepUp());
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
