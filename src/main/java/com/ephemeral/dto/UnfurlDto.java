package com.ephemeral.dto;

/** A link-preview card built from a page's OpenGraph / twitter-card / title meta. */
public record UnfurlDto(
        String url,
        String siteName,
        String title,
        String description,
        String imageUrl,
        String themeColor,
        String type) {
}
