-- At most one LOCAL environment (the service check alone is racy).
CREATE UNIQUE INDEX docker_hosts_single_local_idx ON docker_hosts (connection_type) WHERE connection_type = 'LOCAL';
