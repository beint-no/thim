package no.beint.thim.gradle;

import com.google.devtools.ksp.gradle.KspAATask;
import com.google.devtools.ksp.gradle.KspExtension;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.FileTree;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.PathSensitivity;

import javax.lang.model.SourceVersion;
import java.util.Locale;

public final class ThimPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        var extension = project.getExtensions().create("thim", ThimExtension.class);
        extension.getTemplates().convention(project.getLayout().getProjectDirectory().dir("src/main/resources/templates"));
        extension.getMessages().convention(project.getLayout().getProjectDirectory().dir("src/main/resources"));
        extension.getGeneratedPackage().convention(project.provider(() -> generatedPackage(project)));
        extension.getRegistryName().convention("ThimTemplates");

        project.getPluginManager().withPlugin("org.jetbrains.kotlin.jvm", ignored -> configureKotlinProject(project, extension));
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

        project.getTasks().withType(KspAATask.class).configureEach(task -> {
            task.getInputs().files(extension.getTemplates().map(directory -> htmlFiles(project, directory.getAsFile())))
                    .withPropertyName("thimTemplates")
                    .withPathSensitivity(PathSensitivity.RELATIVE);
            task.getInputs().files(extension.getMessages().map(directory -> propertyFiles(project, directory.getAsFile())))
                    .withPropertyName("thimMessages")
                    .withPathSensitivity(PathSensitivity.RELATIVE);
        });

        var generatedResources = project.getLayout().getBuildDirectory().dir("generated/ksp/main/resources");
        project.getTasks().matching(task -> task.getName().equals("bootRun")).configureEach(task -> {
            task.dependsOn("kspKotlin");
            if (task instanceof JavaExec javaExec) {
                javaExec.classpath(generatedResources);
            }
        });
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
