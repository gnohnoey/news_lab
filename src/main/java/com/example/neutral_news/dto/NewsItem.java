package com.example.neutral_news.dto;

public record NewsItem(
        String title,
        String description,
        String pubDate,
        String originallink,
        String link
) {}
