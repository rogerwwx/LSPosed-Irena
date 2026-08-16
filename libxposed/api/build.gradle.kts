plugins {
    alias(libs.plugins.agp.lib)
}

android {
    namespace = "io.github.libxposed.api"

    sourceSets {
        val main by getting
        main.apply {
            setRoot("api/api/src/main")
        }
    }

    buildFeatures {
        buildConfig = false
    }

    androidResources {
        enable = false
    }
}

dependencies {
    compileOnly(libs.androidx.annotation)
    // io.github.libxposed.annotation.SinceApi / InternalApi, referenced by the API 102 sources
    compileOnly(projects.libxposed.compat)
}
