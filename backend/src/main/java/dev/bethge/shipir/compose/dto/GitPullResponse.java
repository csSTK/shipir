package dev.bethge.shipir.compose.dto;

/**
 * Result of step 1 of "Pull &amp; redeploy": the commit now checked out and the hash of the whole compose graph on disk
 * (null when the graph cannot be hashed, e.g. variable include paths). The ADMIN reviews exactly these files and sends both
 * values back to confirm.
 */
public record GitPullResponse(String commitSha, String graphHash) {
}
