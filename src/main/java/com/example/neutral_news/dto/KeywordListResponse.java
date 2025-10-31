package com.example.neutral_news.dto;

import java.util.List;

public record KeywordListResponse(int count, List<KeywordCount> items) {}