package dev.bethge.shipir.registry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface RegistryCredentialRepository extends JpaRepository<RegistryCredential, UUID> {

    boolean existsByName(String name);

    boolean existsByRegistryHost(String registryHost);
}
