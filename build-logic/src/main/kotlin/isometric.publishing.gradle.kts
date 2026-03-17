import com.vanniktech.maven.publish.SonatypeHost

/**
 * Convention plugin for all publishable Isometric library modules.
 *
 * Applies: com.vanniktech.maven.publish
 * Sets: publishToMavenCentral, signAllPublications, and all shared POM fields
 *       (url, inceptionYear, license, shared developer, scm).
 *
 * Each consuming module still declares its own:
 *   - mavenPublishing.coordinates(groupId, artifactId, version)
 *   - pom.name / pom.description
 *   - any additional developers (e.g. upstream attribution in isometric-core)
 */
plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    pom {
        url.set("https://github.com/jayteealao/Isometric")
        inceptionYear.set("2024")

        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("jayteealao")
                name.set("jayteealao")
                url.set("https://github.com/jayteealao")
            }
        }

        scm {
            url.set("https://github.com/jayteealao/Isometric")
            connection.set("scm:git:git://github.com/jayteealao/Isometric.git")
            developerConnection.set("scm:git:ssh://git@github.com/jayteealao/Isometric.git")
        }
    }
}
