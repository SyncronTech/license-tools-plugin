package com.cookpad.android.licensetools

import groovy.json.JsonBuilder
import groovy.util.slurpersupport.GPathResult
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ResolvedArtifact
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.xml.sax.helpers.DefaultHandler
import org.yaml.snakeyaml.Yaml

class LicenseToolsPlugin implements Plugin<Project> {

    final yaml = new Yaml()

    final DependencySet librariesYaml = new DependencySet() // based on libraries.yml
    final DependencySet dependencyLicenses = new DependencySet() // based on license plugin's dependency-license.xml

    @Override
    void apply(Project project) {
        project.extensions.create(LicenseToolsExtension.NAME, LicenseToolsExtension, project)

        def checkLicenses = project.task('checkLicenses').doLast {
            initialize(project)

            def notDocumented = dependencyLicenses.notListedIn(librariesYaml)
            def notInDependencies = librariesYaml.notListedIn(dependencyLicenses)
            def licensesNotMatched = dependencyLicenses.licensesNotMatched(librariesYaml)

            LicenseToolsExtension ext = project.extensions.findByType(LicenseToolsExtension)

            if(!ext.disAllowed.empty) {
                for(String lic : ext.disAllowed) {
                    boolean found = false
                    def libCheck = { libraryInfo ->
                        if(libraryInfo.license && libraryInfo.license.matches(lic)) {
                            found = true
                        }
                    }

                    dependencyLicenses.each libCheck
                    librariesYaml.each libCheck

                    if(found) {
                        if(ext.throwException) {
                            throw new GradleException("checkLicenses: Licenses found which are not allowed: '${lic}'!")
                        } else {
                            project.logger.error("checkLicenses: Licenses found which are not allowed: '${lic}'!")
                        }
                    }
                }
            }

            if (notDocumented.empty && notInDependencies.empty && licensesNotMatched.empty) {
                project.logger.info("checkLicenses: ok")
                return
            }

            if (notDocumented.size() > 0) {
                project.logger.warn("# Libraries not listed in ${ext.licensesYaml}:")
                notDocumented.each { libraryInfo ->
                    def text = generateLibraryInfoText(libraryInfo)
                    project.logger.warn(text)
                }
            }

            if (notInDependencies.size() > 0) {
                project.logger.warn("# Libraries listed in ${ext.licensesYaml} but not in dependencies:")
                notInDependencies.each { libraryInfo ->
                    project.logger.warn("- artifact: ${libraryInfo.artifactId}\n")
                }
            }
            if (licensesNotMatched.size() > 0) {
                project.logger.warn("# Licenses not matched with pom.xml in dependencies:")
                licensesNotMatched.each { libraryInfo ->
                    project.logger.warn("- artifact: ${libraryInfo.artifactId}\n  license: ${libraryInfo.license}")
                }
            }

            if(ext.throwException) {
                throw new GradleException("checkLicenses: missing libraries in ${ext.licensesYaml}")
            } else {
                project.logger.error("checkLicenses: missing libraries in ${ext.licensesYaml}")
            }
        }

        checkLicenses.configure {
            group = "Verification"
            description = 'Check whether dependency licenses are listed in licenses.yml'
        }

        def updateLicenses = project.task('updateLicenses').doLast {
            initialize(project)

            def notDocumented = dependencyLicenses.notListedIn(librariesYaml)
            LicenseToolsExtension ext = project.extensions.findByType(LicenseToolsExtension)

            notDocumented.each { libraryInfo ->
                def text = generateLibraryInfoText(libraryInfo)
                project.file(ext.licensesYaml).append("\n${text}")
            }
        }

        def generateLicensePage = project.task('generateLicensePage').doLast {
            initialize(project)
            generateLicensePage(project)
        }
        generateLicensePage.dependsOn(checkLicenses)

        def generateLicenseJson = project.task('generateLicenseJson').doLast {
            initialize(project)
            generateLicenseJson(project)
        }
        generateLicenseJson.dependsOn(checkLicenses)

        project.tasks.findByName(LifecycleBasePlugin.CHECK_TASK_NAME)?.dependsOn(checkLicenses)
    }

    void initialize(Project project) {
        LicenseToolsExtension ext = project.extensions.findByType(LicenseToolsExtension)
        loadLibrariesYaml(project.file(ext.licensesYaml))
        loadDependencyLicenses(project, ext.ignoredGroups, ext.ignoredProjects, ext.recursive)
    }

    void loadLibrariesYaml(File licensesYaml) {
        if (!licensesYaml.exists()) {
            return
        }

        def libraries = loadYaml(licensesYaml)
        for (lib in libraries) {
            def libraryInfo = LibraryInfo.fromYaml(lib)
            librariesYaml.add(libraryInfo)
        }
    }

