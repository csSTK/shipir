package dev.bethge.shipir.host;

import dev.bethge.shipir.host.dto.ImageBuildRequest;
import dev.bethge.shipir.host.dto.ImageRunResponse;
import dev.bethge.shipir.host.dto.PushImageRequest;
import dev.bethge.shipir.host.dto.TagImageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Image build, tag and push. Roles (SecurityConfig): build ADMIN only, tag and push ADMIN/OPERATOR; the service adds the
 * host-level OPERATE check. Build and push run in the background; output is streamed on {@code /topic/deployments/{runId}}.
 */
@RestController
@RequestMapping("/api/hosts/{id}/images")
class ImageBuildController {

    private final ImageBuildService service;

    ImageBuildController(ImageBuildService service) {
        this.service = service;
    }

    /**
     * Multipart: part {@code context} (tar or tar.gz) or Git source {@code gitUrl} + {@code gitRef} (+ optional
     * {@code gitCredentialId}); fields {@code tag}, {@code dockerfile} (default {@code Dockerfile}), repeatable
     * {@code buildArg} ({@code KEY=VALUE}), {@code noCache}. Text fields are read as raw parameter values so a comma in a
     * build argument is not split.
     */
    @PostMapping(value = "/build", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    ImageRunResponse build(@PathVariable UUID id,
                           @RequestParam(value = "context", required = false) MultipartFile context,
                           @RequestParam MultiValueMap<String, String> params) {
        return service.build(id, new ImageBuildRequest(context, first(params, "tag"), first(params, "dockerfile"),
                params.getOrDefault("buildArg", List.of()), Boolean.parseBoolean(first(params, "noCache")),
                first(params, "gitUrl"), first(params, "gitRef"), uuidOrNull(first(params, "gitCredentialId"))));
    }

    @PostMapping("/{imageId}/tag")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void tag(@PathVariable UUID id, @PathVariable String imageId, @RequestBody TagImageRequest request) {
        service.tag(id, imageId, request);
    }

    @PostMapping("/push")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ImageRunResponse push(@PathVariable UUID id, @RequestBody PushImageRequest request) {
        return service.push(id, request);
    }

    private static String first(MultiValueMap<String, String> params, String name) {
        return params.getFirst(name);
    }

    private static UUID uuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new HostException(HttpStatus.BAD_REQUEST, "INVALID_GIT_CREDENTIAL", "Invalid Git credential id");
        }
    }
}
