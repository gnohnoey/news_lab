package com.example.neutral_news.service.analzer;

import com.example.neutral_news.dto.KeywordCount;
import com.example.neutral_news.dto.NewsItem;
import com.example.neutral_news.dto.TrendResponse;
import com.example.neutral_news.dto.TrendStat;
import com.example.neutral_news.dto.WeekCount;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * - 기준 타임존: Asia/Seoul
 * - 주 시작: 월요일
 * - 시리즈 정렬: 과거 → 현재(오름차순)
 */
@Service
public class TrendService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter RFC1123 = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    /**
     * 뉴스 아이템 + 키워드 목록(TopN)을 받아
     * keyword별 주간 카운트와 WoW/모멘텀/점수를 계산.
     */
    public TrendResponse computeWeeklyTrends(
            List<NewsItem> items,
            List<KeywordCount> topKeywords,
            int weeks
    ) {

        // 1) 주차 버킷: 최근 weeks개(오름차순: 과거→현재)
        List<LocalDate> weekStarts = buildWeekStarts(weeks);

        // 2) 아이템 전처리: (text, textNoSpace, weekIndex)
        List<Prepped> prepped = new ArrayList<>();
        for (NewsItem it : items) {
            LocalDate date = pubDateToLocalDate(it.pubDate());
            if (date == null) continue;
            int idx = indexOfWeek(date, weekStarts);
            if (idx < 0) continue;

            String raw = nullToEmpty(it.title()) + " " + nullToEmpty(it.description());
            // 핵심: 태그는 공백이 아니라 ""로 제거해야 <b>미</b><b>국</b> → "미국"으로 합쳐짐
            String text = TAGS.matcher(raw).replaceAll("").replaceAll("\\s+", " ").trim();
            String textNoSpace = text.replace(" ", "");

            prepped.add(new Prepped(text, textNoSpace, idx));
        }

        // 3) 키워드별 시리즈 계산
        Map<String, List<WeekCount>> seriesMap = new HashMap<>();
        Map<String, Integer> totals = new HashMap<>();

        for (KeywordCount kc : topKeywords) {
            String kw = kc.keyword();
            String kwNoSpace = kw == null ? "" : kw.replace(" ", "");
            int[] counts = new int[weeks];

            for (Prepped p : prepped) {
                if (containsKeyword(p.text, p.textNoSpace, kw, kwNoSpace)) {
                    counts[p.weekIndex] += 1;
                }
            }

            List<WeekCount> wc = new ArrayList<>(weeks);
            int total = 0;
            for (int i = 0; i < weeks; i++) {
                int c = counts[i];
                total += c;
                wc.add(new WeekCount(weekStarts.get(i).toString(), c));
            }
            seriesMap.put(kw, wc);
            totals.put(kw, total);
        }

        // 4) 통계 계산
        List<TrendStat> allStats = new ArrayList<>();
        for (KeywordCount kc : topKeywords) {
            String kw = kc.keyword();
            List<WeekCount> wc = seriesMap.getOrDefault(kw, List.of());
            int total = totals.getOrDefault(kw, 0);

            int last = wc.isEmpty() ? 0 : wc.get(weeks - 1).count(); // 최신 주
            int prev = weeks >= 2 ? wc.get(weeks - 2).count() : 0;   // 직전 주
            int wow = last - prev;
            double wowPct = prev == 0 ? (last > 0 ? 100.0 : 0.0) : (wow * 100.0 / prev);
            int momentum3 = sumTailIncreases(wc, 3);

            allStats.add(new TrendStat(kw, total, wc, last, prev, wow, wowPct, momentum3, 0.0));
        }

        // 5) z-score 정규화 후 최종 score 계산
        allStats = applyZScore(allStats);

        // 6) 점수 상위 정렬
        List<TrendStat> risingTop = allStats.stream()
                .sorted(Comparator.comparingDouble(TrendStat::score).reversed())
                .limit(Math.min(30, allStats.size()))
                .toList();

        return new TrendResponse(risingTop, allStats);
    }

    // ---------- helpers ----------

    private static final class Prepped {
        final String text;
        final String textNoSpace;
        final int weekIndex;
        Prepped(String text, String textNoSpace, int weekIndex) {
            this.text = text;
            this.textNoSpace = textNoSpace;
            this.weekIndex = weekIndex;
        }
    }

    private String nullToEmpty(String s) {
        return (s == null) ? "" : s;
    }

    /** 키워드 포함 여부: 원문 포함 or 공백 제거본 포함 */
    private boolean containsKeyword(String text, String textNoSpace, String keyword, String keywordNoSpace) {
        if (keyword == null || keyword.isBlank()) return false;
        if (text != null && text.contains(keyword)) return true;
        return (textNoSpace != null && !keywordNoSpace.isBlank() && textNoSpace.contains(keywordNoSpace));
    }

    /** pubDate → KST LocalDate */
    private LocalDate pubDateToLocalDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) return null;
        try {
            return ZonedDateTime.parse(pubDate, RFC1123).withZoneSameInstant(KST).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }

    /** 최근 weeks개의 주 시작일을 오름차순(과거→현재)으로 반환 */
    private List<LocalDate> buildWeekStarts(int weeks) {
        LocalDate latest = weekStart(LocalDate.now(KST));
        List<LocalDate> list = new ArrayList<>(weeks);
        for (int i = weeks - 1; i >= 0; i--) {
            list.add(latest.minusWeeks(i));
        }
        return list;
    }

    /** 월요일 시작 주의 첫 날 */
    private LocalDate weekStart(LocalDate date) {
        int shift = (date.getDayOfWeek().getValue() + 6) % 7; // Mon=0, Sun=6
        return date.minusDays(shift);
    }

    /** date가 어느 주 버킷(index)에 속하는지 */
    private int indexOfWeek(LocalDate d, List<LocalDate> weekStarts) {
        LocalDate ws = weekStart(d);
        for (int i = 0; i < weekStarts.size(); i++) {
            if (weekStarts.get(i).equals(ws)) return i;
        }
        return -1;
    }

    /** 최근 n주 구간에서 양의 증가만 합산 (단기 모멘텀) */
    private int sumTailIncreases(List<WeekCount> wc, int n) {
        int sum = 0;
        for (int i = wc.size() - n + 1; i < wc.size(); i++) {
            if (i <= 0) continue;
            int inc = wc.get(i).count() - wc.get(i - 1).count();
            if (inc > 0) sum += inc;
        }
        return sum;
    }

    /** z-score 정규화 후 3개 지표(wow, wowPct, momentum3) 합산 스코어 */
    private List<TrendStat> applyZScore(List<TrendStat> stats) {
        double[] wowArr = stats.stream().mapToDouble(TrendStat::wow).toArray();
        double[] pctArr = stats.stream().mapToDouble(TrendStat::wowPct).toArray();
        double[] momArr = stats.stream().mapToDouble(TrendStat::momentum3).toArray();

        double mw = mean(wowArr), sw = std(wowArr);
        double mp = mean(pctArr), sp = std(pctArr);
        double mm = mean(momArr), sm = std(momArr);

        List<TrendStat> out = new ArrayList<>(stats.size());
        for (TrendStat s : stats) {
            double z1 = sw == 0 ? 0 : (s.wow() - mw) / sw;
            double z2 = sp == 0 ? 0 : (s.wowPct() - mp) / sp;
            double z3 = sm == 0 ? 0 : (s.momentum3() - mm) / sm;
            out.add(new TrendStat(
                    s.keyword(), s.total(), s.series(),
                    s.lastWeek(), s.prevWeek(), s.wow(), s.wowPct(), s.momentum3(),
                    z1 + z2 + z3
            ));
        }
        return out;
    }

    private double mean(double[] a) {
        if (a.length == 0) return 0;
        double s = 0; for (double v : a) s += v; return s / a.length;
    }
    private double std(double[] a) {
        if (a.length == 0) return 0;
        double m = mean(a), s2 = 0; for (double v : a) s2 += (v - m)*(v - m);
        return Math.sqrt(s2 / a.length);
    }

    // ------ 공개 유틸: 외부에서 특정 키워드만 시리즈 만들 때 사용 ------

    public List<WeekCount> buildSeriesForKeyword(List<NewsItem> items, String keyword, int weeks) {
        List<LocalDate> weekStarts = buildWeekStarts(weeks);
        int[] counts = new int[weeks];

        String kw = keyword == null ? "" : keyword;
        String kwNoSpace = kw.replace(" ", "");

        for (NewsItem it : items) {
            LocalDate date = pubDateToLocalDate(it.pubDate());
            if (date == null) continue;
            int idx = indexOfWeek(date, weekStarts);
            if (idx < 0) continue;

            String raw = nullToEmpty(it.title()) + " " + nullToEmpty(it.description());
            String text = TAGS.matcher(raw).replaceAll("").replaceAll("\\s+", " ").trim();
            String textNoSpace = text.replace(" ", "");

            if (containsKeyword(text, textNoSpace, kw, kwNoSpace)) {
                counts[idx]++;
            }
        }

        List<WeekCount> out = new ArrayList<>(weeks);
        for (int i = 0; i < weeks; i++) {
            out.add(new WeekCount(weekStarts.get(i).toString(), counts[i]));
        }

        return out;
    }
}