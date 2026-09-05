package no.beint.thim.gradle;

import org.gradle.api.IsolatedAction;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.initialization.ProjectDescriptor;
import org.gradle.api.initialization.Settings;

import java.util.ArrayList;
import java.util.List;

public final class ThimSettingsPlugin implements Plugin<Settings> {
    @Override
    public void apply(Settings settings) {
        settings.getGradle().settingsEvaluated(evaluated -> {
            var paths = new ArrayList<String>();
            collectPaths(evaluated.getRootProject(), paths);
            evaluated.getGradle().getLifecycle().beforeProject(new ConfigureProjects(List.copyOf(paths)));
        });
    }

    private static void collectPaths(ProjectDescriptor project, List<String> paths) {
        paths.add(project.getPath());
        project.getChildren().stream().sorted(java.util.Comparator.comparing(ProjectDescriptor::getPath))
                .forEach(child -> collectPaths(child, paths));
    }

    private record ConfigureProjects(List<String> paths) implements IsolatedAction<Project> {
        @Override
        public void execute(Project project) {
            project.getPluginManager().apply(ThimUsagePlugin.class);
            if (project.getPath().equals(":")) {
                ThimUsagePlugin.configureAggregation(project, paths);
            }
        }
    }
}
