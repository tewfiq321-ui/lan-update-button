package io.github.tewfiq321ui.lanupdate.util;

import io.github.tewfiq321ui.lanupdate.LanUpdate;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

/** Minimal HTTP client built on HttpURLConnection, so it only needs java.base. */
public final class Http {
    private static final int MAX_TEXT_BYTES = 8 * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 1024L * 1024 * 1024;

    /** Download progress: bytes done so far and the total (or -1 if unknown). */
    @FunctionalInterface
    public interface Progress {
        void update(long done, long total);
    }

    private Http() {}

    private static HttpURLConnection open(String url, int timeoutMillis) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setRequestProperty("User-Agent", "LANUpdateButton/" + LanUpdate.modVersion());
        return connection;
    }

    public static String getText(String url, int timeoutMillis, @Nullable String accept) throws IOException {
        HttpURLConnection connection = open(url, timeoutMillis);
        if (accept != null) {
            connection.setRequestProperty("Accept", accept);
        }
        try {
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code + " from " + url);
            }
            try (InputStream in = connection.getInputStream()) {
                return new String(in.readNBytes(MAX_TEXT_BYTES), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Downloads to a ".part" file first, checks size and SHA-256, then moves it into place.
     * A half-finished download never ends up as a .jar that Minecraft would try to load.
     */
    public static void download(String url, Path target, long expectedSize, @Nullable String expectedSha256, @Nullable Progress progress) throws IOException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        HttpURLConnection connection = open(url, 30_000);
        try {
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code + " from " + url);
            }
            long total = expectedSize > 0 ? expectedSize : connection.getContentLengthLong();
            MessageDigest digest = ModJars.newSha256();
            long done = 0;
            try (InputStream in = connection.getInputStream(); OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    done += read;
                    if (done > MAX_FILE_BYTES) {
                        throw new IOException("File is too big: " + target.getFileName());
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                    if (progress != null) {
                        progress.update(done, total);
                    }
                }
            }
            if (expectedSize > 0 && done != expectedSize) {
                throw new IOException("Download of " + target.getFileName() + " was incomplete (" + done + " of " + expectedSize + " bytes)");
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(sha256)) {
                throw new IOException("Checksum mismatch for " + target.getFileName() + ", the file was not saved");
            }
            try {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // best effort
            }
            throw e;
        } finally {
            connection.disconnect();
        }
    }
}