    void loadDependencyLicenses(Project project, Set<String> ignoredGroups, Set<String> ignoredProjects, boolean recursive) {
        resolveProjectDependencies(project, ignoredProjects, recursive).each { d ->
            if (d.moduleVersion.id.version == "unspecified") {
                return
            }
            if (ignoredGroups.contains(d.moduleVersion.id.group)) {
                return
            }

            def dependencyDesc = "$d.moduleVersion.id.group:$d.moduleVersion.id.name:$d.moduleVersion.id.version"

            def libraryInfo = new LibraryInfo()
            try {
                libraryInfo.artifactId = ArtifactId.parse(dependencyDesc)
                libraryInfo.filename = d.file
                dependencyLicenses.add(libraryInfo)
            } catch (IllegalArgumentException e) {
                project.logger.info("Unsupport dependency: $dependencyDesc")
                return
            }

            Dependency pomDependency = project.dependencies.create("$dependencyDesc@pom")
            Configuration pomConfiguration = project.configurations.detachedConfiguration(pomDependency)

            File pStream
            try {
                def resolved = pomConfiguration.resolve()
                resolved.each {
                    project.logger.info("POM: ${it}")
                }

                pStream = resolved.asList().first()
            } catch (Exception ignored) {
                project.logger.warn("Unable to retrieve license for $dependencyDesc")
                return
            }

            XmlSlurper slurper = new XmlSlurper(true, false)
            slurper.setErrorHandler(new DefaultHandler())
            GPathResult xml = slurper.parse(pStream)

            libraryInfo.libraryName = xml.name.text()
            libraryInfo.url = xml.url.text()

            xml.licenses.license.each {
                if (!libraryInfo.license) {
                    // takes the first license
                    libraryInfo.license = it.name.text().trim()
                    libraryInfo.licenseUrl = it.url.text().trim()
                }
            }
        }
    }

    Map<String, ?> loadYaml(File yamlFile) {
        return yaml.load(yamlFile.text) as Map<String, ?> ?: [:]
    }

    void generateLicensePage(Project project) {
        def ext = project.extensions.getByType(LicenseToolsExtension)

        def noLicenseLibraries = new ArrayList<LibraryInfo>()
        def content = new StringBuilder()

        librariesYaml.each { libraryInfo ->
            if (libraryInfo.skip) {
                project.logger.info("generateLicensePage: skip ${libraryInfo.name}")
                return
            }

            // merge dependencyLicenses's libraryInfo into librariesYaml's
            def o = dependencyLicenses.find(libraryInfo.artifactId)
            if (o) {
                libraryInfo.license = libraryInfo.license ?: o.license
                libraryInfo.filename = o.filename
                libraryInfo.artifactId = o.artifactId
                libraryInfo.url = libraryInfo.url ?: o.url
            }
            try {
                content.append(Templates.buildLicenseHtml(libraryInfo));
            } catch (NotEnoughInformationException e) {
                noLicenseLibraries.add(e.libraryInfo)
            }
        }

        assertEmptyLibraries(noLicenseLibraries)

        project.mkdir(ext.outputHtml.get().getParentFile())
        project.logger.info("render ${ext.outputHtml}")
        project.file(ext.outputHtml).write(Templates.wrapWithLayout(content))
    }

