package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.dto.ContainerDetailsDto;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ImageDto;
import dev.bethge.shipir.docker.dto.NetworkDto;
import dev.bethge.shipir.host.dto.HostDeploymentResponse;
import dev.bethge.shipir.host.dto.PrunePreviewResponse;
import dev.bethge.shipir.host.dto.PruneRequest;
import dev.bethge.shipir.host.dto.PruneResponse;
import dev.bethge.shipir.host.dto.PullImageRequest;
import dev.bethge.shipir.host.dto.PullImageResponse;
import dev.bethge.shipir.docker.dto.VolumeDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Live resources of one environment: reads for every role, deletes and container actions for ADMIN/OPERATOR (SecurityConfig). */
@RestController
@RequestMapping("/api/hosts/{id}")
class HostResourcesController {

    private final HostResourcesService service;

    HostResourcesController(HostResourcesService service) {
        this.service = service;
    }

    @GetMapping("/deployments")
    List<HostDeploymentResponse> deployments(@PathVariable UUID id) {
        return service.deployments(id);
    }

    @GetMapping("/containers")
    List<ContainerDto> containers(@PathVariable UUID id) {
        return service.containers(id);
    }

    @GetMapping("/containers/{containerId}")
    ContainerDetailsDto container(@PathVariable UUID id, @PathVariable String containerId) {
        return service.container(id, containerId);
    }

    @GetMapping("/images")
    List<ImageDto> images(@PathVariable UUID id) {
        return service.images(id);
    }

    @GetMapping("/networks")
    List<NetworkDto> networks(@PathVariable UUID id) {
        return service.networks(id);
    }

    @GetMapping("/volumes")
    List<VolumeDto> volumes(@PathVariable UUID id) {
        return service.volumes(id);
    }

    @DeleteMapping("/containers/{containerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeContainer(@PathVariable UUID id, @PathVariable String containerId,
                         @RequestParam(defaultValue = "false") boolean force) {
        service.removeContainer(id, containerId, force);
    }

    /** Starts an image pull; progress is streamed on {@code /topic/deployments/{runId}}. */
    @PostMapping("/images/pull")
    @ResponseStatus(HttpStatus.ACCEPTED)
    PullImageResponse pullImage(@PathVariable UUID id, @RequestBody PullImageRequest request) {
        return service.pullImage(id, request);
    }

    @DeleteMapping("/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeImage(@PathVariable UUID id, @PathVariable String imageId) {
        service.removeImage(id, imageId);
    }

    @DeleteMapping("/volumes/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeVolume(@PathVariable UUID id, @PathVariable String name) {
        service.removeVolume(id, name);
    }

    @DeleteMapping("/networks/{networkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeNetwork(@PathVariable UUID id, @PathVariable String networkId) {
        service.removeNetwork(id, networkId);
    }

    @PostMapping("/containers/{containerId}/start")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void startContainer(@PathVariable UUID id, @PathVariable String containerId) {
        service.startContainer(id, containerId);
    }

    @PostMapping("/containers/{containerId}/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void stopContainer(@PathVariable UUID id, @PathVariable String containerId,
                       @RequestParam(required = false) String timeout) {
        service.stopContainer(id, containerId, timeout);
    }

    @PostMapping("/containers/{containerId}/restart")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void restartContainer(@PathVariable UUID id, @PathVariable String containerId,
                          @RequestParam(required = false) String timeout) {
        service.restartContainer(id, containerId, timeout);
    }

    @PostMapping("/containers/{containerId}/pause")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void pauseContainer(@PathVariable UUID id, @PathVariable String containerId) {
        service.pauseContainer(id, containerId);
    }

    @PostMapping("/containers/{containerId}/unpause")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unpauseContainer(@PathVariable UUID id, @PathVariable String containerId) {
        service.unpauseContainer(id, containerId);
    }

    @PostMapping("/containers/{containerId}/kill")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void killContainer(@PathVariable UUID id, @PathVariable String containerId) {
        service.killContainer(id, containerId);
    }

    @GetMapping("/prune/preview")
    PrunePreviewResponse prunePreview(@PathVariable UUID id,
                                      @RequestParam(required = false) PruneRequest.ImageScope images,
                                      @RequestParam(defaultValue = "false") boolean includeManagedVolumes) {
        return service.prunePreview(id, images, includeManagedVolumes);
    }

    @PostMapping("/prune")
    PruneResponse prune(@PathVariable UUID id, @RequestBody PruneRequest request) {
        return service.prune(id, request);
    }
}
