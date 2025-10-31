package com.example.neutral_news.service.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.util.*;

@Component
public class DataLabClient {

    @Value("${naver.client-id}")     private String clientId;
    @Value("${naver.client-secret}") private String clientSecret;
    @Value("${naver.datalab-url}")   private String datalabUrl; // https://openapi.naver.com/v1/datalab/search

    private final ObjectMapper om = new ObjectMapper();

    public Map<String, List<Double>> weeklyTrends(List<String> keywords, LocalDate start, LocalDate end) throws Exception {
        // keywordGroups는 5개씩 끊어서 요청
        Map<String, List<Double>> result = new LinkedHashMap<>();
        for (int i = 0; i < keywords.size(); i += 5) {
            List<String> chunk = keywords.subList(i, Math.min(i + 5, keywords.size()));
            Map<String, List<Double>> part = requestChunk(chunk, start, end);
            result.putAll(part);
        }
        return result;
    }

    private Map<String, List<Double>> requestChunk(List<String> kws, LocalDate start, LocalDate end) throws Exception {
        // 바디 구성
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("startDate", start.toString()); // yyyy-MM-dd
        body.put("endDate", end.toString());
        body.put("timeUnit", "week");

        List<Map<String, Object>> groups = new ArrayList<>();
        for (String kw : kws) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("groupName", kw);
            g.put("keywords", List.of(kw));
            groups.add(g);
        }
        body.put("keywordGroups", groups);

        String json = om.writeValueAsString(body);

        HttpURLConnection conn = (HttpURLConnection) new URL(datalabUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("X-Naver-Client-Id", clientId);
        conn.setRequestProperty("X-Naver-Client-Secret", clientSecret);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (var w = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream()))) {
            w.write(json);
        }

        var is = (conn.getResponseCode() == 200) ? conn.getInputStream() : conn.getErrorStream();
        JsonNode root = om.readTree(is);

        Map<String, List<Double>> out = new LinkedHashMap<>();
        for (JsonNode res : root.path("results")) {
            String name = res.path("title").asText(""); // 또는 groupName/keywords 참조
            if (name.isBlank()) name = res.path("keyword").asText(""); // 호환
            if (name.isBlank()) continue;
            List<Double> ratios = new ArrayList<>();
            for (JsonNode d : res.path("data")) {
                ratios.add(d.path("ratio").asDouble(0));
            }
            out.put(name, ratios);
        }
        return out;
    }
}