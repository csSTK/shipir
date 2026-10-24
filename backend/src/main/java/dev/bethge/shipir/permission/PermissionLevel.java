package dev.bethge.shipir.permission;

/** VIEW reads a host or deployment; OPERATE also runs its lifecycle actions. */
public enum PermissionLevel {
    VIEW,
    OPERATE
}
