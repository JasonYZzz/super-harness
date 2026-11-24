package io.superharness.api;

import io.superharness.domain.Problem;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import org.springframework.web.multipart.MultipartFile;

final class SkillArchive {
    private SkillArchive() {}

    static KnowledgeApi.Skill read(MultipartFile file, String version) throws IOException {
        if (file.getSize() > 1024 * 1024) throw new Problem(413, "SKILL_ARCHIVE_TOO_LARGE");
        var temp = Files.createTempFile("skill-upload-", ".zip");
        try {
            file.transferTo(temp);
            try (var zip = new ZipFile(temp.toFile())) {
                return unpack(zip, version);
            } catch (ZipException | IllegalArgumentException e) {
                throw invalid();
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static KnowledgeApi.Skill unpack(ZipFile zip, String version) throws IOException {
        if (zip.size() > 128) throw invalid();
        var entries = new LinkedHashMap<String, ZipEntry>();
        String prefix = null;
        for (var it = zip.entries(); it.hasMoreElements(); ) {
            var entry = it.nextElement();
            String path = entry.getName();
            if (entry.isDirectory() || path.startsWith("__MACOSX/") || path.equals(".DS_Store")
                    || path.endsWith("/.DS_Store")) continue;
            if (entries.putIfAbsent(path, entry) != null || entries.size() > 17) throw invalid();
            if (path.equals("SKILL.md") || path.endsWith("/SKILL.md")) {
                if (prefix != null) throw invalid();
                prefix = path.substring(0, path.length() - "SKILL.md".length());
                if (!prefix.isEmpty() && !prefix.matches("[^./\\\\:\\p{Cntrl}][^/\\\\:\\p{Cntrl}]*/")) throw invalid();
            }
        }
        if (prefix == null) throw invalid();
        String document = null;
        var resources = new TreeMap<String, String>();
        for (var entry : entries.values()) {
            if (!entry.getName().startsWith(prefix)) throw invalid();
            String path = entry.getName().substring(prefix.length());
            if (path.length() > 240) throw invalid();
            boolean isDocument = path.equals("SKILL.md");
            int limit = isDocument ? 8000 : 65536;
            if (entry.getSize() > 4L * limit) throw invalid();
            try (var stream = zip.getInputStream(entry)) {
                byte[] bytes = stream.readNBytes(4 * limit + 1);
                var crc = new CRC32();
                crc.update(bytes);
                if (bytes.length != entry.getSize() || crc.getValue() != entry.getCrc()) throw invalid();
                String value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                if (value.length() > limit || value.indexOf(0) >= 0) throw invalid();
                if (isDocument) document = value;
                else resources.put(path, value);
            } catch (IOException ex) {
                throw invalid();
            }
        }
        return new KnowledgeApi.Skill(null, version, document, null, resources);
    }

    private static Problem invalid() {
        return new Problem(400, "INVALID_SKILL_ARCHIVE");
    }
}
