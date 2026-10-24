package dev.bethge.shipir.host.dto;

/**
 * What to prune. {@code images} null = leave images alone; {@code DANGLING} = untagged unused images,
 * {@code UNUSED} = all images no container uses. Volumes of managed deployments are only included with
 * {@code includeManagedVolumes}.
 */
public record PruneRequest(Boolean containers, ImageScope images, Boolean volumes, Boolean networks,
                           Boolean includeManagedVolumes) {

    public enum ImageScope { DANGLING, UNUSED }

    public boolean pruneContainers() {
        return Boolean.TRUE.equals(containers);
    }

    public boolean pruneVolumes() {
        return Boolean.TRUE.equals(volumes);
    }

    public boolean pruneNetworks() {
        return Boolean.TRUE.equals(networks);
    }

    public boolean withManagedVolumes() {
        return Boolean.TRUE.equals(includeManagedVolumes);
    }

    public boolean nothingSelected() {
        return !pruneContainers() && images == null && !pruneVolumes() && !pruneNetworks();
    }
}
