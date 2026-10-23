# Backlog

Features Portainer has that Shipir does not, and that are not planned as beads yet. Turn an item into beads with `/text-to-task` when it's picked up.

## Containers and images

- **Create single containers:** a form to run one container (image, ports, env, volumes, restart policy) without Compose.
- **Container inspect:** details view with env, mounts, labels, networks and restart policy.
- **Image actions:** pull, tag and build images; prune unused images.

## Networks and volumes

- **Create networks and volumes:** today they can only be listed and deleted.

## Deployments

- **Pull & update for FILE deployments:** re-pull images and recreate (`docker compose pull` + `up`). Git deployments can already be redeployed.
- **App templates:** one-click deployments from a template catalog.

## Registries

- **Private registries:** stored registry credentials (encrypted via `SecretCipher`) used for pulls and deploys.

## Not planned (out of scope)

Swarm and Kubernetes, edge/remote agent (SaaS control plane), OAuth/LDAP, teams, Docker secrets/configs.
