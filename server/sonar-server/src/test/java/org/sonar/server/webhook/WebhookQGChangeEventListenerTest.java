/*
 * SonarQube
 * Copyright (C) 2009-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
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
package org.sonar.server.webhook;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.sonar.api.config.Configuration;
import org.sonar.api.utils.System2;
import org.sonar.core.util.UuidFactoryFast;
import org.sonar.db.DbClient;
import org.sonar.db.DbTester;
import org.sonar.db.component.AnalysisPropertyDto;
import org.sonar.db.component.BranchDto;
import org.sonar.db.component.BranchType;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.SnapshotDto;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.server.qualitygate.EvaluatedQualityGate;
import org.sonar.server.qualitygate.QualityGate;
import org.sonar.server.qualitygate.ShortLivingBranchQualityGate;
import org.sonar.server.qualitygate.changeevent.QGChangeEvent;
import org.sonar.server.qualitygate.changeevent.QGChangeEventListener;

import static java.lang.String.valueOf;
import static java.util.Collections.emptySet;
import static org.apache.commons.lang.RandomStringUtils.randomAlphanumeric;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;
import static org.sonar.core.util.stream.MoreCollectors.toArrayList;
import static org.sonar.db.component.BranchType.LONG;
import static org.sonar.db.component.ComponentTesting.newBranchDto;
import static org.sonar.db.component.ComponentTesting.newPrivateProjectDto;

public class WebhookQGChangeEventListenerTest {

  private static final EvaluatedQualityGate EVALUATED_QUALITY_GATE_1 = EvaluatedQualityGate.newBuilder()
    .setQualityGate(new QualityGate(valueOf(ShortLivingBranchQualityGate.ID), ShortLivingBranchQualityGate.NAME, emptySet()))
    .setStatus(EvaluatedQualityGate.Status.OK)
    .build();
  private static final Set<QGChangeEventListener.ChangedIssue> CHANGED_ISSUES_ARE_IGNORED = emptySet();

  @Rule
  public DbTester dbTester = DbTester.create(System2.INSTANCE);

  private DbClient dbClient = dbTester.getDbClient();

  private WebHooks webHooks = mock(WebHooks.class);
  private WebhookPayloadFactory webhookPayloadFactory = mock(WebhookPayloadFactory.class);
  private DbClient spiedOnDbClient = Mockito.spy(dbClient);
  private WebhookQGChangeEventListener underTest = new WebhookQGChangeEventListener(webHooks, webhookPayloadFactory, spiedOnDbClient);
  private DbClient mockedDbClient = mock(DbClient.class);
  private WebhookQGChangeEventListener mockedUnderTest = new WebhookQGChangeEventListener(webHooks, webhookPayloadFactory, mockedDbClient);

  @Test
  public void onIssueChanges_has_no_effect_if_no_webhook_is_configured() {
    Configuration configuration1 = mock(Configuration.class);
    mockWebhookDisabled(configuration1);
    QGChangeEvent qualityGateEvent = new QGChangeEvent(new ComponentDto(), new BranchDto(), new SnapshotDto(), configuration1, Optional::empty);

    mockedUnderTest.onIssueChanges(qualityGateEvent, CHANGED_ISSUES_ARE_IGNORED);

    verify(webHooks).isEnabled(configuration1);
    verifyZeroInteractions(webhookPayloadFactory, mockedDbClient);
  }

  @Test
  public void onIssueChanges_calls_webhook_for_changeEvent_with_webhook_enabled() {
    OrganizationDto organization = dbTester.organizations().insert();
    ComponentDto project = dbTester.components().insertPublicProject(organization);
    ComponentAndBranch branch = insertProjectBranch(project, BranchType.SHORT, "foo");
    SnapshotDto analysis = insertAnalysisTask(branch);
    Configuration configuration = mock(Configuration.class);
    mockWebhookEnabled(configuration);
    mockPayloadSupplierConsumedByWebhooks();
    Map<String, String> properties = new HashMap<>();
    properties.put("sonar.analysis.test1", randomAlphanumeric(50));
    properties.put("sonar.analysis.test2", randomAlphanumeric(5000));
    insertPropertiesFor(analysis.getUuid(), properties);
    QGChangeEvent qualityGateEvent = newQGChangeEvent(branch, analysis, configuration, EVALUATED_QUALITY_GATE_1);

    underTest.onIssueChanges(qualityGateEvent, CHANGED_ISSUES_ARE_IGNORED);

    ProjectAnalysis projectAnalysis = verifyWebhookCalledAndExtractPayloadFactoryArgument(branch, configuration, analysis);
    assertThat(projectAnalysis).isEqualTo(
      new ProjectAnalysis(
        new Project(project.uuid(), project.getKey(), project.name()),
        null,
        new Analysis(analysis.getUuid(), analysis.getCreatedAt()),
        new Branch(false, "foo", Branch.Type.SHORT),
        EVALUATED_QUALITY_GATE_1,
        null,
        properties));
  }

  @Test
  public void onIssueChanges_calls_webhook_on_main_branch() {
    OrganizationDto organization = dbTester.organizations().insert();
    ComponentAndBranch mainBranch = insertMainBranch(organization);
    SnapshotDto analysis = insertAnalysisTask(mainBranch);
    Configuration configuration = mock(Configuration.class);
    mockWebhookEnabled(configuration);
    QGChangeEvent qualityGateEvent = newQGChangeEvent(mainBranch, analysis, configuration, EVALUATED_QUALITY_GATE_1);

    underTest.onIssueChanges(qualityGateEvent, CHANGED_ISSUES_ARE_IGNORED);

    verifyWebhookCalled(mainBranch, analysis, configuration);
  }

  @Test
  public void onIssueChanges_calls_webhook_on_long_branch() {
    onIssueChangesCallsWebhookOnBranch(BranchType.LONG);
  }

  @Test
  public void onIssueChanges_calls_webhook_on_short_branch() {
    onIssueChangesCallsWebhookOnBranch(BranchType.SHORT);
  }

  public void onIssueChangesCallsWebhookOnBranch(BranchType branchType) {
    OrganizationDto organization = dbTester.organizations().insert();
    ComponentAndBranch mainBranch = insertMainBranch(organization);
    ComponentAndBranch longBranch = insertProjectBranch(mainBranch.component, branchType, "foo");
    SnapshotDto analysis = insertAnalysisTask(longBranch);
    Configuration configuration = mock(Configuration.class);
    mockWebhookEnabled(configuration);
    QGChangeEvent qualityGateEvent = newQGChangeEvent(longBranch, analysis, configuration, null);

    underTest.onIssueChanges(qualityGateEvent, CHANGED_ISSUES_ARE_IGNORED);

    verifyWebhookCalled(longBranch, analysis, configuration);
  }

  private void mockWebhookEnabled(Configuration... configurations) {
    for (Configuration configuration : configurations) {
      when(webHooks.isEnabled(configuration)).thenReturn(true);
    }
  }

  private void mockWebhookDisabled(Configuration... configurations) {
    for (Configuration configuration : configurations) {
      when(webHooks.isEnabled(configuration)).thenReturn(false);
    }
  }

  private void mockPayloadSupplierConsumedByWebhooks() {
    Mockito.doAnswer(invocationOnMock -> {
      Supplier<WebhookPayload> supplier = (Supplier<WebhookPayload>) invocationOnMock.getArguments()[2];
      supplier.get();
      return null;
    }).when(webHooks)
      .sendProjectAnalysisUpdate(Matchers.any(Configuration.class), Matchers.any(), Matchers.any());
  }

  private void insertPropertiesFor(String snapshotUuid, Map<String, String> properties) {
    List<AnalysisPropertyDto> analysisProperties = properties.entrySet().stream()
      .map(entry -> new AnalysisPropertyDto()
        .setUuid(UuidFactoryFast.getInstance().create())
        .setSnapshotUuid(snapshotUuid)
        .setKey(entry.getKey())
        .setValue(entry.getValue()))
      .collect(toArrayList(properties.size()));
    dbTester.getDbClient().analysisPropertiesDao().insert(dbTester.getSession(), analysisProperties);
    dbTester.getSession().commit();
  }

  private SnapshotDto insertAnalysisTask(ComponentAndBranch componentAndBranch) {
    return dbTester.components().insertSnapshot(componentAndBranch.component);
  }

  private ProjectAnalysis verifyWebhookCalledAndExtractPayloadFactoryArgument(ComponentAndBranch componentAndBranch, Configuration configuration, SnapshotDto analysis) {
    verifyWebhookCalled(componentAndBranch, analysis, configuration);

    return extractPayloadFactoryArguments(1).iterator().next();
  }

  private void verifyWebhookCalled(ComponentAndBranch componentAndBranch, SnapshotDto analysis, Configuration branchConfiguration) {
    verify(webHooks).isEnabled(branchConfiguration);
    verify(webHooks).sendProjectAnalysisUpdate(
      Matchers.same(branchConfiguration),
      Matchers.eq(new WebHooks.Analysis(componentAndBranch.uuid(), analysis.getUuid(), null)),
      any(Supplier.class));
  }

  private void verifyWebhookNotCalled(ComponentAndBranch componentAndBranch, SnapshotDto analysis, Configuration branchConfiguration) {
    verify(webHooks).isEnabled(branchConfiguration);
    verify(webHooks, times(0)).sendProjectAnalysisUpdate(
      Matchers.same(branchConfiguration),
      Matchers.eq(new WebHooks.Analysis(componentAndBranch.uuid(), analysis.getUuid(), null)),
      any(Supplier.class));
  }

  private List<ProjectAnalysis> extractPayloadFactoryArguments(int time) {
    ArgumentCaptor<ProjectAnalysis> projectAnalysisCaptor = ArgumentCaptor.forClass(ProjectAnalysis.class);
    verify(webhookPayloadFactory, Mockito.times(time)).create(projectAnalysisCaptor.capture());
    return projectAnalysisCaptor.getAllValues();
  }

  private ComponentAndBranch insertPrivateBranch(OrganizationDto organization, BranchType branchType) {
    ComponentDto project = dbTester.components().insertPrivateProject(organization);
    BranchDto branchDto = newBranchDto(project.projectUuid(), branchType)
      .setKey("foo");
    ComponentDto newComponent = dbTester.components().insertProjectBranch(project, branchDto);
    return new ComponentAndBranch(newComponent, branchDto);
  }

  public ComponentAndBranch insertMainBranch(OrganizationDto organization) {
    ComponentDto project = newPrivateProjectDto(organization);
    BranchDto branch = newBranchDto(project, LONG).setKey("master");
    dbTester.components().insertComponent(project);
    dbClient.branchDao().insert(dbTester.getSession(), branch);
    dbTester.commit();
    return new ComponentAndBranch(project, branch);
  }

  public ComponentAndBranch insertProjectBranch(ComponentDto project, BranchType type, String branchKey) {
    BranchDto branchDto = newBranchDto(project.projectUuid(), type).setKey(branchKey);
    ComponentDto newComponent = dbTester.components().insertProjectBranch(project, branchDto);
    return new ComponentAndBranch(newComponent, branchDto);
  }

  private static class ComponentAndBranch {
    private final ComponentDto component;

    private final BranchDto branch;

    private ComponentAndBranch(ComponentDto component, BranchDto branch) {
      this.component = component;
      this.branch = branch;
    }

    public ComponentDto getComponent() {
      return component;
    }

    public BranchDto getBranch() {
      return branch;
    }

    public String uuid() {
      return component.uuid();
    }

  }

  private static QGChangeEvent newQGChangeEvent(ComponentAndBranch branch, SnapshotDto analysis, Configuration configuration, @Nullable EvaluatedQualityGate evaluatedQualityGate) {
    return new QGChangeEvent(branch.component, branch.branch, analysis, configuration, () -> Optional.ofNullable(evaluatedQualityGate));
  }

}
