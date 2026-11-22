package io.superharness.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

/** Standalone entry point installed in the sandbox image. All file operations run under its UID. */
public final class SandboxEdit {
    private static String decode(String argument) {
        return new String(Base64.getDecoder().decode(argument), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        Path path = Path.of(decode(args[0])).toRealPath();
        if (!path.startsWith(Path.of("/workspace")) || !Files.isRegularFile(path))
            throw new IllegalArgumentException("Expected a file in the workspace");
        String text = Files.readString(path), old = decode(args[1]), replacement = decode(args[2]);
        int count = 0;
        if (old.isEmpty()) count = text.codePointCount(0, text.length()) + 1;
        else
            for (int pos = text.indexOf(old); pos >= 0; pos = text.indexOf(old, pos + old.length()))
                count++;
        if (count == 0 || (count > 1 && !Boolean.parseBoolean(args[3]))) {
            System.out.println(
                    count == 0
                            ? "Error: String not found in file"
                            : "Error: Multiple occurrences; use replace_all");
            return;
        }
        // Write before rename so a full workspace or interrupted edit leaves the published file
        // intact.
        Path temp = Files.createTempFile(path.getParent(), ".edit-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temp)) {
                if (old.isEmpty()) {
                    writer.write(replacement);
                    for (int i = 0; i < text.length(); ) {
                        int length = Character.charCount(text.codePointAt(i));
                        writer.write(text, i, length);
                        writer.write(replacement);
                        i += length;
                    }
                } else {
                    int start = 0;
                    for (int pos = text.indexOf(old); pos >= 0; pos = text.indexOf(old, start)) {
                        writer.write(text, start, pos - start);
                        writer.write(replacement);
                        start = pos + old.length();
                    }
                    writer.write(text, start, text.length() - start);
                }
            }
            Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(path));
            Files.move(
                    temp,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Edited " + path + " (" + count + " replacement(s))");
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
