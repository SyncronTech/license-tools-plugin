package com.cookpad.android.licensetools

import org.gradle.api.Project
import org.gradle.api.provider.Provider

public class LicenseToolsExtension {
    public static String NAME = "licenseTools"

    public boolean throwException = true

    public boolean recursive = false

    public List<String> disAllowed = []

    public Provider<File> licensesYaml

    public Provider<File> outputJson

    public Provider<File> outputHtml

    public Set<String> ignoredGroups = new HashSet<>()

    public Set<String> ignoredProjects = new HashSet<>()

    LicenseToolsExtension(Project project) {
        this.licensesYaml = project.objects.property(File)
        this.licensesYaml.set(new File("licenses.yml"))

        this.outputJson = project.objects.property(File)
        this.outputJson.set(new File("licenses.json"))

        this.outputHtml = project.objects.property(File)
        this.outputHtml.set(new File("licenses.html"))
    }
}
