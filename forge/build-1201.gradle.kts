// 1.20.1 ノード。Forge 47.2.30 を legacyforge (moddev-gradle 2.0.142) で扱う / Java 17。
// 既存の neoforge ブランチとは plugin id が違う (net.neoforged.moddev.legacyforge)。
plugins {
    id("java-library")
    id("net.neoforged.moddev.legacyforge") version "2.0.142"
}

val mcVersion = "1.20.1"
val mcVersionRange = "[1.20.1, 1.22)"
val forgeVersion = "47.2.30"
val forgeLoaderRange = "[47,)"
val javaVersion = 17
val compatLibsDir = rootProject.file("forge/compat-libs-1.20.1")

group = "com.kuronami.musicdiscmaker"
version = "${rootProject.property("version")}+$mcVersion"

// MOD のメタデータに載る版は接尾辞を持たない (出荷の mods.toml / fabric.mod.json / manifest は "2.2.3")。
// project.version の接尾辞は jar のファイル名で帯を判別するためだけに要る。
val modVersion = rootProject.property("version") as String

base {
    archivesName = "${rootProject.property("mod_id")}-forge-$mcVersion"
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
}

repositories {
    mavenCentral()
    // mixin 0.8.5-SNAPSHOT:processor は Sponge の snapshot リポにしか無い (mod-048 の settings.gradle と同じ)。
    maven("https://repo.spongepowered.org/repository/maven-public/") {
        content { includeGroupAndSubgroups("org.spongepowered") }
    }
}

val commonPath = ":common:${stonecutter.current.project}"
evaluationDependsOn(commonPath)
val commonProject = project(commonPath)
val commonMain = commonProject.extensions.getByType<SourceSetContainer>()["main"]
val commonGenerate = commonProject.tasks.named("stonecutterGenerate")

legacyForge {
    version = "$mcVersion-$forgeVersion"
    validateAccessTransformers = true
    val at = commonProject.file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) accessTransformers.from(at.absolutePath)
    parchment {
        minecraftVersion = "1.20.1"
        mappingsVersion = "2023.09.03"
    }
    mods {
        create(rootProject.property("mod_id") as String) {
            sourceSet(sourceSets.main.get())
        }
    }
}

mixin {
    add(sourceSets.main.get(), "${rootProject.property("mod_id")}.refmap.json")
    config("${rootProject.property("mod_id")}.mixins.json")
    config("${rootProject.property("mod_id")}.forge.mixins.json")
}

dependencies {
    compileOnly("org.jetbrains:annotations:24.1.0")
    annotationProcessor("org.spongepowered:mixin:0.8.5-SNAPSHOT:processor")
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
    commonMain.resources.srcDirs.forEach {
        from(it) {
            exclude("assets/music_disc_maker/lang/**")
            exclude("assets/music_disc_maker/textures/block/music_disc_maker_*.png")
            exclude("assets/music_disc_maker/models/block/music_disc_maker.json")
            exclude("assets/music_disc_maker/textures/gui/music_disc_maker.png")
        }
    }
    // Language keys do not vary by Minecraft version; use the shared source directly.
    from(rootProject.file("common/src/main/resources/assets/music_disc_maker/lang")) {
        into("assets/music_disc_maker/lang")
    }
    // Maker textures are shared across all versions, including the v3 remake.
    from(rootProject.file("common/src/main/resources/assets/music_disc_maker/textures/block")) {
        include("music_disc_maker_*.png")
        into("assets/music_disc_maker/textures/block")
    }
    // The Maker model and menu layout also use the current shared assets.
    from(rootProject.file("common/src/main/resources")) {
        include("assets/music_disc_maker/models/block/music_disc_maker.json")
        include("assets/music_disc_maker/textures/gui/music_disc_maker.png")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // 1.21+ 専用のデータ形式を 1.20.1 の jar に入れない。
    // 共有 resources は 1.21+ の形（単数形ディレクトリ・jukebox_song・assets/items）で持っており、
    // 1.20.1 は自分側で複数形（recipes / advancements / loot_tables / tags/items / tags/blocks）と
    // models/item を持つ。duplicatesStrategy=EXCLUDE は「同じパスなら 1.20.1 側が勝つ」だけなので、
    // パスの形が違う 1.21+ 専用ファイルは弾かれずにそのまま同梱されていた
    // (2026-09-03 実測: 出荷 jar に 69 本。読み飛ばされるので壊れてはいないが出荷物としては劣化)。
    exclude("data/*/jukebox_song/**")   // jukebox_song レジストリは 1.21 で追加
    exclude("assets/*/items/**")        // アイテムモデル定義は 1.21.4+
    exclude("data/*/advancement/**")    // 1.20.1 は advancements/ (複数形)
    exclude("data/*/recipe/**")         // 1.20.1 は recipes/
    exclude("data/*/loot_table/**")     // 1.20.1 は loot_tables/
    exclude("data/*/tags/block/**")     // 1.20.1 は tags/blocks/
    exclude("data/*/tags/item/**")      // 1.20.1 は tags/items/

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
        "forge_version" to forgeVersion,
        "forge_loader_version_range" to forgeLoaderRange,
        "java_version" to javaVersion
    )
    filesMatching(listOf("pack.mcmeta", "META-INF/mods.toml", "*.mixins.json")) {
        expand(expandProps)
    }
    inputs.properties(expandProps)
}

// Forge 47 が mixin config を探す経路は「MixinConfigs マニフェスト属性」か「mods.toml の [[mixins]]」の
// 2つだけで、mod-048 は前者で出荷している (mods.toml に [[mixins]] は無い)。書かないと mixin が1つも
// 読まれず、ビルドは緑のまま JukeboxBlockEntityMixin ごと効かない jar が出る。
tasks.named<Jar>("jar") {
    val modId = rootProject.property("mod_id")
    manifest.attributes(mapOf(
        "MixinConfigs" to "$modId.mixins.json,$modId.forge.mixins.json"
    ))
}

// ─── LavaPlayer 一式を mod jar に同梱 (隔離 classloader が runtime ロード) ───
// 帯によらず同じ 21 本 (impl 1 + 依存 20) が入る。出荷中の 9 本の jar と本数・名前が一致する。
// legacyforge の最終成果物は jar (reobf は class のみ remap し非 class リソースは保持する)。
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
        "Implementation-Title" to "forge",
        "Implementation-Version" to modVersion,
        "Implementation-Vendor" to rootProject.property("mod_author"),
        "Built-On-Minecraft" to mcVersion
    ))
}

tasks.register("printNodeInfo") {
    doLast {
        val loc = Class.forName("net.neoforged.moddevgradle.legacyforge.internal.LegacyForgeModDevPlugin")
            .protectionDomain.codeSource.location
        println("### node=${stonecutter.current.project} mc=$mcVersion forge=$forgeVersion java=$javaVersion legacyforgeJar=$loc")
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

legacyForge {
    runs {
        create("client") {
            client()
            systemProperty("musicdiscmaker.dev", lavaDevPath)
        }
        create("server") {
            server()
            systemProperty("musicdiscmaker.dev", lavaDevPath)
        }
        // Forge 47 の GameTestMain が行うのは、この 2 property を立てて通常の server Main へ
        // 委譲することだけ。server() の ModLauncher / userdev 引数を保ったまま同じ分岐を通す。
        create("gameTestServer") {
            server()
            systemProperty("musicdiscmaker.dev", lavaDevPath)
            systemProperty("forge.enableGameTest", "true")
            systemProperty("forge.gameTestServer", "true")
            systemProperty("forge.enabledGameTestNamespaces", rootProject.property("mod_id") as String)
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