    static String generateLibraryInfoText(LibraryInfo libraryInfo) {
        def text = new StringBuffer()
        text.append("- artifact: ${libraryInfo.artifactId.withWildcardVersion()}\n")
        text.append("  name: ${libraryInfo.escapedName ?: "#NAME#"}\n")
        text.append("  copyrightHolder: ${libraryInfo.copyrightHolder ?: "#COPYRIGHT_HOLDER#"}\n")
        text.append("  license: ${libraryInfo.license ?: "#LICENSE#"}\n")
        if (libraryInfo.licenseUrl) {
            text.append("  licenseUrl: ${libraryInfo.licenseUrl ?: "#LICENSEURL#"}\n")
        }
        if (libraryInfo.url) {
            text.append("  url: ${libraryInfo.url ?: "#URL#"}\n")
        }
        return text.toString().trim()
    }

    void generateLicenseJson(Project project) {
        def ext = project.extensions.getByType(LicenseToolsExtension)
        def noLicenseLibraries = new ArrayList<LibraryInfo>()

        def json = new JsonBuilder()
        def librariesArray = []

        librariesYaml.each { libraryInfo ->
            if (libraryInfo.skip) {
                project.logger.info("generateLicensePage: skip ${libraryInfo.name}")
                return
            }

            // merge dependencyLicenses's libraryInfo into librariesYaml's
            def o = dependencyLicenses.find(libraryInfo.artifactId)
            if (o) {
                libraryInfo.license = libraryInfo.license ?: o.license
                // libraryInfo.filename = o.filename
                libraryInfo.artifactId = o.artifactId
                libraryInfo.url = libraryInfo.url ?: o.url
            }
            try {
                Templates.assertLicenseAndStatement(libraryInfo)
                librariesArray << libraryInfo
            } catch (NotEnoughInformationException e) {
                noLicenseLibraries.add(e.libraryInfo)
            }
        }

        assertEmptyLibraries(noLicenseLibraries)

        json {
            libraries librariesArray.collect {
                l ->
                    return [
                        notice: l.notice,
                        copyrightHolder: l.copyrightHolder,
                        copyrightStatement: l.copyrightStatement,
                        license: l.license,
                        licenseUrl: l.licenseUrl,
                        normalizedLicense: l.normalizedLicense,
                        year: l.year,
                        url: l.url,
                        libraryName: l.libraryName,
                        // I don't why artifactId won't serialize, and this is the only way
                        // I've found -- vishna
                        artifactId: [
                                name: l.artifactId.name,
                                group: l.artifactId.group,
                                version: l.artifactId.version,
                        ]
                    ]
            }
        }

        project.mkdir(ext.outputJson.get().getParentFile())
        project.logger.info("render ${ext.outputJson}")
        project.file(ext.outputJson).write(json.toString())
    }

    static void assertEmptyLibraries(ArrayList<LibraryInfo> noLicenseLibraries) {
        if (noLicenseLibraries.empty) return;
        StringBuilder message = new StringBuilder();
        message.append("Not enough information for:\n")
        message.append("---\n")
        noLicenseLibraries.each { libraryInfo ->
            message.append("- artifact: ${libraryInfo.artifactId}\n")
            message.append("  name: ${libraryInfo.name}\n")
            if (!libraryInfo.license) {
                message.append("  license: #LICENSE#\n")
            }
            if (!libraryInfo.copyrightStatement) {
                message.append("  copyrightHolder: #AUTHOR# (or authors: [...])\n")
                message.append("  year: #YEAR# (optional)\n")
            }
        }
        throw new RuntimeException(message.toString())
    }

    // originated from https://github.com/hierynomus/license-gradle-plugin DependencyResolver.groovy
    Set<ResolvedArtifact> resolveProjectDependencies(Project project, Set<String> ignoredProjects, boolean recursive) {
        def subProjects = [:]
        List<ResolvedArtifact> runtimeDependencies = []

        if(recursive) {
            subProjects = project.rootProject.subprojects.findAll { Project p -> !ignoredProjects.contains(p.name) }
                    .groupBy { Project p -> "$p.group:$p.name:$p.version" }

            project.rootProject.subprojects.findAll { Project p -> !ignoredProjects.contains(p.name) }.each { Project subproject ->
                runtimeDependencies.addAll(getProjectDependencies(subproject, recursive))
            }
        } else {
            runtimeDependencies.addAll(getProjectDependencies(project, recursive))
        }

        runtimeDependencies = runtimeDependencies.flatten()
        runtimeDependencies.removeAll([null])

        def seen = new HashSet<String>()
        def dependenciesToHandle = new HashSet<ResolvedArtifact>()
        runtimeDependencies.each { ResolvedArtifact d ->
            String dependencyDesc = "$d.moduleVersion.id.group:$d.moduleVersion.id.name:$d.moduleVersion.id.version"
            if (!seen.contains(dependencyDesc)) {
                dependenciesToHandle.add(d)

                Project subProject = subProjects[dependencyDesc]?.first()
                if (subProject) {
                    dependenciesToHandle.addAll(resolveProjectDependencies(subProject, ignoredProjects, recursive))
                }
            }
        }
        return dependenciesToHandle
    }

    static List<ResolvedArtifact> getProjectDependencies(Project project, boolean recursive) {
        def tmpList = project.configurations.all.findAll { Configuration c ->
            // compile|implementation|api, release(Compile|Implementation|Api), releaseProduction(Compile|Implementation|Api), and so on.
            c.name.matches(/^(?!releaseUnitTest)(?:release\w*)?([cC]ompile|[cC]ompileOnly|[iI]mplementation|[aA]pi)$/)
        }.collect { Configuration c ->
            Configuration copyConfiguration = c.copyRecursive()
            copyConfiguration.setCanBeResolved(true)

            if(!recursive) {
                // Drop all project dependencies from the artifact resolve
                copyConfiguration.dependencies.all { Dependency a ->
                    if(a instanceof ProjectDependency) {
                        copyConfiguration.dependencies.remove(a)
                    }
                }
            }

            copyConfiguration.resolvedConfiguration.lenientConfiguration.artifacts
        }.flatten() as List<ResolvedArtifact>

        if(recursive) {
            return tmpList
        }

        def fullList = project.configurations.all.findAll { Configuration c ->
            // compile|implementation|api, release(Compile|Implementation|Api), releaseProduction(Compile|Implementation|Api), and so on.
            c.name.matches(/^(?!releaseUnitTest)(?:release\w*)?([cC]ompile|[cC]ompileOnly|[iI]mplementation|[aA]pi)$/)
        }.collect { Configuration c ->
            Configuration copyConfiguration = c.copyRecursive()
            copyConfiguration.setCanBeResolved(true)
            copyConfiguration.resolvedConfiguration.lenientConfiguration.artifacts
        }.flatten() as List<ResolvedArtifact>

        List<ResolvedArtifact> output = []

        fullList.each { ResolvedArtifact a ->
            for(ResolvedArtifact b : tmpList) {
                String debA = "$a.moduleVersion.id.group:$a.moduleVersion.id.name" as String
                String debB = "$b.moduleVersion.id.group:$b.moduleVersion.id.name" as String
                if(debA.equals(debB)) {
                    output.add(a)
                }
            }
        }

        return output
    }
}
