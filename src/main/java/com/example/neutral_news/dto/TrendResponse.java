package com.example.neutral_news.dto;

import java.util.List;

public record TrendResponse(
        List<TrendStat> risingTop,  // 점수 상위 N
        List<TrendStat> all         // 전체(정렬X 또는 키워드명/총합 등 기본정렬)
) {}