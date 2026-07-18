package com.ephemeral.unfurl;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import com.ephemeral.dto.UnfurlDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UnfurlController {

    private final UnfurlService unfurl;

    public UnfurlController(UnfurlService unfurl) {
        this.unfurl = unfurl;
    }

    /** Authenticated (prevents use as an anonymous proxy). 404 = no card for this URL. */
    @GetMapping("/api/unfurl")
    public ResponseEntity<UnfurlDto> unfurl(@CurrentUser AuthUser user, @RequestParam("url") String url) {
        if (url == null || url.length() > 2048) {
            return ResponseEntity.badRequest().build();
        }
        UnfurlDto dto = unfurl.unfurl(url.trim());
        return dto == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(dto);
    }
}
