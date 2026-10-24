package dev.bethge.shipir.auth.oidc;

import dev.bethge.shipir.auth.Role;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Group-to-role mapping, stored as {@code {"ADMIN":["group-a"],"OPERATOR":[...],"VIEWER":[...]}}. */
final class RoleMapping {

    private static final int MAX_GROUPS_PER_ROLE = 100;
    private static final int MAX_GROUP_LENGTH = 255;

    private RoleMapping() {
    }

    /** Validates the PUT value; null or absent is an empty mapping. */
    static Map<Role, List<String>> validate(JsonNode node) {
        Map<Role, List<String>> result = new EnumMap<>(Role.class);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return result;
        }
        if (!node.isObject()) {
            throw invalid("The role mapping must be an object of role to group names");
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            Role role;
            try {
                role = Role.valueOf(entry.getKey());
            } catch (IllegalArgumentException e) {
                throw invalid("Unknown role in the role mapping: " + entry.getKey());
            }
            JsonNode groups = entry.getValue();
            if (!groups.isArray() || groups.size() > MAX_GROUPS_PER_ROLE) {
                throw invalid("Each role maps to a list of at most " + MAX_GROUPS_PER_ROLE + " group names");
            }
            List<String> names = new java.util.ArrayList<>();
            for (JsonNode g : groups) {
                if (!g.isString() || g.asString().isBlank() || g.asString().length() > MAX_GROUP_LENGTH) {
                    throw invalid("Group names must be non-empty text of at most " + MAX_GROUP_LENGTH + " characters");
                }
                names.add(g.asString().trim());
            }
            result.put(role, List.copyOf(names));
        }
        return result;
    }

    static String toJson(JsonMapper mapper, Map<Role, List<String>> mapping) {
        ObjectNode node = mapper.createObjectNode();
        mapping.forEach((role, groups) -> {
            var array = node.putArray(role.name());
            groups.forEach(array::add);
        });
        return node.toString();
    }

    static Map<Role, List<String>> parse(JsonMapper mapper, String json) {
        return validate(mapper.readTree(json));
    }

    /** The highest role (ADMIN over OPERATOR over VIEWER) with a group the user has; empty = deny. */
    static Optional<Role> resolve(Map<Role, List<String>> mapping, Collection<String> userGroups) {
        for (Role role : Role.values()) {
            List<String> mapped = mapping.getOrDefault(role, List.of());
            if (mapped.stream().anyMatch(userGroups::contains)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }

    private static OidcException invalid(String message) {
        return new OidcException(org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_ROLE_MAPPING", message);
    }
}
