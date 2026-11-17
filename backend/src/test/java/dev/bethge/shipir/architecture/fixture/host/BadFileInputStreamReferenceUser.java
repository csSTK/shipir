package dev.bethge.shipir.architecture.fixture.host;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Deliberate violation: takes the FileInputStream constructor as a reference, which a call-only rule misses. */
public class BadFileInputStreamReferenceUser {
    interface IoFactory {
        InputStream open(String path) throws IOException;
    }

    IoFactory factory = FileInputStream::new;
}
