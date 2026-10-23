package dev.bethge.shipir.docker.tls;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/** Generates throwaway CAs and certificates (RSA or EC) as PEM strings, using only bcprov and the JDK. */
public final class TestPki {

    private static final ASN1ObjectIdentifier SHA256_RSA = new ASN1ObjectIdentifier("1.2.840.113549.1.1.11");
    private static final ASN1ObjectIdentifier SHA256_ECDSA = new ASN1ObjectIdentifier("1.2.840.10045.4.3.2");
    private static final ASN1ObjectIdentifier P256 = new ASN1ObjectIdentifier("1.2.840.10045.3.1.7");

    public enum KeyType { RSA, EC }

    /** A certificate with its private key; the key is available as PKCS#8, PKCS#1 (RSA) or SEC1 (EC) PEM. */
    public record Issued(String certPem, KeyPair keyPair, KeyType type, X500Name subject) {

        public String keyPkcs8Pem() {
            return pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        }

        /** Traditional format: PKCS#1 for RSA, SEC1 for EC. */
        public String keyTraditionalPem() {
            try {
                PrivateKeyInfo info = PrivateKeyInfo.getInstance(keyPair.getPrivate().getEncoded());
                if (type == KeyType.RSA) {
                    return pem("RSA PRIVATE KEY", info.parsePrivateKey().toASN1Primitive().getEncoded());
                }
                BigInteger d = ((java.security.interfaces.ECPrivateKey) keyPair.getPrivate()).getS();
                // SEC1 as written by openssl: includes the curve parameters
                return pem("EC PRIVATE KEY", new org.bouncycastle.asn1.sec.ECPrivateKey(256, d, P256).getEncoded());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private TestPki() {
    }

    public static Issued ca(String commonName, KeyType type) {
        return issue(commonName, type, null, List.of(), true);
    }

    /** Server certificate valid for the given DNS names and IP addresses. */
    public static Issued server(Issued ca, String commonName, KeyType type, List<String> dnsNames, List<String> ips) {
        return issue(commonName, type, ca, san(dnsNames, ips), false, KeyPurposeId.id_kp_serverAuth);
    }

    public static Issued client(Issued ca, String commonName, KeyType type) {
        return issue(commonName, type, ca, List.of(), false, KeyPurposeId.id_kp_clientAuth);
    }

    private static List<GeneralName> san(List<String> dnsNames, List<String> ips) {
        List<GeneralName> names = new java.util.ArrayList<>();
        dnsNames.forEach(d -> names.add(new GeneralName(GeneralName.dNSName, d)));
        ips.forEach(i -> names.add(new GeneralName(GeneralName.iPAddress, i)));
        return names;
    }

    private static Issued issue(String cn, KeyType type, Issued issuer, List<GeneralName> sans, boolean isCa,
                                KeyPurposeId... purposes) {
        try {
            KeyPair keyPair = generate(type);
            X500Name subject = new X500Name("CN=" + cn);
            X500Name issuerName = issuer == null ? subject : issuer.subject();
            PrivateKey signingKey = issuer == null ? keyPair.getPrivate() : issuer.keyPair().getPrivate();
            KeyType signingType = issuer == null ? type : issuer.type();

            V3TBSCertificateGenerator tbs = new V3TBSCertificateGenerator();
            tbs.setSerialNumber(new ASN1Integer(new BigInteger(64, new java.security.SecureRandom()).abs().add(BigInteger.ONE)));
            tbs.setIssuer(issuerName);
            tbs.setSubject(subject);
            tbs.setStartDate(new Time(Date.from(Instant.now().minusSeconds(3600))));
            tbs.setEndDate(new Time(Date.from(Instant.now().plusSeconds(24 * 3600))));
            tbs.setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded()));
            AlgorithmIdentifier sigAlg = new AlgorithmIdentifier(signingType == KeyType.RSA ? SHA256_RSA : SHA256_ECDSA);
            tbs.setSignature(sigAlg);

            ExtensionsGenerator ext = new ExtensionsGenerator();
            ext.addExtension(Extension.basicConstraints, true, new BasicConstraints(isCa));
            if (isCa) {
                ext.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            } else {
                ext.addExtension(Extension.keyUsage, true,
                        new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
                ext.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(purposes));
            }
            if (!sans.isEmpty()) {
                ext.addExtension(Extension.subjectAlternativeName, false,
                        new GeneralNames(sans.toArray(new GeneralName[0])));
            }
            tbs.setExtensions(ext.generate());

            var tbsCert = tbs.generateTBSCertificate();
            Signature signature = Signature.getInstance(signingType == KeyType.RSA ? "SHA256withRSA" : "SHA256withECDSA");
            signature.initSign(signingKey);
            signature.update(tbsCert.getEncoded("DER"));
            ASN1EncodableVector v = new ASN1EncodableVector();
            v.add(tbsCert);
            v.add(sigAlg);
            v.add(new DERBitString(signature.sign()));
            byte[] der = Certificate.getInstance(new DERSequence(v)).getEncoded("DER");
            return new Issued(pem("CERTIFICATE", der), keyPair, type, subject);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generate(KeyType type) throws Exception {
        if (type == KeyType.RSA) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    public static String pem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }
}
