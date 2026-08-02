package no.beint.thim.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;

public abstract class ThimExtension {
    public abstract DirectoryProperty getTemplates();

    public abstract DirectoryProperty getMessages();

    public abstract Property<String> getGeneratedPackage();

    public abstract Property<String> getRegistryName();
}
