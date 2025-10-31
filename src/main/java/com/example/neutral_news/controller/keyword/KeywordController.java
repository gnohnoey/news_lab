package com.example.neutral_news.controller.keyword;

import com.example.neutral_news.dto.ItemsRequest;
import com.example.neutral_news.dto.KeywordCount;
import com.example.neutral_news.service.analzer.KeywordExtractorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/keywords")
public class KeywordController {

    private final KeywordExtractorService svc;

    public KeywordController(KeywordExtractorService svc) {
        this.svc = svc;
    }

    // 예: POST /api/keywords/candidates?topN=30
    // Body: { "items": [ { "title":"...", "description":"...", ... }, ... ] }
    @PostMapping("/candidates")
    public ResponseEntity<List<KeywordCount>> candidates(
            @RequestParam(defaultValue = "30") int topN,
            @RequestBody ItemsRequest body
    ) {
        var items = (body == null || body.items() == null) ? List.<com.example.neutral_news.dto.NewsItem>of() : body.items();
        return ResponseEntity.ok(svc.extractTopN(items, topN));
    }
}