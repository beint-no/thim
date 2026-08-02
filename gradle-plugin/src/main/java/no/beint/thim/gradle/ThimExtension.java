package no.beint.thim.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

public abstract class ThimExtension {
    public abstract DirectoryProperty getTemplates();

    public abstract DirectoryProperty getMessages();

    public abstract Property<String> getGeneratedPackage();

    public abstract Property<String> getRegistryName();

    public abstract ListProperty<String> getModelPackages();

    public abstract Property<Boolean> getStrictTemplates();

    public abstract Property<Boolean> getFailOnUnusedMessages();
}
