package dev.bethge.shipir.host.dto;

/** Create a network. {@code driver}: bridge (default), or overlay/macvlan/ipvlan (ADMIN only); {@code subnet} optional CIDR. */
public record CreateNetworkRequest(String name, String driver, Boolean internal, String subnet) {
}
