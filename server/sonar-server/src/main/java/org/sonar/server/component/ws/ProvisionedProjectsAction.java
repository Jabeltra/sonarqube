/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
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
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.server.ws.WebService.Param;
import org.sonar.api.utils.text.JsonWriter;
import org.sonar.api.web.UserRole;
import org.sonar.core.component.ComponentDto;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.persistence.MyBatis;
import org.sonar.server.db.DbClient;
import org.sonar.server.es.SearchOptions;
import org.sonar.server.user.UserSession;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class ProvisionedProjectsAction implements ProjectsAction {
  private final DbClient dbClient;

  private static final List<String> POSSIBLE_FIELDS = Arrays.asList("uuid", "key", "name", "creationDate");

  public ProvisionedProjectsAction(DbClient dbClient) {
    this.dbClient = dbClient;
  }

  @Override
  public void define(WebService.NewController controller) {
    WebService.NewAction action = controller
      .createAction("provisioned")
      .setDescription(
        "Get the list of provisioned projects.<br /> " +
          "Require admin role.")
      .setSince("5.2")
      .setResponseExample(Resources.getResource(getClass(), "projects-example-provisioned.json"))
      .setHandler(this)
      .addPagingParams(100)
      .addFieldsParam(POSSIBLE_FIELDS);

    action
      .createParam(Param.TEXT_QUERY)
      .setDescription("UTF-8 search query")
      .setExampleValue("sonar");
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    UserSession.get().checkGlobalPermission(UserRole.ADMIN, "You need admin rights.");
    SearchOptions options = new SearchOptions().setPage(
      request.mandatoryParamAsInt(Param.PAGE),
      request.mandatoryParamAsInt(Param.PAGE_SIZE)
    );
    List<String> desiredFields = desiredFields(request);
    String query = request.param(Param.TEXT_QUERY);

    DbSession dbSession = dbClient.openSession(false);
    try {
      List<ComponentDto> projects = dbClient.componentDao().selectProvisionedProjects(dbSession, options, query);
      int nbOfProjects = dbClient.componentDao().countProvisionedProjects(dbSession, query);
      JsonWriter json = response.newJsonWriter().beginObject();
      writeProjects(projects, json, desiredFields);
      options.writeJson(json, nbOfProjects);
      json.endObject().close();
    } finally {
      MyBatis.closeQuietly(dbSession);
    }
  }

  private void writeProjects(List<ComponentDto> projects, JsonWriter json, List<String> desiredFields) {
    json.name("projects");
    json.beginArray();
    for (ComponentDto project : projects) {
      json.beginObject();
      json.prop("uuid", project.uuid());
      writeIfNeeded(json, "key", project.key(), desiredFields);
      writeIfNeeded(json, "name", project.name(), desiredFields);
      writeIfNeeded(json, "creationDate", project.getCreatedAt(), desiredFields);
      json.endObject();
    }
    json.endArray();
  }

  private void writeIfNeeded(JsonWriter json, String fieldName, String value, List<String> desiredFields) {
    if (desiredFields.contains(fieldName)) {
      json.prop(fieldName, value);
    }
  }

  private void writeIfNeeded(JsonWriter json, String fieldName, Date date, List<String> desiredFields) {
    if (desiredFields.contains(fieldName)) {
      json.propDateTime(fieldName, date);
    }
  }

  private List<String> desiredFields(Request request) {
    List<String> desiredFields = request.paramAsStrings(Param.FIELDS);
    if (desiredFields == null) {
      desiredFields = POSSIBLE_FIELDS;
    }

    return desiredFields;
  }
}
