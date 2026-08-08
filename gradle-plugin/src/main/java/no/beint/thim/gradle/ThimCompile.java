package no.beint.thim.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;

@CacheableTask
public abstract class ThimCompile extends DefaultTask {
    private final ExecOperations execOperations;
    private final FileSystemOperations fileSystemOperations;

    @Inject
    public ThimCompile(ExecOperations execOperations, FileSystemOperations fileSystemOperations) {
        this.execOperations = execOperations;
        this.fileSystemOperations = fileSystemOperations;
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getModelSources();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getTemplates();

    @InputDirectory
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getMessages();

    @Input
    public abstract Property<String> getDefaultLocale();

    @Input
    public abstract ListProperty<String> getSupportedLocales();

    @Classpath
    public abstract ConfigurableFileCollection getRunnerClasspath();

    @Classpath
    public abstract ConfigurableFileCollection getProcessorClasspath();

    @CompileClasspath
    public abstract ConfigurableFileCollection getLibraries();

    @Input
    public abstract Property<String> getGeneratedPackage();

    @Input
    public abstract Property<String> getRegistryName();

    @Input
    public abstract ListProperty<String> getModelPackages();

    @Input
    public abstract Property<Boolean> getStrictTemplates();

    @Input
    public abstract Property<Boolean> getFailOnUnusedMessages();

    @Input
    public abstract Property<Boolean> getFailOnUnusedFragments();

    @Input
    public abstract Property<Boolean> getValidateRoutes();

    @Input
    public abstract ListProperty<String> getTrustedPaths();

    @Input
    public abstract Property<Boolean> getStrictModels();

    @Input
    public abstract ListProperty<String> getForbiddenModelAnnotations();

    @Input
    public abstract Property<String> getModuleName();

    @Input
    public abstract Property<String> getJdkHome();

    @Internal
    public abstract DirectoryProperty getProjectBase();

    @Internal
    public abstract DirectoryProperty getOutputBase();

    @OutputDirectory
    public abstract DirectoryProperty getJavaOutput();

    @OutputDirectory
    public abstract DirectoryProperty getKotlinOutput();

    @OutputDirectory
    public abstract DirectoryProperty getResourceOutput();

    @OutputDirectory
    public abstract DirectoryProperty getClassOutput();

    @OutputDirectory
    public abstract DirectoryProperty getCaches();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @Internal
    public abstract DirectoryProperty getEmptyKotlinSources();

    @TaskAction
    public void compile() throws IOException {
        var outputDirectories = List.of(
                getOutputBase().get().getAsFile(),
                getJavaOutput().get().getAsFile(),
                getKotlinOutput().get().getAsFile(),
                getResourceOutput().get().getAsFile(),
                getClassOutput().get().getAsFile(),
                getEmptyKotlinSources().get().getAsFile()
        );
        fileSystemOperations.delete(spec -> spec.delete(outputDirectories));
        Files.createDirectories(getCaches().get().getAsFile().toPath());
        for (var directory : outputDirectories) {
            Files.createDirectories(directory.toPath());
        }

        var separator = File.pathSeparator;
        var javaSources = getModelSources().getFiles().stream()
                .sorted(Comparator.comparing(File::getAbsolutePath))
                .map(File::getAbsolutePath)
                .reduce((left, right) -> left + separator + right)
                .orElse("");
        var processorOptions = String.join(separator,
                "thim.templates=" + getTemplates().get().getAsFile().getAbsolutePath(),
                "thim.messages=" + getMessages().get().getAsFile().getAbsolutePath(),
                "thim.defaultLocale=" + getDefaultLocale().get(),
                "thim.supportedLocales=" + String.join(",", getSupportedLocales().get()),
                "thim.package=" + getGeneratedPackage().get(),
                "thim.registry=" + getRegistryName().get(),
                "thim.modelPackages=" + String.join(",", getModelPackages().get()),
                "thim.strictTemplates=" + getStrictTemplates().get(),
                "thim.failOnUnusedMessages=" + getFailOnUnusedMessages().get(),
                "thim.failOnUnusedFragments=" + getFailOnUnusedFragments().get(),
                "thim.validateRoutes=" + getValidateRoutes().get(),
                "thim.trustedPaths=" + String.join(",", getTrustedPaths().get()),
                "thim.strictModels=" + getStrictModels().get(),
                "thim.forbiddenModelAnnotations=" + String.join(",", getForbiddenModelAnnotations().get())
        );
        var arguments = List.of(
                "-java-source-roots=" + javaSources,
                "-java-output-dir=" + getJavaOutput().get().getAsFile().getAbsolutePath(),
                "-jdk-home=" + getJdkHome().get(),
                "-jvm-target=26",
                "-module-name=" + getModuleName().get(),
                "-source-roots=" + getEmptyKotlinSources().get().getAsFile().getAbsolutePath(),
                "-project-base-dir=" + getProjectBase().get().getAsFile().getAbsolutePath(),
                "-output-base-dir=" + getOutputBase().get().getAsFile().getAbsolutePath(),
                "-caches-dir=" + getCaches().get().getAsFile().getAbsolutePath(),
                "-class-output-dir=" + getClassOutput().get().getAsFile().getAbsolutePath(),
                "-kotlin-output-dir=" + getKotlinOutput().get().getAsFile().getAbsolutePath(),
                "-resource-output-dir=" + getResourceOutput().get().getAsFile().getAbsolutePath(),
                "-libraries=" + getLibraries().getAsPath(),
                "-language-version=2.3",
                "-api-version=2.3",
                "-processor-options=" + processorOptions,
                getProcessorClasspath().getAsPath()
        );

        execOperations.javaexec(spec -> {
            spec.setClasspath(getRunnerClasspath());
            spec.getMainClass().set("com.google.devtools.ksp.cmdline.KSPJvmMain");
            spec.jvmArgs("--sun-misc-unsafe-memory-access=allow");
            spec.args(arguments);
        }).assertNormalExitValue();

        var provider = getResourceOutput().file("META-INF/services/no.beint.thim.TemplateSet").get().getAsFile();
        if (!provider.isFile()) {
            throw new GradleException("Thim did not generate a template registry");
        }

        writeReport();
    }

    private void writeReport() throws IOException {
        var report = getReportFile().get().getAsFile();
        Files.createDirectories(report.toPath().getParent());
        Files.writeString(report.toPath(), """
                {
                  "status": "valid"
                }
                """);
    }
}
