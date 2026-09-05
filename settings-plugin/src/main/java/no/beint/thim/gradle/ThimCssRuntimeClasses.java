package no.beint.thim.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Files;

@CacheableTask
public abstract class ThimCssRuntimeClasses extends DefaultTask {
    @Input
    public abstract ListProperty<String> getClasses();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void writeClasses() throws IOException {
        var output = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(output.getParent());
        Files.write(output, getClasses().get().stream().distinct().sorted().toList());
    }
}
