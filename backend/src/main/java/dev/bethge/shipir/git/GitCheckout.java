package dev.bethge.shipir.git;

/** Result of a repository sync: the checked-out commit and the content of the compose file in it. */
public record GitCheckout(String commitSha, String composeYaml) {

    @Override
    public String toString() {
        return "GitCheckout[commitSha=" + commitSha + "]";
    }
}
