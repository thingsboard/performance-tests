package org.thingsboard.tools.service.dashboard;

import org.thingsboard.server.common.data.group.EntityGroup;
import org.thingsboard.server.common.data.id.DashboardId;
import org.thingsboard.server.common.data.role.Role;

public interface DashboardManager {

    void createDashboards() throws Exception;

    void removeDashboards() throws Exception;

    EntityGroup getSharedDashboardsGroup();

    DashboardId getSharedDashboardId();

    Role getReadOnlyRole();
}
