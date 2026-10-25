package dev.bethge.shipir.compose.generator;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

/**
 * Rejects unknown properties in {@code POST /api/compose/generate}, at any depth (wizard objects included), with the
 * usual 400 from {@link dev.bethge.shipir.common.exception.GlobalExceptionHandler}.
 * <p>
 * Spring Boot turns Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES} off globally, so a misspelled wizard field would be
 * dropped silently and the generated YAML would lack it. The global default is left alone on purpose (it would change
 * every endpoint); this advice is scoped to the one request type and runs a strict pass over the raw body before
 * Spring binds it. Binding and {@code @Valid} then run as usual on the same bytes.
 */
@ControllerAdvice
class StrictGenerateRequestAdvice extends RequestBodyAdviceAdapter {

    private static final JsonMapper STRICT_JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return parameter.getContainingClass() == ComposeGeneratorController.class
                && targetType == GenerateRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
                                           Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        byte[] body = inputMessage.getBody().readAllBytes();
        try {
            STRICT_JSON.readValue(body, GenerateRequest.class);
        } catch (JacksonException e) {
            // The message carries the offending property name, but GlobalExceptionHandler never echoes it.
            throw new HttpMessageNotReadableException("Unknown or malformed property in compose generate request",
                    e, inputMessage);
        }
        return new BufferedInputMessage(inputMessage.getHeaders(), body);
    }

    private record BufferedInputMessage(HttpHeaders headers, byte[] body) implements HttpInputMessage {

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }
    }
}
