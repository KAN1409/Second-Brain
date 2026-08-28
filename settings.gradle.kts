pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "SecondBrain"

include(
    ":app",
    ":core:common",
    ":core:model",
    ":core:database",
    ":core:search",
    ":core:privacy",
    ":core:testing",
    ":domain",
    ":data:repository",
    ":capture:android",
    ":ai:api",
    ":ai:whisper",
    ":ai:ocr",
    ":ai:embedding",
    ":ai:gemini",
    ":feature:timeline",
    ":feature:search",
    ":feature:ask",
    ":feature:capture",
    ":feature:settings",
    ":feature:onboarding",
)
