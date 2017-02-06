/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package it.issue;

import com.sonar.orchestrator.build.SonarScanner;
import com.sonar.orchestrator.container.Server;
import com.sonar.orchestrator.locator.FileLocation;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;
import org.sonar.wsclient.issue.Issue;
import org.sonar.wsclient.issue.IssueQuery;
import org.sonarqube.ws.ProjectAnalyses;
import org.sonarqube.ws.client.projectanalysis.SearchRequest;
import util.ItUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static util.ItUtils.projectDir;

/**
 * @see <a href="https://jira.sonarsource.com/browse/MMF-567">MMF-567</a>
 */
public class IssueCreationDateTest extends AbstractIssueTest {

  private static final String LANGUAGE_XOO = "xoo";

  private static final String DATETIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ssZ";

  private static final String SAMPLE_PROJECT_KEY = "creation-date-sample";
  private static final String SAMPLE_PROJECT_NAME = "Creation date sample";

  // source file locations
  private enum SourceCode {
    INITIAL("issue/creationDateSampleInitial"),
    CHANGED("issue/creationDateSampleChanged");

    private final String path;

    private SourceCode(String path) {
      this.path = path;
    }
  }

  // source components
  private enum Component {

    OnlyInInitial("creation-date-sample:src/main/xoo/sample/OnlyInInitial.xoo"),
    ForeverAndModified("creation-date-sample:src/main/xoo/sample/ForeverAndModified.xoo"),
    ForeverAndUnmodified("creation-date-sample:src/main/xoo/sample/ForeverAndUnmodified.xoo"),
    OnlyInChanged("creation-date-sample:src/main/xoo/sample/OnlyInChanged.xoo"),
    ;
    private final String key;

    private Component(String key) {
      this.key = key;
    }

    public String getKey() {
      return key;
    }
  }

  private static final Component[] FILES_OF_SOURCE_INITIAL = {Component.OnlyInInitial, Component.ForeverAndModified, Component.ForeverAndUnmodified};
  private static final Component[] FILES_OF_SOURCE_CHANGED = {Component.ForeverAndModified, Component.ForeverAndUnmodified, Component.OnlyInChanged};

  // quality profiles
  private enum QProfile {
    ONE_RULE("/issue/IssueCreationDateTest/one-rule.xml", "one-rule"),
    NO_RULES("/issue/IssueCreationDateTest/no-rules.xml", "no-rules");

    private final String path;
    private final String name;

    private QProfile(String path, String name) {
      this.path = path;
      this.name = name;
    }
  }

  // scm settings
  private enum Scm {
    ON_ {
      @Override
      void configure(SonarScanner scanner) {
        scanner
          .setProperty("sonar.scm.provider", "xoo")
          .setProperty("sonar.scm.disabled", "false");
      }
    },
    OFF;

    void configure(SonarScanner scanner) {
    }
  }

  // issue creation dates
  private enum IssueCreationDate {
    OnlyInInitial_R1("2001-01-01T00:00:00+0000"),
    ForeverAndUnmodified_R1("2002-01-01T00:00:00+0000"),
    ForeverAndModified_R1("2003-01-01T00:00:00+0000"),
    ForeverAndModified_R2("2004-01-01T00:00:00+0000"),
    OnlyInChanged_R1("2005-01-01T00:00:00+0000"),

    FIRST_ANALYSIS {
      @Override
      Date getDate() {
        return getAnalysisDate(l -> {
          if (l.isEmpty()) {
            return Optional.empty();
          }
          return Optional.of(l.get(l.size() - 1));
        });
      }
    },
    LATEST_ANALYSIS {
      @Override
      Date getDate() {
        return getAnalysisDate(l -> {
          if (l.size() > 0) {
            return Optional.of(l.get(0));
          }
          return Optional.empty();
        });
      }
    };

    private final Date date;

    private IssueCreationDate() {
      this.date = null;
    }

    private IssueCreationDate(String date) {
      this.date = dateParse(date);
    }

    Date getDate() {
      return date;
    }

    private static Date getAnalysisDate(Function<List<ProjectAnalyses.Analysis>, Optional<ProjectAnalyses.Analysis>> chooseItem) {
      return Optional.of(
        ItUtils.newWsClient(ORCHESTRATOR)
          .projectAnanlysis()
          .search(SearchRequest.builder().setProject(SAMPLE_PROJECT_KEY).build())
          .getAnalysesList())
        .flatMap(chooseItem)
        .map(ProjectAnalyses.Analysis::getDate)
        .map(IssueCreationDateTest::dateParse)
        .orElseThrow(() -> new IllegalStateException("There is no analysis"));
    }
  }

  private Server server = ORCHESTRATOR.getServer();

  @Before
  public void resetData() {
    ORCHESTRATOR.resetData();
    server.provisionProject(SAMPLE_PROJECT_KEY, SAMPLE_PROJECT_NAME);
  }

