package com.fsa.textsql.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataRootResolutionTest {

  @TempDir Path repo;

  @Test
  void findsDataWhenRunFromRepositoryRoot() throws Exception {
    Files.createDirectories(repo.resolve("data/chinook"));

    Path root = LocalChinookBootstrap.resolveDataRoot("", repo);

    assertThat(root).isEqualTo(repo.resolve("data"));
  }

  @Test
  void findsDataWhenRunFromBackendFolder() throws Exception {
    Files.createDirectories(repo.resolve("data/chinook"));
    Path backend = Files.createDirectories(repo.resolve("backend"));

    Path root = LocalChinookBootstrap.resolveDataRoot(null, backend);

    assertThat(root).isEqualTo(repo.resolve("data").normalize());
  }

  @Test
  void explicitConfigurationWins() {
    Path root = LocalChinookBootstrap.resolveDataRoot("/custom/data", repo);

    assertThat(root).isEqualTo(Path.of("/custom/data"));
  }

  @Test
  void failsWithClearMessageWhenDataFolderIsMissing() {
    assertThatThrownBy(() -> LocalChinookBootstrap.resolveDataRoot("", repo))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NLA_DATA_ROOT");
  }
}
