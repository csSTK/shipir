package dev.bethge.shipir.proxy.cert;

import dev.bethge.shipir.proxy.ProxyException;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.springframework.http.HttpStatus;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure validation of an uploaded certificate chain and private key. Only unencrypted PEM; the key may be PKCS#8, PKCS#1
 * (RSA) or SEC1 (EC) and is proven to belong to the leaf by signing and verifying a random challenge. Error messages
 * never contain input. Throws {@link ProxyException} (400) with a stable code.
 */
final class CertificateParser {

    static final int MAX_CERT_BYTES = 64 * 1024;
    static final int MAX_KEY_BYTES = 16 * 1024;
    static final int MIN_RSA_BITS = 2048;
    static final int MAX_CHAIN = 10;
    static final int MAX_DOMAINS = 100;

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s]*?)-----END \\1-----");
    private static final Pattern PRIVATE_KEY_HEADER = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");
    private static final Pattern DOMAIN = Pattern.compile(
            "(\\*\\.)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final SecureRandom RANDOM = new SecureRandom();

    private CertificateParser() {
    }

    static CertificateMaterial parse(String certPem, String keyPem, Instant now) {
        if (certPem == null || certPem.isBlank() || keyPem == null || keyPem.isBlank()) {
            throw bad("INVALID_CERTIFICATE", "Certificate and key are required");
        }
        if (certPem.length() > MAX_CERT_BYTES || keyPem.length() > MAX_KEY_BYTES) {
            throw bad("CERT_TOO_LARGE", "Certificate chain or key is too large");
        }
        List<X509Certificate> chain = parseChain(certPem);
        X509Certificate leaf = chain.get(0);
        if (leaf.getNotAfter().toInstant().isBefore(now)) {
            throw bad("CERTIFICATE_EXPIRED", "The certificate has expired");
        }
        if (leaf.getNotBefore().toInstant().isAfter(now)) {
            throw bad("CERTIFICATE_NOT_YET_VALID", "The certificate is not valid yet");
        }
        PrivateKey key = parseKey(keyPem);
        requireMatch(key, leaf.getPublicKey());
        List<String> domains = domainsOf(leaf);
        return new CertificateMaterial(canonical(chain), pem("PRIVATE KEY", key.getEncoded()), domains, leaf.getNotAfter().toInstant());
    }

    private static List<X509Certificate> parseChain(String pem) {
        if (PRIVATE_KEY_HEADER.matcher(pem).find()) {
            throw bad("INVALID_CERTIFICATE", "The certificate field contains a private key");
        }
        Matcher m = PEM_BLOCK.matcher(pem);
        List<X509Certificate> chain = new ArrayList<>();
        int last = 0;
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            while (m.find()) {
                if (!pem.substring(last, m.start()).isBlank() || !"CERTIFICATE".equals(m.group(1))) {
                    throw bad("INVALID_CERTIFICATE", "The certificate must be PEM encoded certificates only");
                }
                last = m.end();
                if (chain.size() == MAX_CHAIN) {
                    throw bad("INVALID_CERTIFICATE", "The certificate chain has too many certificates");
                }
                byte[] der = Base64.getMimeDecoder().decode(m.group(2));
                chain.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
            }
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw bad("INVALID_CERTIFICATE", "The certificate could not be parsed");
        }
        if (chain.isEmpty() || !pem.substring(last).isBlank()) {
            throw bad("INVALID_CERTIFICATE", "The certificate must be PEM encoded certificates only");
        }
        return chain;
    }

    private static PrivateKey parseKey(String pem) {
        if (pem.contains("-----BEGIN ENCRYPTED PRIVATE KEY-----") || pem.contains("Proc-Type:")) {
            throw bad("KEY_ENCRYPTED", "Encrypted private keys are not supported, remove the passphrase first");
        }
        Matcher m = PEM_BLOCK.matcher(pem);
        if (!m.find() || !pem.substring(0, m.start()).isBlank() || !pem.substring(m.end()).isBlank()) {
            throw bad("INVALID_KEY", "The key must be exactly one PEM private key");
        }
        byte[] der;
        try {
            der = Base64.getMimeDecoder().decode(m.group(2));
        } catch (IllegalArgumentException e) {
            throw bad("INVALID_KEY", "The private key could not be parsed");
        }
        try {
            byte[] pkcs8 = switch (m.group(1)) {
                case "PRIVATE KEY" -> der;
                case "RSA PRIVATE KEY" -> new PrivateKeyInfo(
                        new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE),
                        ASN1Primitive.fromByteArray(der)).getEncoded();
                case "EC PRIVATE KEY" -> {
                    ECPrivateKey ec = ECPrivateKey.getInstance(ASN1Primitive.fromByteArray(der));
                    ASN1Encodable curve = ec.getParametersObject();
                    if (curve == null) {
                        throw bad("INVALID_KEY", "The EC key carries no curve");
                    }
                    yield new PrivateKeyInfo(new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, curve), ec)
                            .getEncoded();
                }
                default -> throw bad("INVALID_KEY", "Unsupported private key type");
            };
            for (String algorithm : List.of("RSA", "EC", "Ed25519")) {
                try {
                    PrivateKey key = KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
                    if (key instanceof java.security.interfaces.RSAKey rsa && rsa.getModulus().bitLength() < MIN_RSA_BITS) {
                        throw bad("INVALID_KEY", "RSA keys need at least " + MIN_RSA_BITS + " bits");
                    }
                    return key;
                } catch (GeneralSecurityException ignored) {
                    // try the next algorithm
                }
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ProxyException pe) {
                throw pe;
            }
        }
        throw bad("INVALID_KEY", "The private key could not be parsed (RSA, EC and Ed25519 are supported)");
    }

    /** The key belongs to the certificate if a signature made with it verifies with the certificate's public key. */
    private static void requireMatch(PrivateKey key, PublicKey publicKey) {
        String algorithm = switch (publicKey.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            case "Ed25519", "EdDSA" -> "Ed25519";
            default -> null;
        };
        if (algorithm == null) {
            throw bad("INVALID_CERTIFICATE", "Unsupported certificate key type (RSA, EC and Ed25519 are supported)");
        }
        boolean same = false;
        try {
            byte[] challenge = new byte[32];
            RANDOM.nextBytes(challenge);
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(challenge);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(publicKey);
            verifier.update(challenge);
            same = verifier.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            same = false; // different key type or curve
        }
        if (!same) {
            throw bad("KEY_MISMATCH", "The private key does not belong to the certificate");
        }
    }

    /** DNS SANs; the subject CN only when there is no DNS SAN. Lower-case, deduplicated, only valid names kept. */
    private static List<String> domainsOf(X509Certificate leaf) {
        Set<String> names = new LinkedHashSet<>();
        try {
            Collection<List<?>> sans = leaf.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    if (san.size() == 2 && Integer.valueOf(2).equals(san.get(0)) && san.get(1) instanceof String s) {
                        names.add(s.toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (java.security.cert.CertificateParsingException e) {
            throw bad("INVALID_CERTIFICATE", "The certificate alternative names could not be read");
        }
        if (names.isEmpty()) {
            try {
                for (Rdn rdn : new LdapName(leaf.getSubjectX500Principal().getName()).getRdns()) {
                    if ("CN".equalsIgnoreCase(rdn.getType()) && rdn.getValue() instanceof String cn) {
                        names.add(cn.toLowerCase(Locale.ROOT));
                    }
                }
            } catch (InvalidNameException e) {
                // no CN
            }
        }
        List<String> valid = names.stream().filter(n -> n.length() <= 253 && DOMAIN.matcher(n).matches()).toList();
        if (valid.isEmpty()) {
            throw bad("INVALID_CERTIFICATE", "The certificate names no usable domain (SAN or CN)");
        }
        if (valid.size() > MAX_DOMAINS) {
            throw bad("INVALID_CERTIFICATE", "The certificate names too many domains");
        }
        return valid;
    }

    private static String canonical(List<X509Certificate> chain) {
        StringBuilder sb = new StringBuilder();
        try {
            for (X509Certificate c : chain) {
                sb.append(pem("CERTIFICATE", c.getEncoded()));
            }
        } catch (GeneralSecurityException e) {
            throw bad("INVALID_CERTIFICATE", "The certificate could not be encoded");
        }
        return sb.toString();
    }

    /** Re-emits DER as PEM, so only what was parsed is stored, never the text that was sent. */
    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static ProxyException bad(String code, String message) {
        return new ProxyException(HttpStatus.BAD_REQUEST, code, message);
    }
}
