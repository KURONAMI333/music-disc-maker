// 1.21.1 ノード。fabric-loom は id が fabric-loom (別座標) の 1.14.10 / Java 21。
plugins {
    id("java-library")
    id("fabric-loom") version "1.14.10"
}

val mcVersion = "1.21.1"
val fabricLoaderVersion = "0.16.9"
val fabricApiVersion = "0.109.0+1.21.1"
val javaVersion = 21
val compatLibsDir = rootProject.file("fabric/compat-libs-1.21.1")

group = "com.kuronami.musicdiscmaker"
version = "${rootProject.property("version")}+$mcVersion"

// MOD のメタデータに載る版は接尾辞を持たない (出荷の mods.toml / fabric.mod.json / manifest は "2.2.3")。
// project.version の接尾辞は jar のファイル名で帯を判別するためだけに要る。
val modVersion = rootProject.property("version") as String

base {
    archivesName = "${rootProject.property("mod_id")}-fabric-$mcVersion"
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    // MC 1.21.1 は難読化ありなので mappings + remap 経路 (mod-047 と同じ)。parchment は入れない (引数名だけの差)。
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // 互換 mod (ソフト依存・compileOnly = 公開 jar には含めない)
    modCompileOnly(files(fileTree(compatLibsDir) { include("*.jar") }))
}

// ─── common のソースを取り込む経路 (neoforge ノードと同じ) ───
val commonPath = ":common:${stonecutter.current.project}"
evaluationDependsOn(commonPath)
val commonProject = project(commonPath)
val commonMain = commonProject.extensions.getByType<SourceSetContainer>()["main"]
val commonGenerate = commonProject.tasks.named("stonecutterGenerate")

loom {
    val aw = commonProject.file("src/main/resources/${rootProject.property("mod_id")}.accesswidener")
    if (aw.exists()) accessWidenerPath.set(aw)
    // loom.mixin ブロックは置かない (1.14.10 は remapJar 時に静的 remap・26.x は難読化解除済み)
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
        "minecraft_version_range" to "[1.21.1, 1.22)",
        "fabric_version" to fabricApiVersion,
        "fabric_loader_version" to fabricLoaderVersion,
        "mod_name" to rootProject.property("mod_name"),
        "mod_author" to rootProject.property("mod_author"),
        "mod_id" to rootProject.property("mod_id"),
        "license" to rootProject.property("license"),
        "credits" to rootProject.property("credits"),
        "description" to rootProject.property("mod_description"),
        "java_version" to javaVersion
    )
    filesMatching(listOf("pack.mcmeta", "fabric.mod.json", "*.mixins.json")) {
        expand(expandProps)
    }
    inputs.properties(expandProps)
}

// ─── LavaPlayer 一式を mod jar に同梱 (隔離 classloader が runtime ロード) ───
// 帯によらず同じ 21 本 (impl 1 + 依存 20) が入る。出荷中の 9 本の jar と本数・名前が一致する。
// Loom の最終成果物は remapJar (jar を消費) なので、jar に入れれば remap 後も残る。
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
        "Implementation-Title" to "fabric",
        "Implementation-Version" to modVersion,
        "Implementation-Vendor" to rootProject.property("mod_author"),
        "Built-On-Minecraft" to mcVersion
    ))
}

tasks.register("printLoomInfo") {
    doLast {
        val loc = Class.forName("net.fabricmc.loom.LoomGradleExtension")
            .protectionDomain.codeSource.location
        println("### node=${stonecutter.current.project} mc=$mcVersion java=$javaVersion loomJar=$loc")
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

loom {
    runs {
        configureEach {
            ideConfigGenerated(true)
            runDir("runs/" + name)
            // clientProd だけ近道を立てない (production の依存ロード経路を踏ませる)
            if (name != "clientProd") {
                property("musicdiscmaker.dev", lavaDevPath)
            }
        }
        // production の依存ロード経路を踏む唯一の run。
        create("clientProd") {
            client()
        }
    }
}

tasks.matching {
    it.name in listOf("runClient", "runServer", "runClientProd")
}.configureEach {
    dependsOn(project(":lavaplayer").tasks.named("assemble"))
}
