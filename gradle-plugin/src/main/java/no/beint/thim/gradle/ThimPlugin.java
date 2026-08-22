package no.beint.thim.gradle;

import com.google.devtools.ksp.gradle.KspAATask;
import com.google.devtools.ksp.gradle.KspExtension;
import org.gradle.api.GradleException;
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
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.language.jvm.tasks.ProcessResources;

import javax.lang.model.SourceVersion;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ThimPlugin implements Plugin<Project> {
    private static final String KSP_VERSION = "2.3.10";
    private static final String KOTLIN_VERSION = "2.3.20";
    private static final String MESSAGE_USAGE_TASK = "thimMessageUsageCheck";
    private static final String MESSAGE_USAGE_TASK_KEY = "no.beint.thim.messageUsageTask";
    private static final String CSS_USAGE_TASK = "thimCssUsageCheck";
    private static final String CSS_USAGE_TASK_KEY = "no.beint.thim.cssUsageTask";

    @Override
    public void apply(Project project) {
        var extension = project.getExtensions().create("thim", ThimExtension.class);
        extension.getTemplates().convention(project.getLayout().getProjectDirectory().dir("src/main/resources/templates"));
        extension.getMessages().convention(project.getLayout().getProjectDirectory().dir("src/main/resources/i18n"));
        extension.getDefaultLocale().convention("en");
        extension.getSupportedLocales().convention(java.util.List.of());
        extension.getGeneratedPackage().convention(project.provider(() -> generatedPackage(project)));
        extension.getRegistryName().convention("ThimTemplates");
        extension.getModelPackages().convention(project.provider(() -> java.util.List.of(defaultModelPackage(project))));
        extension.getStrictTemplates().convention(true);
        extension.getFailOnUnusedMessages().convention(true);
        extension.getFailOnUnusedFragments().convention(true);
        extension.getValidateRoutes().convention(true);
        extension.getGenerateRoutes().convention(false);
        extension.getRoutesName().convention(extension.getRegistryName().map(name ->
                name.endsWith("Templates") ? name.substring(0, name.length() - "Templates".length()) + "Routes" : name + "Routes"));
        extension.getGenerateMessages().convention(true);
        extension.getMessagesName().convention(extension.getRegistryName().map(name ->
                name.endsWith("Templates") ? name.substring(0, name.length() - "Templates".length()) + "Messages" : name + "Messages"));
        extension.getTrustedPaths().convention(java.util.List.of());
        extension.getStrictModels().convention(true);
        extension.getForbiddenModelAnnotations().convention(java.util.List.of(
                "jakarta.persistence.Entity", "jakarta.persistence.Embeddable", "jakarta.persistence.MappedSuperclass",
                "javax.persistence.Entity", "javax.persistence.Embeddable", "javax.persistence.MappedSuperclass"));
        extension.getCss().convention(project.getLayout().getProjectDirectory().dir("src/main/resources/static"));
        extension.getFailOnUnusedCss().convention(true);
        extension.getRuntimeCssClasses().convention(java.util.List.of("htmx-request", "htmx-indicator"));

        var cssUsageCheck = cssUsageCheck(project, extension);

        project.getPluginManager().withPlugin("org.jetbrains.kotlin.jvm", ignored ->
                configureKotlinProject(project, extension, cssUsageCheck));
        project.getPluginManager().withPlugin("java", ignored -> {
            configureRuntimeDependencies(project);
            configureResourceFiltering(project, extension);
            project.afterEvaluate(evaluated -> {
                if (!project.getPluginManager().hasPlugin("org.jetbrains.kotlin.jvm")) {
                    configureJavaProject(project, extension, cssUsageCheck);
                }
            });
        });
    }

    private void configureKotlinProject(
            Project project,
            ThimExtension extension,
            TaskProvider<ThimCssUsageCheck> cssUsageCheck
    ) {
        project.getPluginManager().apply("com.google.devtools.ksp");
        var version = implementationVersion(project);
        project.getDependencies().add("ksp", "no.beint.thim:compiler:" + version);

        var ksp = project.getExtensions().getByType(KspExtension.class);
        ksp.arg("thim.templates", extension.getTemplates().map(directory -> directory.getAsFile().getAbsolutePath()));
        ksp.arg("thim.messages", extension.getMessages().map(directory -> directory.getAsFile().getAbsolutePath()));
        ksp.arg("thim.defaultLocale", extension.getDefaultLocale());
        ksp.arg("thim.supportedLocales", extension.getSupportedLocales().map(locales -> String.join(",", locales)));
        ksp.arg("thim.package", extension.getGeneratedPackage());
        ksp.arg("thim.registry", extension.getRegistryName());
        ksp.arg("thim.modelPackages", extension.getModelPackages().map(packages -> String.join(",", packages)));
        ksp.arg("thim.strictTemplates", extension.getStrictTemplates().map(String::valueOf));
        ksp.arg("thim.failOnUnusedMessages", extension.getFailOnUnusedMessages().map(String::valueOf));
        ksp.arg("thim.failOnUnusedFragments", extension.getFailOnUnusedFragments().map(String::valueOf));
        ksp.arg("thim.validateRoutes", extension.getValidateRoutes().map(String::valueOf));
        ksp.arg("thim.generateRoutes", extension.getGenerateRoutes().map(String::valueOf));
        ksp.arg("thim.routesName", extension.getRoutesName());
        ksp.arg("thim.generateMessages", extension.getGenerateMessages().map(String::valueOf));
        ksp.arg("thim.messagesName", extension.getMessagesName());
        ksp.arg("thim.catalogId", extension.getMessages().map(directory -> catalogId(project, directory.getAsFile())));
        ksp.arg("thim.trustedPaths", extension.getTrustedPaths().map(paths -> String.join(",", paths)));
        ksp.arg("thim.strictModels", extension.getStrictModels().map(String::valueOf));
        ksp.arg("thim.forbiddenModelAnnotations", extension.getForbiddenModelAnnotations().map(names -> String.join(",", names)));

        project.getTasks().withType(KspAATask.class).configureEach(task -> {
            task.getInputs().files(extension.getTemplates().map(directory -> htmlFiles(project, directory.getAsFile())))
                    .withPropertyName("thimTemplates")
                    .withPathSensitivity(PathSensitivity.RELATIVE);
            task.getInputs().files(extension.getMessages().map(directory -> allFiles(project, directory.getAsFile())))
                    .withPropertyName("thimMessages")
                    .withPathSensitivity(PathSensitivity.RELATIVE);
        });

        var kspKotlinTasks = project.getTasks().withType(KspAATask.class)
                .matching(task -> task.getName().equals("kspKotlin"));
        var messageUsageCheck = messageUsageCheck(project);
        project.getTasks().register("thimCheck", task -> {
            task.setGroup("verification");
            task.setDescription("Validates Thim templates with the production compiler");
            task.dependsOn(kspKotlinTasks, messageUsageCheck, cssUsageCheck);
        });
        project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME).configure(task ->
                task.dependsOn(messageUsageCheck, cssUsageCheck));

        var generatedResources = project.getLayout().getBuildDirectory().dir("generated/ksp/main/resources");
        project.getTasks().matching(task -> task.getName().equals("bootRun")).configureEach(task -> {
            task.dependsOn("kspKotlin");
            if (task instanceof JavaExec javaExec) {
                javaExec.classpath(generatedResources);
            }
        });
    }

    private void configureJavaProject(
            Project project,
            ThimExtension extension,
            TaskProvider<ThimCssUsageCheck> cssUsageCheck
    ) {
        if (extension.getGenerateRoutes().get()) {
            throw new GradleException("Thim route builders currently require the Kotlin JVM plugin");
        }
        var version = implementationVersion(project);

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
        var messageUsageCheck = messageUsageCheck(project);
        project.getTasks().register("thimCheck", ThimCompile.class, task -> {
            task.setGroup("verification");
            task.setDescription("Validates Thim templates with the production compiler");
            configureThimTask(project, extension, main, modelSourceDirectories, runner, processor, task, checkBase, "check");
            task.dependsOn(messageUsageCheck, cssUsageCheck);
        });
        project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME).configure(task ->
                task.dependsOn(messageUsageCheck, cssUsageCheck));

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
        task.getDefaultLocale().set(extension.getDefaultLocale());
        task.getSupportedLocales().set(extension.getSupportedLocales());
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
        task.getGenerateMessages().set(extension.getGenerateMessages());
        task.getMessagesName().set(extension.getMessagesName());
        task.getCatalogId().set(extension.getMessages().map(directory -> catalogId(project, directory.getAsFile())));
        task.getTrustedPaths().set(extension.getTrustedPaths());
        task.getStrictModels().set(extension.getStrictModels());
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

    private void configureRuntimeDependencies(Project project) {
        var version = implementationVersion(project);
        project.getDependencies().add("implementation", "no.beint.thim:runtime:" + version);
        project.getPluginManager().withPlugin("org.springframework.boot", ignored ->
                project.getDependencies().add("implementation", "no.beint.thim:spring:" + version));
    }

    private String implementationVersion(Project project) {
        var version = ThimPlugin.class.getPackage().getImplementationVersion();
        return version == null ? project.getVersion().toString() : version;
    }

    private FileTree htmlFiles(Project project, java.io.File directory) {
        return project.fileTree(directory, files -> files.include("**/*.html"));
    }

    private FileTree allFiles(Project project, java.io.File directory) {
        return project.fileTree(directory);
    }

    private void configureResourceFiltering(Project project, ThimExtension extension) {
        project.getTasks().named(JavaPlugin.PROCESS_RESOURCES_TASK_NAME, ProcessResources.class).configure(task ->
                task.exclude(element -> {
                    var file = element.getFile().toPath().toAbsolutePath().normalize();
                    var messages = extension.getMessages().get().getAsFile().toPath().toAbsolutePath().normalize();
                    if (file.startsWith(messages)) {
                        return true;
                    }
                    if (!extension.getStrictTemplates().get()) {
                        return false;
                    }
                    var templates = extension.getTemplates().get().getAsFile().toPath().toAbsolutePath().normalize();
                    return file.startsWith(templates);
                })
        );
    }

    @SuppressWarnings("unchecked")
    private TaskProvider<ThimMessageUsageCheck> messageUsageCheck(Project project) {
        var root = project.getRootProject();
        var extras = root.getExtensions().getExtraProperties();
        synchronized (extras) {
            if (extras.has(MESSAGE_USAGE_TASK_KEY)) {
                return (TaskProvider<ThimMessageUsageCheck>) extras.get(MESSAGE_USAGE_TASK_KEY);
            }
            var check = root.getTasks().register(MESSAGE_USAGE_TASK, ThimMessageUsageCheck.class, task -> {
                task.setGroup("verification");
                task.setDescription("Detects unused Thim messages across production project classes");
                task.getReportFile().set(root.getLayout().getBuildDirectory().file("reports/thim/message-usage.json"));
            });
            extras.set(MESSAGE_USAGE_TASK_KEY, check);
            root.allprojects(candidate -> candidate.getPluginManager().withPlugin("java", ignored -> {
                var sourceSets = candidate.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
                var main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
                check.configure(task -> {
                    task.getProjectOutputs().from(main.getOutput());
                    task.dependsOn(candidate.getTasks().named(JavaPlugin.CLASSES_TASK_NAME));
                });
            }));
            return check;
        }
    }

    @SuppressWarnings("unchecked")
    private TaskProvider<ThimCssUsageCheck> cssUsageCheck(Project project, ThimExtension extension) {
        var root = project.getRootProject();
        var extras = root.getExtensions().getExtraProperties();
        TaskProvider<ThimCssUsageCheck> check;
        synchronized (extras) {
            if (extras.has(CSS_USAGE_TASK_KEY)) {
                check = (TaskProvider<ThimCssUsageCheck>) extras.get(CSS_USAGE_TASK_KEY);
            } else {
                check = root.getTasks().register(CSS_USAGE_TASK, ThimCssUsageCheck.class, task -> {
                    task.setGroup("verification");
                    task.setDescription("Detects unused first-party CSS across production source trees");
                    task.getRootDirectory().set(root.getLayout().getProjectDirectory());
                    task.getRuntimeClasses().convention(java.util.List.of());
                    task.getReportFile().set(root.getLayout().getBuildDirectory().file("reports/thim/css-usage.json"));
                });
                extras.set(CSS_USAGE_TASK_KEY, check);
                var registered = check;
                root.allprojects(candidate -> registered.configure(task ->
                        task.getUsageFiles().from(usageFiles(candidate))));
            }
        }
        var registered = check;
        registered.configure(task -> {
            task.getCssFiles().from(project.provider(() -> {
                if (!extension.getFailOnUnusedCss().get()) return java.util.List.of();
                var directory = extension.getCss().get().getAsFile();
                return directory.isDirectory() ? cssFiles(project, directory) : java.util.List.of();
            }));
            task.getUsageFiles().from(extension.getCssUsage());
            task.getRuntimeClasses().addAll(extension.getRuntimeCssClasses());
        });
        return check;
    }

    private String catalogId(Project project, File directory) {
        var relative = project.getRootProject().relativePath(directory).replace(File.separatorChar, '/');
        return project.getRootProject().getName() + "/" + relative;
    }

    private FileTree cssFiles(Project project, File directory) {
        return project.fileTree(directory, files -> {
            files.include("**/*.css");
            files.exclude("**/vendor/**", "**/node_modules/**");
        });
    }

    private FileTree usageFiles(Project project) {
        var directory = project.getLayout().getProjectDirectory().dir("src/main").getAsFile();
        return project.fileTree(directory, files -> {
            files.include(
                    "**/*.html", "**/*.htm", "**/*.java", "**/*.kt", "**/*.kts",
                    "**/*.js", "**/*.jsx", "**/*.mjs", "**/*.cjs", "**/*.ts", "**/*.tsx"
            );
            files.exclude("**/node_modules/**");
        });
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
