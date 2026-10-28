package dev.bethge.shipir.docker.client;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.dto.ContainerFileEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Fallback for listing one directory when the archive API would have to stream too much (shipr-p4vd). Runs
 * {@code find <dir>/ -mindepth 1 -maxdepth 1 ...} via docker exec with an argument list (no shell). {@code dir}
 * is a normalised absolute path, so it can never be read as an option. Container output is untrusted: names
 * are NUL-separated (newlines in names are legal), anything with a {@code /} is dropped, and both the output
 * size and the entry count are capped. Any failure (no {@code find} in the image, container not running,
 * non-zero exit) throws {@link DockerErrorCode#DIRECTORY_TOO_LARGE}. This is the only exec use in file access;
 * download and upload stay on the archive API.
 */
class ContainerDirectoryExecLister {

    static final int MAX_ENTRIES = 10_000;
    static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;

    private final DockerCallSupport calls;

    ContainerDirectoryExecLister(DockerCallSupport calls) {
        this.calls = calls;
    }

    List<ContainerFileEntry> list(UUID hostId, DockerEndpoint endpoint, String containerId, String dir) {
        try {
            Set<String> all = names(hostId, endpoint, containerId, dir, List.of());
            Set<String> dirs = names(hostId, endpoint, containerId, dir, List.of("-type", "d"));
            Set<String> links = names(hostId, endpoint, containerId, dir, List.of("-type", "l"));
            return toEntries(dir, all, dirs, links);
        } catch (DockerConnectionException e) {
            throw tooLarge(e);
        }
    }

    static List<ContainerFileEntry> toEntries(String dir, Set<String> all, Set<String> dirs, Set<String> links) {
        List<ContainerFileEntry> result = new ArrayList<>();
        for (String name : all) {
            ContainerFileEntry.Type type = dirs.contains(name) ? ContainerFileEntry.Type.DIRECTORY
                    : links.contains(name) ? ContainerFileEntry.Type.SYMLINK : ContainerFileEntry.Type.FILE;
            String path = dir.equals("/") ? "/" + name : dir + "/" + name;
            result.add(new ContainerFileEntry(name, path, type, 0, (Instant) null, 0, null));
        }
        return result;
    }

    /** Argument list of one exec; {@code dir} is absolute, so it is never an option. */
    static List<String> command(String dir, List<String> filter) {
        List<String> cmd = new ArrayList<>(List.of("find", dir.equals("/") ? "/" : dir + "/", "-mindepth", "1", "-maxdepth", "1"));
        cmd.addAll(filter);
        cmd.add("-print0");
        return cmd;
    }

    /**
     * Parses NUL-separated {@code find} output into direct child names. Names that are not a single path
     * segment below {@code dir} are ignored; more than {@link #MAX_ENTRIES} entries is DIRECTORY_TOO_LARGE.
     */
    static Set<String> parse(String dir, byte[] stdout) {
        String prefix = dir.equals("/") ? "/" : dir + "/";
        Set<String> names = new LinkedHashSet<>();
        String text = new String(stdout, StandardCharsets.UTF_8);
        for (String line : text.split("\0")) {
            if (!line.startsWith(prefix)) continue;
            String name = line.substring(prefix.length());
            if (name.isEmpty() || name.contains("/") || name.equals(".") || name.equals("..")) continue;
            names.add(name);
            if (names.size() > MAX_ENTRIES) {
                throw tooLarge(null);
            }
        }
        return names;
    }

    private Set<String> names(UUID hostId, DockerEndpoint endpoint, String containerId, String dir, List<String> filter) {
        byte[] out = exec(hostId, endpoint, containerId, command(dir, filter));
        return out.length == 0 ? new HashSet<>() : parse(dir, out);
    }

    private byte[] exec(UUID hostId, DockerEndpoint endpoint, String containerId, List<String> cmd) {
        return calls.call(hostId, endpoint, client -> {
            String id = client.execCreateCmd(containerId).withAttachStdout(true).withAttachStderr(true)
                    .withCmd(cmd.toArray(String[]::new)).exec().getId();
            OutputCollector callback = new OutputCollector(MAX_OUTPUT_BYTES);
            try {
                client.execStartCmd(id).exec(callback);
                if (!callback.awaitCompletion(calls.manager().readTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        && !callback.overflowed()) {
                    throw new DockerConnectionException(DockerErrorCode.TIMEOUT, "The listing timed out.", null);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw tooLarge(e);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw tooLarge(e);
            } finally {
                try {
                    callback.close();
                } catch (IOException ignored) {
                    // already ending
                }
            }
            if (callback.overflowed()) throw tooLarge(null);
            checkExit(client.inspectExecCmd(id).exec().getExitCodeLong());
            return callback.bytes();
        });
    }

    static void checkExit(Long exitCode) {
        if (exitCode == null || exitCode != 0) throw tooLarge(null);
    }

    /** Collects stdout up to {@code max} bytes; on overflow it closes itself so a runaway command is cut off. */
    static class OutputCollector extends ResultCallback.Adapter<Frame> {
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final int max;
        private boolean overflow;
        private boolean closed;

        OutputCollector(int max) {
            this.max = max;
        }

        @Override
        public void onNext(Frame frame) {
            if (frame.getStreamType() != StreamType.STDOUT || overflow) return;
            byte[] payload = frame.getPayload();
            if (stdout.size() + payload.length > max) {
                overflow = true;
                try {
                    close();
                } catch (IOException ignored) {
                    // aborting anyway
                }
                return;
            }
            stdout.write(payload, 0, payload.length);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        boolean overflowed() {
            return overflow;
        }

        boolean isClosed() {
            return closed;
        }

        byte[] bytes() {
            return stdout.toByteArray();
        }
    }

    private static DockerConnectionException tooLarge(Throwable cause) {
        return new DockerConnectionException(DockerErrorCode.DIRECTORY_TOO_LARGE, "The directory is too large to list.", cause);
    }
}
