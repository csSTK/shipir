package dev.bethge.shipir.docker.tls;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint.TlsMaterial;
import dev.bethge.shipir.docker.DockerErrorCode;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;

import java.io.ByteArrayInputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses PEM strings (CA certificates, client certificate chain, unencrypted RSA or EC private key in PKCS#8,
 * PKCS#1 or SEC1 form) with the JDK and bcprov's ASN.1 classes. Everything stays in memory.
 *
 * <p>Failures throw {@link DockerConnectionException} with {@link DockerErrorCode#INVALID_TLS_MATERIAL}. The
 * messages name the broken part only; neither PEM content nor the underlying parser exception is included.
 */
final class TlsMaterialParser {

    record Parsed(List<X509Certificate> caCertificates, List<X509Certificate> clientChain, PrivateKey clientKey) {
    }

    private static final Pattern PEM_BLOCK =
            Pattern.compile("-----BEGIN ([A-Z0-9 ]+)-----([^-]*)-----END \\1-----");

    private TlsMaterialParser() {
    }

    static Parsed parse(TlsMaterial material) {
        if (material == null) {
            throw invalid("TLS material is missing.");
        }
        List<X509Certificate> ca = certificates(material.caPem(), "CA certificate");
        List<X509Certificate> chain = certificates(material.certPem(), "client certificate");
        PrivateKey key = privateKey(material.keyPem());
        if (!keyMatches(key, chain.getFirst())) {
            throw invalid("The client key does not match the client certificate.");
        }
        return new Parsed(ca, chain, key);
    }

    private static List<X509Certificate> certificates(String pem, String what) {
        List<X509Certificate> result = new ArrayList<>();
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            for (Block block : blocks(pem)) {
                if (block.label.equals("CERTIFICATE")) {
                    Certificate cert = factory.generateCertificate(new ByteArrayInputStream(block.der));
                    result.add((X509Certificate) cert);
                }
            }
        } catch (Exception e) {
            throw invalid("The " + what + " is not valid PEM.");
        }
        if (result.isEmpty()) {
            throw invalid("The " + what + " is missing or not valid PEM.");
        }
        return result;
    }

    private static PrivateKey privateKey(String pem) {
        try {
            for (Block block : blocks(pem)) {
                PrivateKeyInfo info = switch (block.label) {
                    case "PRIVATE KEY" -> PrivateKeyInfo.getInstance(block.der);
                    case "RSA PRIVATE KEY" -> fromPkcs1(block.der);
                    case "EC PRIVATE KEY" -> fromSec1(block.der);
                    case "ENCRYPTED PRIVATE KEY" -> throw invalid("Encrypted client keys are not supported.");
                    default -> null;
                };
                if (info != null) {
                    String algorithm = algorithm(info.getPrivateKeyAlgorithm().getAlgorithm());
                    return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(info.getEncoded("DER")));
                }
            }
        } catch (DockerConnectionException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("The client key is not valid PEM.");
        }
        throw invalid("The client key is missing or not valid PEM.");
    }

    private static PrivateKeyInfo fromPkcs1(byte[] der) throws Exception {
        RSAPrivateKey rsa = RSAPrivateKey.getInstance(der);
        return new PrivateKeyInfo(new AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE), rsa);
    }

    private static PrivateKeyInfo fromSec1(byte[] der) throws Exception {
        ECPrivateKey ec = ECPrivateKey.getInstance(ASN1Sequence.getInstance(der));
        ASN1Primitive parameters = ec.getParametersObject() == null ? null : ec.getParametersObject().toASN1Primitive();
        if (parameters == null) {
            throw invalid("The client key is not valid PEM.");
        }
        ASN1Encodable curve = parameters;
        return new PrivateKeyInfo(new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, curve), ec);
    }

    private static String algorithm(ASN1ObjectIdentifier oid) {
        if (PKCSObjectIdentifiers.rsaEncryption.equals(oid)) return "RSA";
        if (X9ObjectIdentifiers.id_ecPublicKey.equals(oid)) return "EC";
        throw invalid("The client key type is not supported (RSA or EC).");
    }

    /** Signs and verifies a probe, so a key that doesn't belong to the certificate is rejected up front. */
    private static boolean keyMatches(PrivateKey key, X509Certificate cert) {
        try {
            String algorithm = key.getAlgorithm().equals("RSA") ? "SHA256withRSA" : "SHA256withECDSA";
            byte[] probe = new byte[] {1, 2, 3, 4};
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(cert.getPublicKey());
            verifier.update(probe);
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    private record Block(String label, byte[] der) {
    }

    private static List<Block> blocks(String pem) {
        List<Block> blocks = new ArrayList<>();
        if (pem == null) {
            return blocks;
        }
        Matcher m = PEM_BLOCK.matcher(pem);
        while (m.find()) {
            String body = m.group(2).replaceAll("\\s", "");
            blocks.add(new Block(m.group(1), Base64.getDecoder().decode(body)));
        }
        return blocks;
    }

    private static DockerConnectionException invalid(String message) {
        return new DockerConnectionException(DockerErrorCode.INVALID_TLS_MATERIAL, message, null);
    }
}
