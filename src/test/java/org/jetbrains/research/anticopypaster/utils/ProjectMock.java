package org.jetbrains.research.anticopypaster.utils;

import com.intellij.openapi.project.Project;
import org.jetbrains.research.anticopypaster.config.ProjectSettingsState;
import org.mockito.Mock;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Class providing a mocked IntelliJ project whose only functionality is returning a provided
 * {@code ProjectSettingsState}. More lightweight than simulating a full IntelliJ project.
 */
public class ProjectMock {

    @Mock
    private Project mockProject;

    public ProjectMock(ProjectSettingsState settings) {
        mockProject = mock(Project.class);
        // Required for ProjectSettingsState.getInstance to work
        when(mockProject.getService(eq(ProjectSettingsState.class))).thenReturn(settings);
    }

    public Project getMock() {
        return mockProject;
    }
}
