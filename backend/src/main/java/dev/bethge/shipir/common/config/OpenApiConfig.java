package dev.bethge.shipir.common.config;

import dev.bethge.shipir.common.exception.ErrorResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata: session cookie auth plus the CSRF header every non-GET call needs.
 * Only active while {@code shipir.openapi.enabled} is true.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "shipir.openapi.enabled", havingValue = "true", matchIfMissing = true)
class OpenApiConfig {

    static final String SESSION_SCHEME = "sessionCookie";
    static final String CSRF_SCHEME = "csrfHeader";

    @Bean
    OpenAPI shipirOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Shipir API")
                        .description("Manage Docker hosts, containers and Compose deployments. "
                                + "Log in via POST /api/auth/login (session cookie); send the XSRF-TOKEN cookie "
                                + "value as X-XSRF-TOKEN header on every non-GET request. "
                                + "Errors use {code, message, details}.")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes(SESSION_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("JSESSIONID")
                                .description("HttpOnly session cookie set by POST /api/auth/login."))
                        .addSecuritySchemes(CSRF_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-XSRF-TOKEN")
                                .description("Value of the XSRF-TOKEN cookie; required for POST/PUT/PATCH/DELETE.")))
                .addSecurityItem(new SecurityRequirement().addList(SESSION_SCHEME).addList(CSRF_SCHEME));
    }

    /**
     * Registers {@link ErrorResponse} as a component schema and adds 400/401/403/404 responses that reference it to
     * every operation that does not declare them, so the spec shows the {@code {code, message, details}} error body.
     */
    @Bean
    OpenApiCustomizer errorResponseCustomizer() {
        return openApi -> {
            Map<String, Schema> schemas = ModelConverters.getInstance().readAll(ErrorResponse.class);
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            schemas.forEach(openApi.getComponents()::addSchemas);
            Map<String, String> errors = new LinkedHashMap<>();
            errors.put("400", "Invalid request (validation or business rule, see code)");
            errors.put("401", "Not authenticated");
            errors.put("403", "Not allowed for this role or permission");
            errors.put("404", "Resource not found");
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(item -> item.readOperations().forEach(op -> addErrors(op, errors)));
        };
    }

    private static void addErrors(Operation op, Map<String, String> errors) {
        errors.forEach((status, description) -> {
            if (op.getResponses() != null && op.getResponses().containsKey(status)) {
                return;
            }
            Schema<?> ref = new Schema<>().$ref("#/components/schemas/ErrorResponse");
            op.getResponses().addApiResponse(status, new ApiResponse().description(description)
                    .content(new Content().addMediaType("application/json", new MediaType().schema(ref))));
        });
    }
}
