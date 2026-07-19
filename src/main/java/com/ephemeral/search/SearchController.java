package com.ephemeral.search;

import com.ephemeral.auth.AuthUser;
import com.ephemeral.auth.CurrentUser;
import com.ephemeral.dto.SearchHitDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping("/api/search")
    public List<SearchHitDto> search(@CurrentUser AuthUser user,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) UUID guildId,
                                     @RequestParam(required = false) UUID channelId,
                                     @RequestParam(required = false) UUID authorId,
                                     @RequestParam(required = false) String has,
                                     @RequestParam(defaultValue = "recent") String sort,
                                     @RequestParam(defaultValue = "25") int limit,
                                     @RequestParam(defaultValue = "0") int offset) {
        return search.search(user.id(), q, guildId, channelId, authorId, has, sort, limit, offset);
    }

    /** Mentions inbox: every message that @mentioned me, newest first. */
    @GetMapping("/api/mentions")
    public List<SearchHitDto> mentions(@CurrentUser AuthUser user,
                                       @RequestParam(defaultValue = "50") int limit) {
        return search.recentMentions(user.id(), limit);
    }
}
