package dev.bethge.shipir.ai.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Validates tool arguments against the small JSON Schema subset the tools use: object with {@code properties}
 * (type string/integer/boolean/array of strings, {@code format: uuid}, {@code enum}, {@code minimum}, {@code maximum}),
 * {@code required}, and no unknown properties.
 */
final class ArgumentValidator {

    private ArgumentValidator() {
    }

    /** @return human-readable violations, empty when valid */
    @SuppressWarnings("unchecked")
    static List<String> validate(Map<String, Object> schema, Map<String, Object> args) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        List<String> required = (List<String>) schema.getOrDefault("required", List.of());
        for (String name : required) {
            if (args.get(name) == null) {
                errors.add("missing required argument '" + name + "'");
            }
        }
        for (Map.Entry<String, Object> e : args.entrySet()) {
            Map<String, Object> prop = (Map<String, Object>) props.get(e.getKey());
            if (prop == null) {
                errors.add("unknown argument '" + e.getKey() + "'");
            } else if (e.getValue() != null) {
                checkValue(e.getKey(), prop, e.getValue(), errors);
            }
        }
        return errors;
    }

    private static void checkValue(String name, Map<String, Object> prop, Object value, List<String> errors) {
        switch (String.valueOf(prop.get("type"))) {
            case "string" -> {
                if (!(value instanceof String s)) {
                    errors.add("'" + name + "' must be a string");
                    return;
                }
                if ("uuid".equals(prop.get("format")) && !isUuid(s)) {
                    errors.add("'" + name + "' must be a UUID");
                }
                if (prop.get("enum") instanceof List<?> allowed && !allowed.contains(s)) {
                    errors.add("'" + name + "' must be one of " + allowed);
                }
            }
            case "integer" -> {
                if (!(value instanceof Long || value instanceof Integer)) {
                    errors.add("'" + name + "' must be an integer");
                    return;
                }
                long n = ((Number) value).longValue();
                if (prop.get("minimum") instanceof Number min && n < min.longValue()) {
                    errors.add("'" + name + "' must be >= " + min);
                }
                if (prop.get("maximum") instanceof Number max && n > max.longValue()) {
                    errors.add("'" + name + "' must be <= " + max);
                }
            }
            case "array" -> {
                if (!(value instanceof List<?> items) || !items.stream().allMatch(String.class::isInstance)) {
                    errors.add("'" + name + "' must be a list of strings");
                    return;
                }
                if (prop.get("maxItems") instanceof Number max && items.size() > max.longValue()) {
                    errors.add("'" + name + "' must have at most " + max + " items");
                }
            }
            case "boolean" -> {
                if (!(value instanceof Boolean)) {
                    errors.add("'" + name + "' must be a boolean");
                }
            }
            default -> errors.add("'" + name + "' has an unsupported schema type");
        }
    }

    private static boolean isUuid(String s) {
        try {
            return UUID.fromString(s).toString().equalsIgnoreCase(s);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
