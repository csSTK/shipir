package dev.bethge.shipir.streaming;

import java.util.List;

/**
 * Where a user may hold a console on one host: every container ({@code hostOperable}) or only containers of the
 * listed Compose projects (managed deployments the user may operate).
 */
record ExecScope(boolean hostOperable, List<String> operableProjects) {

    static final ExecScope NONE = new ExecScope(false, List.of());
}
