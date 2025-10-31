package com.example.neutral_news.service.combine;

import com.example.neutral_news.dto.*;
import com.example.neutral_news.service.integration.DataLabClient;
import com.example.neutral_news.service.analzer.TrendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CombinedTrendService {

    private static final Logger log = LoggerFactory.getLogger(CombinedTrendService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final TrendService trendService;   // 뉴스 주간 시리즈 계산
    private final DataLabClient dataLabClient; // 데이터랩 호출

    public CombinedTrendService(TrendService trendService, DataLabClient dataLabClient) {
        this.trendService = trendService;
        this.dataLabClient = dataLabClient;
    }

    /**
     * @param items       수집한 뉴스 아이템
     * @param topKeywords 형태소 분석으로 뽑은 TopN 키워드
     * @param weeks       최근 N주 (오름차순 시리즈: 과거 → 현재)
     * @param wNews       뉴스 지표 가중치 (예: 0.6)
     * @param wData       데이터랩 지표 가중치 (예: 0.4)
     */
    public CombinedTrendResponse build(
            List<NewsItem> items,
            List<KeywordCount> topKeywords,
            int weeks,
            double wNews,
            double wData
    ) throws Exception {

        // --- 1) 뉴스 기반 주간 시리즈/지표 ---
        Map<String, List<WeekCount>> newsSeries = new LinkedHashMap<>();
        Map<String, Integer> newsLast = new HashMap<>(), newsPrev = new HashMap<>(),
                newsWow = new HashMap<>(), newsMomentum3 = new HashMap<>();
        Map<String, Double> newsWowPct = new HashMap<>();

        for (KeywordCount kc : topKeywords) {
            String kw = kc.keyword();
            List<WeekCount> series = trendService.buildSeriesForKeyword(items, kw, weeks); // 오름차순(과거→현재)로 만들어짐
            newsSeries.put(kw, series);

            int last = series.isEmpty() ? 0 : series.get(weeks - 1).count();      // 최신 주
            int prev = weeks >= 2        ? series.get(weeks - 2).count() : 0;     // 직전 주
            int wowV = last - prev;
            double wowP = (prev == 0) ? ((last > 0) ? 100.0 : 0.0) : (wowV * 100.0 / prev);
            int mom3 = momentum3(series);

            newsLast.put(kw, last);
            newsPrev.put(kw, prev);
            newsWow.put(kw, wowV);
            newsWowPct.put(kw, wowP);
            newsMomentum3.put(kw, mom3);
        }

        // 주차 범위 계산 → 데이터랩 호출 기간 설정
        List<LocalDate> weekStarts = newsSeries.values().stream().findFirst()
                .map(s -> s.stream().map(w -> LocalDate.parse(w.weekStart())).toList())
                .orElseGet(() -> buildWeekStarts(weeks));
        LocalDate startDate = weekStarts.get(0);
        LocalDate endDate   = weekStarts.get(weeks - 1).plusDays(6);

        // --- 2) 데이터랩 호출(키워드 일괄, 5개 단위 분할) ---
        List<String> keywords = topKeywords.stream().map(KeywordCount::keyword).collect(Collectors.toList());
        Map<String, List<Double>> dataMap = Collections.emptyMap();
        try {
            dataMap = dataLabClient.weeklyTrends(keywords, startDate, endDate);
            log.debug("DataLab weeklyTrends OK: {} keywords, weeks {}~{}", keywords.size(), startDate, endDate);
        } catch (Exception e) {
            log.warn("DataLab weeklyTrends FAILED: {} (start={}, end={})", e.getMessage(), startDate, endDate);
            // 실패해도 전체 파이프라인은 진행 (0으로 패딩)
            dataMap = Collections.emptyMap();
        }

        // 데이터랩 시리즈 길이를 weeks에 맞춰 정렬(오름차순) + 패딩
        Map<String, List<WeekRatio>> dataSeries = new LinkedHashMap<>();
        for (String kw : keywords) {
            List<Double> ratios = dataMap.getOrDefault(kw, Collections.emptyList());
            List<Double> aligned = alignByLength(ratios, weeks); // 뒤쪽 정렬, 부족분 0 패딩
            List<WeekRatio> wr = new ArrayList<>(weeks);
            for (int i = 0; i < weeks; i++) {
                wr.add(new WeekRatio(weekStarts.get(i).toString(), aligned.get(i)));
            }
            dataSeries.put(kw, wr);
        }

        // 데이터랩 지표 계산
        Map<String, Double> dataLast = new HashMap<>(), dataPrev = new HashMap<>(),
                dataWow = new HashMap<>(), dataWowPct = new HashMap<>(), dataMomentum3 = new HashMap<>();

        for (String kw : keywords) {
            List<WeekRatio> s = dataSeries.get(kw);
            double last = s.isEmpty() ? 0 : s.get(weeks - 1).ratio();
            double prev = weeks >= 2   ? s.get(weeks - 2).ratio() : 0;
            double wowV = last - prev;
            double wowP = (prev == 0) ? ((last > 0) ? 100.0 : 0.0) : (wowV * 100.0 / prev);
            double mom3 = momentum3D(s);

            dataLast.put(kw, last);
            dataPrev.put(kw, prev);
            dataWow.put(kw, wowV);
            dataWowPct.put(kw, wowP);
            dataMomentum3.put(kw, mom3);
        }

        // --- 3) z-score 정규화 후 가중합 점수 ---
        List<CombinedTrendStat> stats = new ArrayList<>(keywords.size());
        double[] nz1 = z(newsWow, keywords),     nz2 = z(newsWowPct, keywords),     nz3 = z(newsMomentum3, keywords);
        double[] dz1 = z(dataWow, keywords),     dz2 = z(dataWowPct, keywords),     dz3 = z(dataMomentum3, keywords);

        for (int i = 0; i < keywords.size(); i++) {
            String kw = keywords.get(i);
            double newsScore = nz1[i] + nz2[i] + nz3[i];
            double dataScore = dz1[i] + dz2[i] + dz3[i];
            double finalScore = wNews * newsScore + wData * dataScore;

            stats.add(new CombinedTrendStat(
                    kw,
                    newsSeries.getOrDefault(kw, List.of()),
                    newsLast.getOrDefault(kw, 0),
                    newsPrev.getOrDefault(kw, 0),
                    newsWow.getOrDefault(kw, 0),
                    newsWowPct.getOrDefault(kw, 0.0),
                    newsMomentum3.getOrDefault(kw, 0),
                    dataSeries.getOrDefault(kw, List.of()),
                    dataLast.getOrDefault(kw, 0.0),
                    dataPrev.getOrDefault(kw, 0.0),
                    dataWow.getOrDefault(kw, 0.0),
                    dataWowPct.getOrDefault(kw, 0.0),
                    dataMomentum3.getOrDefault(kw, 0.0),
                    finalScore
            ));
        }

        List<CombinedTrendStat> risingTop = stats.stream()
                .sorted(Comparator.comparingDouble(CombinedTrendStat::score).reversed())
                .limit(Math.min(30, stats.size()))
                .toList();

        return new CombinedTrendResponse(risingTop, stats);
    }

    // ---------- helpers ----------

    /** 길이를 weeks에 맞춰 뒤쪽 정렬. 부족분은 앞쪽 0 패딩, 초과분은 앞쪽 컷 */
    private List<Double> alignByLength(List<Double> src, int weeks) {
        if (src == null) src = List.of();
        if (src.size() == weeks) return src;
        if (src.size() > weeks)  return src.subList(src.size() - weeks, src.size());
        List<Double> out = new ArrayList<>(Collections.nCopies(weeks - src.size(), 0.0));
        out.addAll(src);
        return out;
    }

    /** 최근 3주 상승분 합(음수 무시) */
    private int momentum3(List<WeekCount> s) {
        int sum = 0;
        for (int i = s.size() - 2; i < s.size(); i++) {
            if (i <= 0) continue;
            int inc = s.get(i).count() - s.get(i - 1).count();
            if (inc > 0) sum += inc;
        }
        return sum;
    }

    /** 최근 3주 상승분 합(실수 버전, 음수 무시) */
    private double momentum3D(List<WeekRatio> s) {
        double sum = 0.0;
        for (int i = s.size() - 2; i < s.size(); i++) {
            if (i <= 0) continue;
            double inc = s.get(i).ratio() - s.get(i - 1).ratio();
            if (inc > 0) sum += inc;
        }
        return sum;
    }

    /** 지정된 order 순서대로 맵 값을 배열로 꺼내서 z-score 계산에 사용 */
    private double[] z(Map<String, ? extends Number> map, List<String> order) {
        double[] arr = new double[order.size()];
        for (int i = 0; i < order.size(); i++) {
            Number n = map.get(order.get(i));
            arr[i] = (n == null) ? 0.0 : n.doubleValue();
        }
        double m = mean(arr), s = std(arr);
        double[] z = new double[arr.length];
        for (int i = 0; i < arr.length; i++) z[i] = (s == 0) ? 0 : (arr[i] - m) / s;
        return z;
    }

    private double mean(double[] a) {
        if (a.length == 0) return 0;
        double s = 0; for (double v : a) s += v; return s / a.length;
    }
    private double std(double[] a) {
        if (a.length == 0) return 0;
        double m = mean(a), s2 = 0; for (double v : a) s2 += (v - m) * (v - m);
        return Math.sqrt(s2 / a.length);
    }

    /** 최근 weeks개의 주 시작일을 오름차순(과거→현재)으로 반환 */
    private List<LocalDate> buildWeekStarts(int weeks) {
        LocalDate latest = weekStart(LocalDate.now(KST));
        List<LocalDate> list = new ArrayList<>(weeks);
        for (int i = weeks - 1; i >= 0; i--) list.add(latest.minusWeeks(i));
        return list;
    }

    /** 월요일 시작 주의 첫 날 */
    private LocalDate weekStart(LocalDate d) {
        int shift = (d.getDayOfWeek().getValue() + 6) % 7; // Mon=0, Sun=6
        return d.minusDays(shift);
    }
}