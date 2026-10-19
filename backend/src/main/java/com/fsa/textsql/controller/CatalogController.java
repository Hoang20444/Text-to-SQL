package com.fsa.textsql.controller;

import com.fsa.textsql.dto.DataCatalogDto;
import com.fsa.textsql.service.CatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/catalog")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    @GetMapping
    public ResponseEntity<List<DataCatalogDto>> getCatalog() {
        return ResponseEntity.ok(catalogService.getDatasetCatalog());
    }
}
