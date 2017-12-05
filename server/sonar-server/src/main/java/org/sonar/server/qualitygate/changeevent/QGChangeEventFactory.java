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
package org.sonar.server.qualitygate.changeevent;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Objects;
import org.sonar.core.issue.DefaultIssue;
import org.sonar.db.component.ComponentDto;

public interface QGChangeEventFactory {

  final class IssueChangeData {
    private final List<DefaultIssue> issues;
    private final List<ComponentDto> components;

    public IssueChangeData(List<DefaultIssue> issues, List<ComponentDto> components) {
      this.issues = ImmutableList.copyOf(issues);
      this.components = ImmutableList.copyOf(components);
    }

    public List<DefaultIssue> getIssues() {
      return issues;
    }

    public List<ComponentDto> getComponents() {
      return components;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      IssueChangeData that = (IssueChangeData) o;
      return Objects.equals(issues, that.issues) &&
        Objects.equals(components, that.components);
    }

    @Override
    public int hashCode() {
      return Objects.hash(issues, components);
    }

    @Override
    public String toString() {
      return "IssueChangeData{" +
        "issues=" + issues +
        ", components=" + components +
        '}';
    }
  }
}
