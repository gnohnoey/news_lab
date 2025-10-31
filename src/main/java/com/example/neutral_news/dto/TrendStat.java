package com.example.neutral_news.dto;

import java.util.List;

public record TrendStat(
        String keyword,
        int total,
        List<WeekCount> series,
        int lastWeek,
        int prevWeek,
        int wow,          // lastWeek - prevWeek
        double wowPct,    // (wow / max(prevWeek,1))*100
        int momentum3,    // 최근3주 상승 합
        double score      // 상승도 종합 점수
) {}
