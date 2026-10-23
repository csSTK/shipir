package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ImageDto;
import dev.bethge.shipir.docker.dto.NetworkDto;
import dev.bethge.shipir.host.dto.HostDeploymentResponse;
import dev.bethge.shipir.docker.dto.VolumeDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Live resources of one environment: reads for every role, deletes for ADMIN/OPERATOR (SecurityConfig). */
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
}
