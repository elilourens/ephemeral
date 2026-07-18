package com.ephemeral.file;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import com.ephemeral.dto.AttachmentDto;
import com.ephemeral.util.Ids;
import com.ephemeral.web.ApiException;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

@RestController
public class FileController {

    private final NamedParameterJdbcTemplate jdbc;
    private final StorageService storage;

    public FileController(NamedParameterJdbcTemplate jdbc, StorageService storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    /** Upload is decoupled from send: returns an attachment id to reference when posting. */
    @PostMapping("/api/uploads")
    public AttachmentDto upload(@CurrentUser AuthUser user, @RequestParam("file") MultipartFile file,
                                @RequestParam(value = "durationMs", required = false) Integer durationMs,
                                @RequestParam(value = "waveform", required = false) String waveform) {
        if (file.isEmpty()) {
            throw ApiException.badRequest("empty file");
        }
        // voice-message metadata (optional) — keep the stored waveform small
        if (waveform != null && waveform.length() > 2048) {
            waveform = null;
        }
        UUID id = Ids.newId();
        String key = storage.store(file, id);
        String filename = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "file" : file.getOriginalFilename());
        jdbc.update("""
                insert into attachments (id, message_id, owner_id, filename, content_type, size_bytes, storage_key, duration_ms, waveform)
                values (:id, null, :o, :f, :ct, :s, :k, :dm, :wf)
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("o", user.id()).addValue("f", filename)
                .addValue("ct", file.getContentType()).addValue("s", file.getSize()).addValue("k", key)
                .addValue("dm", durationMs).addValue("wf", waveform));
        return new AttachmentDto(id, filename, file.getContentType(), file.getSize(), "/api/files/" + id, durationMs, waveform);
    }

    @GetMapping("/api/files/{id}")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        var rows = jdbc.query("select filename, content_type, storage_key from attachments where id = :id",
                Map.of("id", id),
                (rs, i) -> new String[]{rs.getString("filename"), rs.getString("content_type"),
                        rs.getString("storage_key")});
        if (rows.isEmpty()) {
            throw ApiException.notFound("file not found");
        }
        String[] r = rows.get(0);
        Resource res = storage.loadResource(r[2]);
        MediaType type = r[1] != null ? MediaType.parseMediaType(r[1]) : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + r[0] + "\"")
                .body(res);
    }
}
