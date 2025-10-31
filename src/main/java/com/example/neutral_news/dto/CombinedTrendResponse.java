package com.example.neutral_news.dto;

import java.util.List;

public record CombinedTrendResponse(
        List<CombinedTrendStat> risingTop,
        List<CombinedTrendStat> all
) {}