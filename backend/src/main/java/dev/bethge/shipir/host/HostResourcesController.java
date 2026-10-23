package dev.bethge.shipir.host;

import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ImageDto;
import dev.bethge.shipir.docker.dto.NetworkDto;
import dev.bethge.shipir.host.dto.HostDeploymentResponse;
import dev.bethge.shipir.docker.dto.VolumeDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Live, read-only resources of one environment; open to every authenticated role. */
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
}
