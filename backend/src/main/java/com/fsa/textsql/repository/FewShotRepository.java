package com.fsa.textsql.repository;

import com.fsa.textsql.dto.FewShotExampleDto;
import java.util.List;

public interface FewShotRepository {

    List<FewShotExampleDto> findAllActive();

    List<FewShotExampleDto> findByCategory(String category);
}
