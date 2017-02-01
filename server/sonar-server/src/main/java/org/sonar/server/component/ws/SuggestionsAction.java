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
package org.sonar.server.component.ws;

import com.google.common.io.Resources;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.server.ws.WebService.NewAction;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.server.component.index.ComponentHit;
import org.sonar.server.component.index.ComponentHitsPerQualifier;
import org.sonar.server.component.index.ComponentIndex;
import org.sonar.server.component.index.ComponentIndexQuery;
import org.sonarqube.ws.WsComponents.SuggestionsWsResponse;
import org.sonarqube.ws.WsComponents.SuggestionsWsResponse.ComponentSearchResult;
import org.sonarqube.ws.WsComponents.SuggestionsWsResponse.Qualifier;

import static com.google.common.base.Preconditions.checkState;
import static org.sonar.server.ws.WsUtils.writeProtobuf;
import static org.sonarqube.ws.client.component.ComponentsWsParameters.ACTION_SUGGESTIONS;

public class SuggestionsAction implements ComponentsWsAction {

  private static final String URL_PARAM_QUERY = "s";

  private static final String[] QUALIFIERS = {
    Qualifiers.VIEW,
    Qualifiers.SUBVIEW,
    Qualifiers.PROJECT,
    Qualifiers.MODULE,
    Qualifiers.FILE,
    Qualifiers.UNIT_TEST_FILE
  };

  private static final int NUMBER_OF_RESULTS_PER_QUALIFIER = 6;

  private final ComponentIndex index;

  private DbClient dbClient;

  public SuggestionsAction(DbClient dbClient, ComponentIndex index) {
    this.dbClient = dbClient;
    this.index = index;
  }

  @Override
  public void define(WebService.NewController context) {
    NewAction action = context.createAction(ACTION_SUGGESTIONS)
      .setDescription(
        "Internal WS for the top-right search engine. The result will contain component search results, grouped by their qualifiers.<p>"
          + "Each result contains:"
          + "<ul>"
          + "<li>the organization key</li>"
          + "<li>the component key</li>"
          + "<li>the component's name (unescaped)</li>"
          + "<li>optionally a display name, which puts emphasis to matching characters (this text contains html tags and parts of the html-escaped name)</li>"
          + "</ul>")
      .setSince("4.2")
      .setInternal(true)
      .setHandler(this)
      .setResponseExample(Resources.getResource(this.getClass(), "components-example-suggestions.json"));

    action.createParam(URL_PARAM_QUERY)
      .setRequired(true)
      .setDescription("Substring of project key (minimum 2 characters)")
      .setExampleValue("sonar");
  }

  @Override
  public void handle(Request wsRequest, Response wsResponse) throws Exception {
    SuggestionsWsResponse searchWsResponse = doHandle(wsRequest.param(URL_PARAM_QUERY));
    writeProtobuf(searchWsResponse, wsRequest, wsResponse);
  }

  SuggestionsWsResponse doHandle(String query) {
    List<ComponentHitsPerQualifier> results = searchInIndex(query);
    return createResponse(results);
  }

  private List<ComponentHitsPerQualifier> searchInIndex(String query) {
    return index.search(
      new ComponentIndexQuery(query)
        .setQualifiers(Arrays.asList(QUALIFIERS))
        .setLimit(NUMBER_OF_RESULTS_PER_QUALIFIER));
  }

  private SuggestionsWsResponse createResponse(List<ComponentHitsPerQualifier> hits) {
    return SuggestionsWsResponse.newBuilder()
      .addAllResults(createQualifiers(hits))
      .build();
  }

  private List<Qualifier> createQualifiers(List<ComponentHitsPerQualifier> componentsPerQualifiers) {

    // load all relevant components from database (in a single request)
    Set<String> componentUuids = componentsPerQualifiers
      .stream()
      .flatMap(q -> q.getHits().stream())
      .map(ComponentHit::getUuid)
      .collect(Collectors.toSet());
    Map<String, ComponentDto> componentDtoByUuid = getComponentDtos(componentUuids);

    // load all relevant organizations from database (in a single request)
    Set<String> organizationUuids = componentDtoByUuid
      .values()
      .stream()
      .map(ComponentDto::getOrganizationUuid)
      .collect(Collectors.toSet());
    Map<String, OrganizationDto> organizationDtoByUuid = getOrganizationDtos(organizationUuids);

    return componentsPerQualifiers.stream()
      .map(hitsPerQualifier -> createItems(hitsPerQualifier, componentDtoByUuid, organizationDtoByUuid))
      .collect(Collectors.toList());
  }

  private Map<String, ComponentDto> getComponentDtos(Set<String> componentUuids) {
    List<ComponentDto> componentDtos;
    try (DbSession dbSession = dbClient.openSession(false)) {
      componentDtos = dbClient.componentDao().selectByUuids(dbSession, componentUuids);
    }
    return componentDtos.stream().collect(Collectors.toMap(ComponentDto::uuid, Function.identity()));
  }

  private Map<String, OrganizationDto> getOrganizationDtos(Set<String> organizationUuids) {
    List<OrganizationDto> organizationDtos;
    try (DbSession dbSession = dbClient.openSession(false)) {
      organizationDtos = dbClient.organizationDao().selectByUuids(dbSession, organizationUuids);
    }
    return organizationDtos.stream().collect(Collectors.toMap(OrganizationDto::getUuid, Function.identity()));
  }

  private static Qualifier createItems(ComponentHitsPerQualifier hitsPerQualifier, Map<String, ComponentDto> componentDtoByUuid,
    Map<String, OrganizationDto> organizationDtoByUuid) {
    List<ComponentSearchResult> items = hitsPerQualifier.getHits()
      .stream()
      .map(hit -> createItem(hit, componentDtoByUuid, organizationDtoByUuid))
      .collect(Collectors.toList());

    return Qualifier.newBuilder()
      .setQ(hitsPerQualifier.getQualifier())
      .addAllItems(items)
      .build();
  }

  private static ComponentSearchResult createItem(ComponentHit hit, Map<String, ComponentDto> componentDtoByUuid, Map<String, OrganizationDto> organizationDtoByUuid) {
    String componentUuid = hit.getUuid();
    ComponentDto componentDto = componentDtoByUuid.get(componentUuid);
    checkState(componentDto != null, "Component with uuid '%s' found in index, but not found in database", componentDto);

    String organizationUuid = componentDto.getOrganizationUuid();
    OrganizationDto organizationDto = organizationDtoByUuid.get(organizationUuid);
    checkState(organizationDto != null, "Organization with uuid '%s' not found", organizationUuid);

    return createItem(hit, componentDto, organizationDto);
  }

  private static ComponentSearchResult createItem(ComponentHit hit, ComponentDto componentDto, OrganizationDto organizationDto) {
    ComponentSearchResult.Builder resultBuilder = ComponentSearchResult.newBuilder()
      .setOrganization(organizationDto.getKey())
      .setKey(componentDto.getKey())
      .setName(componentDto.longName());
    hit.getHighlightedText().ifPresent(resultBuilder::setHighlightedText);
    return resultBuilder.build();
  }
}
