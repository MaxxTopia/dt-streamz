pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor is JitPack-hosted only — there's no Maven
        // Central or Google Maven publication. Scope tightly so we never
        // pull production deps from JitPack accidentally.
        maven {
            url = uri("https://jitpack.io")
            content {
                // NewPipeExtractor is a multi-module Gradle project — its
                // transitive deps live under com.github.TeamNewPipe.<X>.<Y>
                // groups, so the regex form catches all submodules.
                includeGroupByRegex("com\\.github\\.TeamNewPipe.*")
                includeGroupByRegex("com\\.github\\.teamnewpipe.*")
            }
        }
    }
}

rootProject.name = "dt-streamz"
include(":app")
