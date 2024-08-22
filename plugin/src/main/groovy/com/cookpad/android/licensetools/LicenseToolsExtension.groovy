package com.cookpad.android.licensetools

import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty

public class LicenseToolsExtension {

    public static String NAME = "licenseTools"

    public boolean throwException = true

    public boolean recursive = false

    public List<String> disAllowed = []

    public RegularFileProperty licensesYaml

    public RegularFileProperty outputJson

    public RegularFileProperty outputHtml

    public Set<String> ignoredGroups = new HashSet<>()

    public Set<String> ignoredProjects = new HashSet<>()

    LicenseToolsExtension(Project project) {
        this.licensesYaml = project.objects.fileProperty().convention(project.layout.projectDirectory.file("licenses.yml"))
        this.outputJson = project.objects.fileProperty().convention(project.layout.projectDirectory.file("licenses.json"))
        this.outputHtml = project.objects.fileProperty().convention(project.layout.projectDirectory.file("licenses.html"))
    }
}
