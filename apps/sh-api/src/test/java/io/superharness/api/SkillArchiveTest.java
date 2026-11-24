package io.superharness.api;

import static org.junit.jupiter.api.Assertions.*;

import io.superharness.domain.Problem;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class SkillArchiveTest {
    private static MockMultipartFile archive(Map<String, byte[]> entries) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return new MockMultipartFile("file", "skill.zip", "application/zip", bytes.toByteArray());
    }

    @Test
    void preservesNestedTextWithEitherRootLayout() throws IOException {
        for (String prefix : new String[] {"", "示例/"}) {
            var upload = archive(Map.of(prefix + "SKILL.md", "说明".getBytes(StandardCharsets.UTF_8),
                    prefix + "references/guide.md", "参考".getBytes(StandardCharsets.UTF_8)));
            var skill = SkillArchive.read(upload, "1");
            assertEquals("说明", skill.content());
            assertEquals(Map.of("references/guide.md", "参考"), skill.resources());
        }
    }

    @Test
    void rejectsAmbiguousRootsMalformedTextAndOversizedExpansion() {
        byte[] text = {'x'};
        for (var entries : java.util.List.of(
                Map.of("a/SKILL.md", text, "b/SKILL.md", text),
                Map.of("../SKILL.md", text),
                Map.of("references/doc", text),
                Map.of("SKILL.md", new byte[]{(byte) 0xff}),
                Map.of("SKILL.md", text, "references/huge", new byte[262145]))) {
            var error = assertThrows(Problem.class, () -> SkillArchive.read(archive(entries), "1"));
            assertEquals(400, error.status);
        }
    }
}
