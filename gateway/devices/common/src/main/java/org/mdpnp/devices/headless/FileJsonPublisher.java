package org.mdpnp.devices.headless;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class FileJsonPublisher implements JsonPublisher {
    private final BufferedWriter writer;

    public FileJsonPublisher(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) { Files.createDirectories(parent); }
        this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (e.g. Windows) — skip permission setting
        }
    }

    @Override
    public synchronized void publish(Map<String, Object> event) throws IOException {
        writer.write(JsonUtil.toJson(event));
        writer.newLine();
        writer.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }
}
