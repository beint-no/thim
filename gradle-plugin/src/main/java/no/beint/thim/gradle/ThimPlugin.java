package no.beint.thim.gradle;

import com.google.devtools.ksp.gradle.KspAATask;
import com.google.devtools.ksp.gradle.KspExtension;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileTree;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SourceSet;

import javax.lang.model.SourceVersion;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ThimPlugin implements Plugin<Project> {
    private static final String KSP_VERSION = "2.3.10";
    private static final String KOTLIN_VERSION = "2.3.20";

    @Override
    public void apply(Project project) {
        var extension = project.getExtensions().create("thim", ThimExtension.class);
        extension.getTemplates().convention(project.getLayout().getProjectDirectory().dir("src/main/resources/templates"));
        extension.getMessages().convention(project.getLayout().getProjectDirectory().dir("src/main/resources"));
        extension.getGeneratedPackage().convention(project.provider(() -> generatedPackage(project)));
        extension.getRegistryName().convention("ThimTemplates");
        extension.getModelPackages().convention(project.provider(() -> java.util.List.of(defaultModelPackage(project))));
        extension.getStrictTemplates().convention(false);
        extension.getFailOnUnusedMessages().convention(false);
        extension.getFailOnUnusedFragments().convention(false);
        extension.getValidateRoutes().convention(true);
        extension.getExternalPaths().convention(java.util.List.of());
        extension.getStrictModels().convention(false);
        extension.getFailOnUnusedProperties().convention(false);
        extension.getForbiddenModelAnnotations().convention(java.util.List.of(
                "jakarta.persistence.Entity", "jakarta.persistence.Embeddable", "jakarta.persistence.MappedSuperclass",
                "javax.persistence.Entity", "javax.persistence.Embeddable", "javax.persistence.MappedSuperclass"));

        project.getPluginManager().withPlugin("org.jetbrains.kotlin.jvm", ignored -> configureKotlinProject(project, extension));
        project.getPluginManager().withPlugin("java", ignored -> project.afterEvaluate(evaluated -> {
            if (!project.getPluginManager().hasPlugin("org.jetbrains.kotlin.jvm")) {
                configureJavaProject(project, extension);
            }
        }));
    }

    private void configureKotlinProject(Project project, ThimExtension extension) {
        project.getPluginManager().apply("com.google.devtools.ksp");
        var version = ThimPlugin.class.getPackage().getImplementationVersion();
        if (version == null) {
            version = project.getVersion().toString();
        }
        project.getDependencies().add("implementation", "no.beint.thim:spring:" + version);
        project.getDependencies().add("ksp", "no.beint.thim:compiler:" + version);

        var ksp = project.getExtensions().getByType(KspExtension.class);
        ksp.arg("thim.templates", extension.getTemplates().map(directory -> directory.getAsFile().getAbsolutePath()));
        ksp.arg("thim.messages", extension.getMessages().map(directory -> directory.getAsFile().getAbsolutePath()));
        ksp.arg("thim.package", extension.getGeneratedPackage());
        ksp.arg("thim.registry", extension.getRegistryName());
        ksp.arg("thim.modelPackages", extension.getModelPackages().map(packages -> String.join(",", packages)));
        ksp.arg("thim.strictTemplates", extension.getStrictTemplates().map(String::valueOf));
        ksp.arg("thim.failOnUnusedMessages", extension.getFailOnUnusedMessages().map(String::valueOf));
        ksp.arg("thim.failOnUnusedFragments", extension.getFailOnUnusedFragments().map(String::valueOf));
        ksp.arg("thim.validateRoutes", extension.getValidateRoutes().map(String::valueOf));
        ksp.arg("thim.externalPaths", extension.getExternalPaths().map(paths -> String.join(",", paths)));
        ksp.arg("thim.strictModels", extension.getStrictModels().map(String::valueOf));
        ksp.arg("thim.failOnUnusedProperties", extension.getFailOnUnusedProperties().map(String::valueOf));
        ksp.arg("thim.forbiddenModelAnnotations", extension.getForbiddenModelAnnotations().map(names -> String.join(",", names)));

        project.getTasks().withType(KspAATask.class).configureEach(task -> {
            task.getInputs().files(extension.getTemplates().map(directory -> htmlFiles(project, directory.getAsFile())))
                    .withPropertyName("thimTemplates")
                    .withPathSensitivity(PathSensitivity.RELATIVE);
            task.getInputs().files(extension.getMessages().map(directory -> propertyFiles(project, directory.getAsFile())))
                    .withPropertyName("thimMessages")
                    .withPathSensitivity(PathSensitivity.RELATIVE);
        });

        var kspKotlinTasks = project.getTasks().withType(KspAATask.class)
                .matching(task -> task.getName().equals("kspKotlin"));
        project.getTasks().register("thimCheck", task -> {
            task.setGroup("verification");
            task.setDescription("Validates Thim templates with the production compiler");
            task.dependsOn(kspKotlinTasks);
        });

        var generatedResources = project.getLayout().getBuildDirectory().dir("generated/ksp/main/resources");
        project.getTasks().matching(task -> task.getName().equals("bootRun")).configureEach(task -> {
            task.dependsOn("kspKotlin");
            if (task instanceof JavaExec javaExec) {
                javaExec.classpath(generatedResources);
            }
        });
    }

    private void configureJavaProject(Project project, ThimExtension extension) {
        var version = implementationVersion(project);
        project.getDependencies().add("implementation", "no.beint.thim:spring:" + version);

        var runner = dependencyConfiguration(project, "thimCompilerRuntime");
        project.getDependencies().add(runner.getName(), "com.google.devtools.ksp:symbol-processing-aa-embeddable:" + KSP_VERSION);
        project.getDependencies().add(runner.getName(), "com.google.devtools.ksp:symbol-processing-common-deps:" + KSP_VERSION);
        project.getDependencies().add(runner.getName(), "com.google.devtools.ksp:symbol-processing-api:" + KSP_VERSION);
        project.getDependencies().add(runner.getName(), "org.jetbrains.kotlin:kotlin-stdlib:" + KOTLIN_VERSION);
        project.getDependencies().add(runner.getName(), "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2");

        var processor = dependencyConfiguration(project, "thimProcessor");
        project.getDependencies().add(processor.getName(), "no.beint.thim:compiler:" + version);

        var sourceSets = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
        var main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        var modelSourceDirectories = new ArrayList<>(main.getJava().getSrcDirs());
        var generatedBase = project.getLayout().getBuildDirectory().dir("generated/thim/main");
        var compileThim = project.getTasks().register("compileThim", ThimCompile.class, task -> {
            task.setGroup("build");
            task.setDescription("Compiles typed HTML templates into Java renderers");
            configureThimTask(project, extension, main, modelSourceDirectories, runner, processor, task, generatedBase, "main");
        });
        var checkBase = project.getLayout().getBuildDirectory().dir("generated/thim/check");
        project.getTasks().register("thimCheck", ThimCompile.class, task -> {
            task.setGroup("verification");
            task.setDescription("Validates Thim templates with the production compiler");
            configureThimTask(project, extension, main, modelSourceDirectories, runner, processor, task, checkBase, "check");
        });

        main.getJava().srcDir(compileThim.flatMap(ThimCompile::getJavaOutput));
        main.getResources().srcDir(compileThim.flatMap(ThimCompile::getResourceOutput));

        project.getTasks().named(JavaPlugin.COMPILE_JAVA_TASK_NAME).configure(task -> task.dependsOn(compileThim));
        project.getTasks().named(JavaPlugin.PROCESS_RESOURCES_TASK_NAME).configure(task -> task.dependsOn(compileThim));
    }

    private void configureThimTask(
            Project project,
            ThimExtension extension,
            SourceSet main,
            List<File> modelSourceDirectories,
            Configuration runner,
            Configuration processor,
            ThimCompile task,
            Provider<Directory> generatedBase,
            String purpose
    ) {
        task.getModelSources().from(modelSourceDirectories);
        task.getTemplates().set(extension.getTemplates());
        task.getMessages().set(extension.getMessages());
        task.getRunnerClasspath().from(runner);
        task.getProcessorClasspath().from(processor);
        task.getLibraries().from(main.getCompileClasspath());
        task.getGeneratedPackage().set(extension.getGeneratedPackage());
        task.getRegistryName().set(extension.getRegistryName());
        task.getModelPackages().set(extension.getModelPackages());
        task.getStrictTemplates().set(extension.getStrictTemplates());
        task.getFailOnUnusedMessages().set(extension.getFailOnUnusedMessages());
        task.getFailOnUnusedFragments().set(extension.getFailOnUnusedFragments());
        task.getValidateRoutes().set(extension.getValidateRoutes());
        task.getExternalPaths().set(extension.getExternalPaths());
        task.getStrictModels().set(extension.getStrictModels());
        task.getFailOnUnusedProperties().set(extension.getFailOnUnusedProperties());
        task.getForbiddenModelAnnotations().set(extension.getForbiddenModelAnnotations());
        task.getModuleName().set(project.getName() + "-" + purpose);
        task.getJdkHome().set(System.getProperty("java.home"));
        task.getProjectBase().set(project.getLayout().getProjectDirectory());
        task.getOutputBase().set(generatedBase);
        task.getJavaOutput().set(generatedBase.map(directory -> directory.dir("java")));
        task.getKotlinOutput().set(generatedBase.map(directory -> directory.dir("kotlin")));
        task.getResourceOutput().set(generatedBase.map(directory -> directory.dir("resources")));
        task.getClassOutput().set(generatedBase.map(directory -> directory.dir("classes")));
        task.getCaches().set(project.getLayout().getBuildDirectory().dir("thim/" + purpose + "/caches"));
        task.getReportFile().set(project.getLayout().getBuildDirectory().file("reports/thim/" + purpose + ".json"));
        task.getEmptyKotlinSources().set(project.getLayout().getBuildDirectory().dir("thim/" + purpose + "/empty-kotlin"));
    }

    private Configuration dependencyConfiguration(Project project, String name) {
        var configuration = project.getConfigurations().create(name);
        configuration.setCanBeConsumed(false);
        configuration.setCanBeResolved(true);
        return configuration;
    }

    private String implementationVersion(Project project) {
        var version = ThimPlugin.class.getPackage().getImplementationVersion();
        return version == null ? project.getVersion().toString() : version;
    }

    private FileTree htmlFiles(Project project, java.io.File directory) {
        return project.fileTree(directory, files -> files.include("**/*.html"));
    }

    private FileTree propertyFiles(Project project, java.io.File directory) {
        return project.fileTree(directory, files -> files.include("**/*.properties"));
    }

    private String generatedPackage(Project project) {
        var group = project.getGroup().toString();
        var prefix = group.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*") ? group + "." : "";
        var projectPath = project.getPath().replaceFirst("^:", "").replace(':', '.');
        if (projectPath.isEmpty()) {
            projectPath = project.getName();
        }
        var identifiers = projectPath.split("\\.");
        var packageName = new StringBuilder(prefix);
        for (var identifier : identifiers) {
            if (!packageName.isEmpty() && packageName.charAt(packageName.length() - 1) != '.') {
                packageName.append('.');
            }
            packageName.append(javaIdentifier(identifier));
        }
        return packageName.append(".thim.generated").toString();
    }

    private String defaultModelPackage(Project project) {
        var group = project.getGroup().toString();
        return group.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*") ? group + ".page" : "page";
    }

    private String javaIdentifier(String value) {
        var output = new StringBuilder(value.length());
        value.toLowerCase(Locale.ROOT).codePoints().forEach(character -> {
            if (Character.isJavaIdentifierPart(character)) {
                output.appendCodePoint(character);
            } else {
                output.append('_');
            }
        });
        if (output.isEmpty() || !Character.isJavaIdentifierStart(output.codePointAt(0)) || SourceVersion.isKeyword(output)) {
            output.insert(0, '_');
        }
        return output.toString();
    }
}
