package com.ephemeral.dto;

import java.util.List;

/** The whole friends picture for one user: accepted, awaiting my answer, awaiting theirs. */
public record FriendsDto(List<UserBriefDto> friends, List<UserBriefDto> incoming, List<UserBriefDto> outgoing) {
}
