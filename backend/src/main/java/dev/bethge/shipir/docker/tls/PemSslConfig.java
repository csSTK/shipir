package dev.bethge.shipir.docker.tls;

import com.github.dockerjava.core.SSLConfig;
import dev.bethge.shipir.docker.DockerEndpoint.TlsMaterial;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Base64;

/**
 * docker-java {@link SSLConfig} built from in-memory PEM strings: trust store with the given CA only, key
 * store with the client certificate and key. Nothing touches the disk. The default trust manager keeps
 * chain verification on; hostname verification is done by the HTTP transport.
 *
 * <p>The constructor parses eagerly and throws {@link dev.bethge.shipir.docker.DockerConnectionException}
 * with {@code INVALID_TLS_MATERIAL} for unusable PEM.
 */
public final class PemSslConfig implements SSLConfig {

    private final SSLContext sslContext;

    public PemSslConfig(TlsMaterial material) {
        TlsMaterialParser.Parsed parsed = TlsMaterialParser.parse(material);
        try {
            char[] password = randomPassword();

            KeyStore trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            int i = 0;
            for (X509Certificate ca : parsed.caCertificates()) {
                trust.setCertificateEntry("ca-" + i++, ca);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);

            KeyStore keys = KeyStore.getInstance("PKCS12");
            keys.load(null, null);
            keys.setKeyEntry("client", parsed.clientKey(), password,
                    parsed.clientChain().toArray(new X509Certificate[0]));
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keys, password);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            this.sslContext = context;
        } catch (Exception e) {
            // Key store set-up with already validated material: an internal failure, keep the cause out of the message
            throw new IllegalStateException("Could not build the TLS context.", e);
        }
    }

    @Override
    public SSLContext getSSLContext() {
        return sslContext;
    }

    private static char[] randomPassword() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes).toCharArray();
    }
}
