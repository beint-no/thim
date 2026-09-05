package no.beint.thim.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;

import java.util.List;
import java.util.Map;

public final class ThimUsagePlugin implements Plugin<Project> {
    static final String OUTPUTS = "thimProjectOutputs";
    static final String SOURCES = "thimUsageSources";
    static final String CSS = "thimCssSources";
    static final String RUNTIME_CLASSES = "thimRuntimeCssClasses";

    @Override
    public void apply(Project project) {
        for (var name : List.of(OUTPUTS, SOURCES, CSS, RUNTIME_CLASSES)) {
            project.getConfigurations().consumable(name);
        }
        project.getArtifacts().add(SOURCES, project.getLayout().getProjectDirectory().dir("src/main"));
        project.getPluginManager().withPlugin("java", ignored -> project.afterEvaluate(evaluated -> {
            var main = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets()
                    .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            for (var output : main.getOutput().getFiles()) {
                project.getArtifacts().add(OUTPUTS, output, artifact -> artifact.builtBy(main.getOutput()));
            }
        }));
    }

    static void configureAggregation(Project project, List<String> paths) {
        var outputs = aggregate(project, OUTPUTS, paths);
        var sources = aggregate(project, SOURCES, paths);
        var css = aggregate(project, CSS, paths);
        var runtimeClasses = aggregate(project, RUNTIME_CLASSES, paths);
        project.getTasks().register("thimMessageUsageCheck", ThimMessageUsageCheck.class, task -> {
            task.setGroup("verification");
            task.setDescription("Detects unused Thim messages across production project classes");
            task.getProjectOutputs().from(outputs);
            task.getReportFile().set(project.getLayout().getBuildDirectory().file("reports/thim/message-usage.json"));
        });
        project.getTasks().register("thimCssUsageCheck", ThimCssUsageCheck.class, task -> {
            task.setGroup("verification");
            task.setDescription("Detects unused first-party CSS across production source trees");
            task.getRootDirectory().set(project.getLayout().getProjectDirectory());
            task.getCssFiles().from(css.getAsFileTree().matching(pattern -> {
                pattern.include("**/*.css");
                pattern.exclude("**/vendor/**", "**/node_modules/**");
            }));
            task.getUsageFiles().from(sources.getAsFileTree().matching(pattern -> {
                pattern.include("**/*.html", "**/*.htm", "**/*.java", "**/*.kt", "**/*.kts",
                        "**/*.js", "**/*.jsx", "**/*.mjs", "**/*.cjs", "**/*.ts", "**/*.tsx");
                pattern.exclude("**/node_modules/**");
            }));
            task.getRuntimeClassFiles().from(runtimeClasses);
            task.getRuntimeClasses().convention(List.of());
            task.getReportFile().set(project.getLayout().getBuildDirectory().file("reports/thim/css-usage.json"));
        });
    }

    public static void configureCss(Project project, ThimExtension extension) {
        var runtimeClasses = project.getTasks().register("thimCssRuntimeClasses", ThimCssRuntimeClasses.class, task -> {
            task.getClasses().set(extension.getRuntimeCssClasses());
            task.getOutputFile().set(project.getLayout().getBuildDirectory().file("thim/runtime-css-classes.txt"));
        });
        project.getArtifacts().add(RUNTIME_CLASSES, runtimeClasses.flatMap(ThimCssRuntimeClasses::getOutputFile));
        project.afterEvaluate(evaluated -> {
            if (extension.getFailOnUnusedCss().get()) {
                project.getArtifacts().add(CSS, extension.getCss());
            }
            for (var usage : extension.getCssUsage().getFiles()) {
                project.getArtifacts().add(SOURCES, usage, artifact -> artifact.builtBy(extension.getCssUsage()));
            }
        });
    }

    private static Configuration aggregate(Project project, String outgoing, List<String> paths) {
        var dependencies = project.getConfigurations().dependencyScope(outgoing + "Dependencies").get();
        var configuration = project.getConfigurations().resolvable(outgoing + "Aggregate").get();
        configuration.extendsFrom(dependencies);
        configuration.setTransitive(false);
        for (var path : paths) {
            project.getDependencies().add(dependencies.getName(),
                    project.getDependencies().project(Map.of("path", path, "configuration", outgoing)));
        }
        return configuration;
    }
}
