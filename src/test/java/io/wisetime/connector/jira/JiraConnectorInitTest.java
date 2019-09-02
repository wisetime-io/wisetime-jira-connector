package io.wisetime.connector.jira;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.inject.Guice;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JiraConnector}.
 *
 * @author dchandler
 */
class JiraConnectorInitTest {

  private static JiraConnector connector;

  @BeforeAll
  static void setUp() {
    connector = Guice.createInjector().getInstance(JiraConnector.class);
  }

  @Test
  void getConnectorType_should_not_be_changed() {
    assertThat(connector.getConnectorType())
        .as("Connector returns the expected connector type")
        .isEqualTo("wisetime-jira-connector");
  }
}
