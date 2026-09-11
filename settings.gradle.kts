pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.7"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        // root ブランチはノードの版一覧だけを持つ (build ファイルは持たない)
        version("26.2", "26.2")
        version("1.21.11", "1.21.11")
        version("1.21.1", "1.21.1")
        version("1.20.1", "1.20.1")

        // モジュール = ブランチ。ノードごとに buildscript を分けてプラグイン版を出し分ける
        branch("common") {
            version("26.2", "26.2")
            version("26.1.2", "26.1.2")
            version("1.21.11", "1.21.11")
            version("1.21.1", "1.21.1")
            // 1.20.1 は legacyforge (MCP マッピング) なので別 buildscript
            version("1.20.1", "1.20.1").buildscript("build-1201.gradle.kts")
        }
        branch("neoforge") {
            version("26.2", "26.2").buildscript("build-262.gradle.kts")
            version("26.1.2", "26.1.2").buildscript("build-2612.gradle.kts")
            version("1.21.11", "1.21.11").buildscript("build-12111.gradle.kts")
            version("1.21.1", "1.21.1").buildscript("build-1211.gradle.kts")
        }
        branch("fabric") {
            version("26.2", "26.2").buildscript("build-262.gradle.kts")
            version("1.21.11", "1.21.11").buildscript("build-12111.gradle.kts")
            version("1.21.1", "1.21.1").buildscript("build-1211.gradle.kts")
            version("1.20.1", "1.20.1").buildscript("build-1201.gradle.kts")
        }
        // 1.20.1 だけ NeoForge ではなく Forge 47.x なので別ブランチ
        branch("forge") {
            version("1.20.1", "1.20.1").buildscript("build-1201.gradle.kts")
        }

        vcsVersion = "1.21.11"
    }
}

rootProject.name = "stonecutter-spike"

// LavaPlayer は隔離 URLClassLoader で runtime ロードする。ソースが MC / loader に触れないので
// Stonecutter のノードを持たない素のサブプロジェクト 2 本で足りる (帯で分けない)。
//  - lavaplayer-api : mod <-> LavaPlayer の橋渡し interface (common の api パッケージを読む)
//  - lavaplayer     : LavaPlayer を使う impl + 依存を .jar.packed で同梱 (隔離 classloader が読む)
include("lavaplayer-api")
include("lavaplayer")
