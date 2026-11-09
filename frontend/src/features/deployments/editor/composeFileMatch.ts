/**
 * Models the Compose JSON schema applies to (monaco-yaml `fileMatch` globs). Not `*`: other YAML
 * models would get false Compose errors.
 */
export const COMPOSE_FILE_MATCH = ['**/compose.yaml', '**/compose.yml', '**/docker-compose.yaml', '**/docker-compose.yml']
