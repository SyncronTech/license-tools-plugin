package com.cookpad.android.licensetools

public class LicenseToolsExtension {
    public static String NAME = "licenseTools"

    public boolean throwException = true

    public boolean recursive = false

    public List<String> disAllowed = []

    public File licensesYaml = new File("licenses.yml")

    public File outputJson = new File("licenses.json")

    public File outputHtml = new File("licenses.html")

    public Set<String> ignoredGroups = new HashSet<>()

    public Set<String> ignoredProjects = new HashSet<>()
}
