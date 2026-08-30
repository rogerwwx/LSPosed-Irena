/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2022 LSPosed Contributors
 */

plugins {
    alias(libs.plugins.agp.lib)
}

// True only when built with -Plsp.special=true; compiles out the dex2oat binary's logs.
val specialBuild: Boolean by rootProject.extra

android {
    namespace = "org.lsposed.dex2oat"

    buildFeatures {
        buildConfig = false
        prefab = true
        prefabPublishing = true
    }

    androidResources {
        enable = false
    }

    defaultConfig {
        minSdk = 29
        if (specialBuild) {
            externalNativeBuild {
                cmake {
                    arguments += "-DLOG_DISABLED=ON"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
        }
    }

    prefab {
        register("dex2oat")
    }
}
