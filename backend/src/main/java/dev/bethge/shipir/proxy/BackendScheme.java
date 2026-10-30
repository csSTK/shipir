package dev.bethge.shipir.proxy;

/** Protocol the proxy speaks to the container. Stored lower-case (V40 CHECK). */
public enum BackendScheme {
    HTTP("http"),
    HTTPS("https"),
    H2C("h2c");

    private final String value;

    BackendScheme(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    static BackendScheme fromValue(String value) {
        for (BackendScheme s : values()) {
            if (s.value.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown backend scheme: " + value);
    }
}
