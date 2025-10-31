package com.example.neutral_news.dto;

import java.util.List;

public record CombinedTrendStat(
        String keyword,

        // 뉴스 기반
        List<WeekCount> newsSeries,
        int newsLast, int newsPrev, int newsWow, double newsWowPct, int newsMomentum3,

        // 데이터랩 기반 (0~100 지수)
        List<WeekRatio> datalabSeries,
        double dataLast, double dataPrev, double dataWow, double dataWowPct, double dataMomentum3,

        // 최종 점수(가중 합산 z-score)
        double score
) {}