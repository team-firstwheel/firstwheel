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
        // TODO: Pleos Connect SDK 저장소 주소를 Pleos Playground 'Vehicle SDK 연동 가이드' 기준으로 추가
        // maven { url = uri("https://<pleos-connect-sdk-repository>") }
    }
}

rootProject.name = "firstwheel"
include(":core", ":app")
