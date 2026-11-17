package dev.bethge.shipir.architecture.fixture.host;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Deliberate violation: opens a file with a File stream constructor. */
public class BadFileInputStreamUser {
    InputStream open(String path) throws IOException {
        return new FileInputStream(path);
    }
}
