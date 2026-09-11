// 1.21.11 ノード。ModDevGradle 2.0.141 / NeoForge 21.11.42 / Java 21。
plugins {
    id("java-library")
    id("net.neoforged.moddev") version "2.0.141"
}

val mcVersion = "1.21.11"
val mcVersionRange = "[1.21.11, 1.22)"
val neoforgeVersion = "21.11.42"
val neoforgeLoaderRange = "[1,)"
val javaVersion = 21
val compatLibsDir = rootProject.file("neoforge/compat-libs-1.21.11")

group = "com.kuronami.musicdiscmaker"
version = "${rootProject.property("version")}+$mcVersion"

// MOD のメタデータに載る版は接尾辞を持たない (出荷の mods.toml / fabric.mod.json / manifest は "2.2.3")。
// project.version の接尾辞は jar のファイル名で帯を判別するためだけに要る。
val modVersion = rootProject.property("version") as String

base {
    archivesName = "${rootProject.property("mod_id")}-neoforge-$mcVersion"
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
}

repositories {
    mavenCentral()
}

neoForge {
    version = neoforgeVersion
    val at = project(":common:${stonecutter.current.project}").file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) accessTransformers.from(at.absolutePath)
    mods {
        create(rootProject.property("mod_id") as String) {
            sourceSet(sourceSets.main.get())
        }
    }
}

// ─── common のソースを取り込む経路 ───
// MultiLoader-Template は commonJava/commonResources という consumable configuration で
// ソースディレクトリを渡すが、Gradle 9.5.1 では variant として解決できなかったため、
// 同じノードの common プロジェクトの srcDirs を直接読む形に置き換えている。
val commonPath = ":common:${stonecutter.current.project}"
evaluationDependsOn(commonPath)
val commonProject = project(commonPath)
val commonMain = commonProject.extensions.getByType<SourceSetContainer>()["main"]
val commonGenerate = commonProject.tasks.named("stonecutterGenerate")

dependencies {

    // 互換 mod (ソフト依存・compileOnly = 公開 jar には含めない)
    compileOnly(files(fileTree(compatLibsDir) { include("*.jar") }))
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(commonGenerate)
    source(commonMain.java.srcDirs)
    options.encoding = "UTF-8"
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(commonGenerate)
    commonMain.resources.srcDirs.forEach { from(it) }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    val expandProps = mapOf(
        "version" to modVersion,
        "group" to project.group,
        "minecraft_version" to mcVersion,
        "minecraft_version_range" to mcVersionRange,
        "mod_name" to rootProject.property("mod_name"),
        "mod_author" to rootProject.property("mod_author"),
        "mod_id" to rootProject.property("mod_id"),
        "license" to rootProject.property("license"),
        "credits" to rootProject.property("credits"),
        "description" to rootProject.property("mod_description"),
        "neoforge_version" to neoforgeVersion,
        "neoforge_loader_version_range" to neoforgeLoaderRange,
        "java_version" to javaVersion
    )
    filesMatching(listOf("pack.mcmeta", "META-INF/neoforge.mods.toml", "*.mixins.json")) {
        expand(expandProps)
    }
    inputs.properties(expandProps)
}

// ─── LavaPlayer 一式を mod jar に同梱 (隔離 classloader が runtime ロード) ───
// 帯によらず同じ 21 本 (impl 1 + 依存 20) が入る。出荷中の 9 本の jar と本数・名前が一致する。
evaluationDependsOn(":lavaplayer")
val lavaImpl = project(":lavaplayer")

tasks.named<Jar>("jar") {
    dependsOn(lavaImpl.tasks.named("assemble"))
    into("dependencies") {
        from(lavaImpl.layout.buildDirectory.dir("libs")) { include("*.jar.packed") }
        from(lavaImpl.layout.buildDirectory.dir("dependencies")) { include("*.jar.packed") }
    }

    // 原本の convention plugin (multiloader-common.gradle) が jar に載せていた宣言。
    // ノード別 buildscript へ写っていなかったので明示する。Implementation-Title は原本と同じく
    // ローダー名 (統合ツリーの project.name はノードの版番号なので使えない)。
    from(rootProject.file("LICENSE")) { rename { "${it}_" + rootProject.property("mod_name") } }
    manifest.attributes(mapOf(
        "Specification-Title" to rootProject.property("mod_name"),
        "Specification-Vendor" to rootProject.property("mod_author"),
        "Specification-Version" to modVersion,
        "Implementation-Title" to "neoforge",
        "Implementation-Version" to modVersion,
        "Implementation-Vendor" to rootProject.property("mod_author"),
        "Built-On-Minecraft" to mcVersion
    ))
}

tasks.register("printNodeInfo") {
    doLast {
        val loc = Class.forName("net.neoforged.moddevgradle.internal.ModDevPlugin")
            .protectionDomain.codeSource.location
        println("### node=${stonecutter.current.project} mc=$mcVersion neoforge=$neoforgeVersion java=$javaVersion moddevJar=$loc")
    }
}


// ─── dev 起動 (run 設定) ──────────────────────────────────────────────────
// DependencyManager.doLoad() は musicdiscmaker.dev があると build ディレクトリを直読みし、
// ServiceLoader -> PathLocator -> jar 内走査という production の経路を丸ごと迂回する。
// 原本 5 リポは全ての run にこの近道を入れていたので、同じ形だけを写すと
// production の依存ロード経路を一度も踏まない run 設定が統合後も残る。
// runClientProd だけ musicdiscmaker.dev を立てず、その経路を踏ませる。
val lavaRunProject = project(":lavaplayer")
val lavaDevPath = lavaRunProject.layout.buildDirectory.dir("libs").get().asFile.absolutePath +
        ";" + lavaRunProject.layout.buildDirectory.dir("dependencies").get().asFile.absolutePath

neoForge {
    runs {
        configureEach {
            systemProperty("neoforge.enabledGameTestNamespaces",
                    rootProject.property("mod_id") as String)
        }
        create("client") {
            client()
            systemProperty("musicdiscmaker.dev", lavaDevPath)
        }
        create("server") {
            server()
            systemProperty("musicdiscmaker.dev", lavaDevPath)
        }
        create("gameTestServer") {
            // Keep automated test logs, options and worlds separate from an open development client.
            gameDirectory.set(layout.projectDirectory.dir("run-gametest"))
            type.set("gameTestServer")
            systemProperty("musicdiscmaker.dev", lavaDevPath)
            programArgument("--tests")
            programArgument("music_disc_maker:*")
        }
        // production の依存ロード経路を踏む唯一の run。musicdiscmaker.dev を立てない。
        create("clientProd") {
            client()
        }
    }
}

tasks.matching {
    it.name in listOf("runClient", "runServer", "runGameTestServer", "runClientProd")
}.configureEach {
    dependsOn(project(":lavaplayer").tasks.named("assemble"))
}
