package com.fsa.textsql.controller;

import com.fsa.textsql.dto.FewShotExampleDto;
import com.fsa.textsql.repository.FewShotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/few-shots")
@RequiredArgsConstructor
public class FewShotController {

    private final FewShotRepository fewShotRepository;

    @GetMapping
    public ResponseEntity<List<FewShotExampleDto>> getFewShots(
            @RequestParam(required = false) String category) {
        if (category != null && !category.trim().isEmpty() && !"ALL".equalsIgnoreCase(category)) {
            return ResponseEntity.ok(fewShotRepository.findByCategory(category));
        }
        return ResponseEntity.ok(fewShotRepository.findAllActive());
    }
}
