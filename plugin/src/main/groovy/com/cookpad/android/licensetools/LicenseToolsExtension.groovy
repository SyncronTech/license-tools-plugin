package com.cookpad.android.licensetools

public class LicenseToolsExtension {
    public static String NAME = "licenseTools"

    public boolean throwException = true

    public List<String> disAllowed = []

    public File licensesYaml = new File("licenses.yml")

    public File outputHtml = new File("licenses.html")

    public File outputJson = new File("licenses.json")

    public Set<String> ignoredGroups = new HashSet<>()

    public Set<String> ignoredProjects = new HashSet<>()
}
