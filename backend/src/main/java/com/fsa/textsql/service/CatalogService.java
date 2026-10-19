package com.fsa.textsql.service;

import com.fsa.textsql.dto.DataCatalogDto;
import java.util.List;

public interface CatalogService {

    List<DataCatalogDto> getDatasetCatalog();
}