  @Test
  public void should_use_scm_date_for_new_issues_if_scm_is_available() {
    analysis(Scm.ON_, QProfile.ONE_RULE, SourceCode.INITIAL);

    assertNumberOfIssues(3);
    assertIssueCreationDate(Component.OnlyInInitial, IssueCreationDate.OnlyInInitial_R1);
    assertIssueCreationDate(Component.ForeverAndModified, IssueCreationDate.ForeverAndModified_R1);
    assertIssueCreationDate(Component.ForeverAndUnmodified, IssueCreationDate.ForeverAndUnmodified_R1);
  }

  @Test
  public void should_use_analysis_date_for_new_issues_if_scm_is_not_available() {
    analysis(Scm.OFF, QProfile.ONE_RULE, SourceCode.INITIAL);

    assertNumberOfIssues(3);
    Stream.of(FILES_OF_SOURCE_INITIAL)
      .forEach(component -> {
        assertIssueCreationDate(component, IssueCreationDate.FIRST_ANALYSIS);
      });
  }

  @Test
  public void no_rules_no_issues_if_scm_is_available() {
    analysis(Scm.ON_, QProfile.NO_RULES, SourceCode.INITIAL);
    assertNoIssue();
  }

  @Test
  public void no_rules_no_issues_if_scm_is_not_available() {
    analysis(Scm.OFF, QProfile.NO_RULES, SourceCode.INITIAL);
    assertNoIssue();
  }

  @Test
  public void use_scm_date_for_issues_raised_by_new_rules_if_scm_is_newly_available() {
    analysis(Scm.OFF, QProfile.NO_RULES, SourceCode.INITIAL);
    analysis(Scm.ON_, QProfile.ONE_RULE, SourceCode.CHANGED);

    assertNumberOfIssues(3);
    assertIssueCreationDate(Component.ForeverAndModified, IssueCreationDate.ForeverAndModified_R2);
    assertIssueCreationDate(Component.ForeverAndUnmodified, IssueCreationDate.ForeverAndUnmodified_R1);
    assertIssueCreationDate(Component.OnlyInChanged, IssueCreationDate.OnlyInChanged_R1);
  }

  @Test
  public void use_scm_date_for_issues_raised_by_new_rules_if_scm_is_available_and_ever_has_been_available() {
    analysis(Scm.ON_, QProfile.NO_RULES, SourceCode.INITIAL);
    analysis(Scm.ON_, QProfile.ONE_RULE, SourceCode.CHANGED);

    assertNumberOfIssues(3);
    assertIssueCreationDate(Component.ForeverAndModified, IssueCreationDate.ForeverAndModified_R2);
    assertIssueCreationDate(Component.ForeverAndUnmodified, IssueCreationDate.ForeverAndUnmodified_R1);
    assertIssueCreationDate(Component.OnlyInChanged, IssueCreationDate.OnlyInChanged_R1);
  }

  @Test
  public void use_analysis_date_for_issues_raised_by_new_rules_if_scm_is_not_available() {
    analysis(Scm.OFF, QProfile.NO_RULES, SourceCode.INITIAL);
    analysis(Scm.OFF, QProfile.ONE_RULE, SourceCode.CHANGED);

    assertNumberOfIssues(3);
    Stream.of(FILES_OF_SOURCE_CHANGED)
      .forEach(component -> {
        assertIssueCreationDate(component, IssueCreationDate.LATEST_ANALYSIS);
      });
  }

  @Test
  public void do_not_change_the_date_of_an_existing_issue_if_the_blame_information_changes() {
    analysis(Scm.ON_, QProfile.ONE_RULE, SourceCode.INITIAL);
    analysis(Scm.ON_, QProfile.ONE_RULE, SourceCode.CHANGED);

    assertIssueCreationDate(Component.ForeverAndModified, IssueCreationDate.ForeverAndModified_R1);
  }

  private void analysis(Scm scm, QProfile qProfile, SourceCode sourceCode) {
    server.restoreProfile(FileLocation.ofClasspath(qProfile.path));
    server.associateProjectToQualityProfile(SAMPLE_PROJECT_KEY, LANGUAGE_XOO, qProfile.name);

    SonarScanner scanner = SonarScanner.create(projectDir(sourceCode.path));
    scm.configure(scanner);
    ORCHESTRATOR.executeBuild(scanner);
  }

  private static void assertNoIssue() {
    assertNumberOfIssues(0);
  }

  private static void assertNumberOfIssues(int number) {
    assertThat(getIssues(IssueQuery.create())).hasSize(number);
  }

  private static void assertIssueCreationDate(Component component, IssueCreationDate expectedDate) {
    assertThat(getIssues(IssueQuery.create().components(component.getKey())))
      .extracting(Issue::creationDate)
      .containsExactly(expectedDate.getDate());
  }

  private static List<Issue> getIssues(IssueQuery query) {
    return issueClient().find(query).list();
  }

  private static Date dateParse(String expectedDate) {
    try {
      return new SimpleDateFormat(DATETIME_FORMAT).parse(expectedDate);
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }
}
