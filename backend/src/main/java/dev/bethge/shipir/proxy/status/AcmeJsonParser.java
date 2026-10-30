package dev.bethge.shipir.proxy.status;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Reads the issued certificates out of a Traefik {@code acme.json}. The file also holds the ACME account key and the
 * private key of every certificate, so it is read with a streaming parser that extracts only {@code domain} and
 * {@code certificate}; every other value (notably {@code key} and {@code Account}) is skipped and never kept. The result
 * type {@link IssuedCert} has no place for secrets, and errors carry fixed text only. Bounded by {@link #MAX_BYTES} and
 * {@link #MAX_ENTRIES}.
 */
final class AcmeJsonParser {

    // Note: the caller got the whole file (private keys included) as a byte[] from ContainerFileService.readFile, up to
    // MAX_BYTES. It only lives in memory for the duration of this call; nothing here keeps, logs or returns any key.

    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_ENTRIES = 2000;
    private static final int MAX_DOMAINS = 100;
    private static final int MAX_CERT_B64 = 200 * 1024;
    private static final JsonFactory FACTORY = new JsonFactory();

    private AcmeJsonParser() {
    }

    static List<IssuedCert> parse(byte[] json) {
        if (json == null || json.length == 0) {
            return List.of();
        }
        if (json.length > MAX_BYTES) {
            throw new StatusParseException("acme.json is too large");
        }
        List<IssuedCert> out = new ArrayList<>();
        int[] seen = {0};
        try (JsonParser p = FACTORY.createParser(json)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new StatusParseException("acme.json has an unexpected format");
            }
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                if (p.nextToken() == JsonToken.START_OBJECT) {
                    readResolver(p, out, seen);
                } else {
                    p.skipChildren();
                }
            }
        } catch (JacksonException e) {
            // no cause and no message: either could quote the document
            throw new StatusParseException("acme.json could not be parsed");
        }
        return List.copyOf(out);
    }

    private static void readResolver(JsonParser p, List<IssuedCert> out, int[] seen) {
        while (p.nextToken() == JsonToken.PROPERTY_NAME) {
            String name = p.currentName();
            JsonToken value = p.nextToken();
            if ("Certificates".equalsIgnoreCase(name) && value == JsonToken.START_ARRAY) {
                JsonToken element;
                while ((element = p.nextToken()) != null && element != JsonToken.END_ARRAY) {
                    if (element != JsonToken.START_OBJECT) {
                        p.skipChildren();
                        continue;
                    }
                    if (++seen[0] > MAX_ENTRIES) {
                        throw new StatusParseException("acme.json has too many certificates");
                    }
                    readEntry(p, out);
                }
            } else {
                p.skipChildren();
            }
        }
    }

    private static void readEntry(JsonParser p, List<IssuedCert> out) {
        List<String> domains = new ArrayList<>();
        String certificate = null;
        while (p.nextToken() == JsonToken.PROPERTY_NAME) {
            String name = p.currentName();
            JsonToken value = p.nextToken();
            if ("domain".equalsIgnoreCase(name) && value == JsonToken.START_OBJECT) {
                readDomain(p, domains);
            } else if ("certificate".equalsIgnoreCase(name) && value == JsonToken.VALUE_STRING) {
                certificate = p.getStringLength() <= MAX_CERT_B64 ? p.getString() : null;
            } else {
                p.skipChildren();
            }
        }
        if (certificate == null || domains.isEmpty()) {
            return;
        }
        try {
            byte[] pem = Base64.getMimeDecoder().decode(certificate.getBytes(StandardCharsets.US_ASCII));
            Instant notAfter = CertPem.notAfter(pem);
            out.add(new IssuedCert(List.copyOf(domains), notAfter));
        } catch (IllegalArgumentException | StatusParseException e) {
            // an unreadable entry is ignored, the others still count
        }
    }

    private static void readDomain(JsonParser p, List<String> domains) {
        while (p.nextToken() == JsonToken.PROPERTY_NAME) {
            String name = p.currentName();
            JsonToken value = p.nextToken();
            if ("main".equalsIgnoreCase(name) && value == JsonToken.VALUE_STRING) {
                add(domains, p.getString());
            } else if ("sans".equalsIgnoreCase(name) && value == JsonToken.START_ARRAY) {
                JsonToken element;
                while ((element = p.nextToken()) != null && element != JsonToken.END_ARRAY) {
                    if (element == JsonToken.VALUE_STRING) {
                        add(domains, p.getString());
                    } else {
                        p.skipChildren();
                    }
                }
            } else {
                p.skipChildren();
            }
        }
    }

    private static void add(List<String> domains, String domain) {
        if (domain != null && !domain.isBlank() && domain.length() <= 253 && domains.size() < MAX_DOMAINS) {
            domains.add(domain.toLowerCase(Locale.ROOT));
        }
    }
}
