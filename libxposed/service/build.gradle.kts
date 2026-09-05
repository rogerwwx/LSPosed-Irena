plugins {
    alias(libs.plugins.agp.lib)
}

android {
    namespace = "io.github.libxposed.service"

    sourceSets {
        val main by getting
        main.apply {
            setRoot("service/service/src/main")
            // srcDirs works in both the classic and the new AGP DSL.
            aidl.srcDirs("service/interface/src/main/aidl")
        }
    }

    buildFeatures {
        buildConfig = false
        resValues = false
        aidl = true
    }
}

dependencies {
    compileOnly(libs.androidx.annotation)
    // io.github.libxposed.annotation.SinceApi, referenced by the service API 102 sources
    compileOnly(projects.libxposed.compat)
}
