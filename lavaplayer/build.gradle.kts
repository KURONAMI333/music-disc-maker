// LavaPlayer を使う impl サブプロジェクト。
//  - 自前の impl クラスは普通の jar にするが、拡張子を .jar.packed にして
//    mod scanner に拾わせない (隔離 classloader が後で読む)。
//  - LavaPlayer + 推移依存は library configuration で解決し、個別 .jar.packed として
//    build/dependencies に書き出す。これらは mod jar の dependencies/ に同梱される。
//
// ソースは MC にも loader にも触れないので帯によらず 1 本 (net.minecraft への参照が 0 件)。
// Stonecutter のノードを持たない素のサブプロジェクトのままでよい。
plugins {
    id("java")
}

// 帯によらず Java 17 で出す。隔離 classloader が読む .class の major 61 は
// どの帯の JVM (17 / 21 / 25) でもロードできる。1.20.1 の JVM が 17 なのでここが上限。
java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}

// 同梱する LavaPlayer 系の依存。commons-logging は jcl-over-slf4j で置き換える。
val library: Configuration by configurations.creating {
    exclude(mapOf("group" to "commons-logging", "module" to "commons-logging"))
}
configurations.named("implementation") { extendsFrom(library) }

repositories {
    maven("https://repo.u-team.info")          // HyCraftHD fork (lavaplayer 2.2.4-fix-j8)
    maven("https://maven.arbjerg.dev/releases") // lavaplayer 本家系 / lavadsp
    maven("https://maven.topi.wtf/releases")    // lavasrc (v1.1+ Spotify 用、現状未使用)
    maven("https://maven.lavalink.dev/releases") // youtube-source (Maven Central 未配布)
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    // 隔離 classloader 側にロードされる LavaPlayer 一式。
    //  - lavaplayer fork 2.2.4-fix-j8 : repo.u-team.info の最新
    //  - youtube-source v2 : release 1.18.2
    library("net.hycrafthd.lavaplayer:lavaplayer:2.2.4-fix-j8")
    library("dev.lavalink.youtube:v2:1.18.2")
    library("org.slf4j:jcl-over-slf4j:2.0.17")  // httpclient の commons-logging を slf4j へ橋渡し

    // ALAC (Apple Lossless) の decoder。M4A/ALAC の入力はこのコアで直接 decode する。
    //  座標      : com.tianscar.javasound:javasound-alac:0.2.3
    //  ライセンス: BSD-3-Clause (Copyright (c) 2011-2014 Peter McQuillan /
    //              Based on the ALAC decoder - Copyright (c) 2005 David Hammerton)
    //              全文は src/main/resources/META-INF/licenses/javasound-alac-LICENSE.txt
    //  原典      : soiaf/Java-Apple-Lossless-decoder の Java ポートを Tianscar が Maven Central で配布。
    //              配布元 GitHub (Tianscar/javasound-alac) は 2026-11 時点で 404 なので、
    //              Maven Central の jar を版・SHA-256 で固定して使う。
    //  SHA-256   : d5cb14c1c36fdad2b64a7892ada4f14d7cb5a675960ce5ac73b96a93e46a7d7f
    // 推移依存 javasound-resloader は javax.sound 向け SPI だけが参照するので入れない。
    add(library.name, "com.tianscar.javasound:javasound-alac:0.2.3") {
        isTransitive = false
    }

    // native libconnector を load できない環境 (Android = Bionic libc) 用の pure-Java codec。
    // com.sedmelluq...natives の差し替えクラスが NativeCodecs の判定でこちらへ切り替える。
    library("io.github.jaredmdobson:concentus:1.0.2") // Opus decode / encode (BSD-3-Clause)
    library("javazoom:jlayer:1.0.1")                  // MP3 (LGPL-2.1)
    library("de.sfuhrm:jaad:0.8.7")                   // AAC (Public Domain)
    library("org.jcraft:jorbis:0.0.17")               // Vorbis (LGPL-2.0)

    // 橋渡し interface (mod classloader 側に置かれるので packed には含めない)
    implementation(project(":lavaplayer-api"))

    // slf4j-api は mod 側が提供 (隔離 classloader が org.slf4j を mod へ委譲する) → compileOnly
    compileOnly("org.slf4j:slf4j-api:2.0.17")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // -Dmusicdiscmaker.javaCodecs=true で pure-Java codec (Android 経路) のままテストを回せる
    providers.systemProperty("musicdiscmaker.javaCodecs").orNull?.let { systemProperty("musicdiscmaker.javaCodecs", it) }
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// impl jar を .jar.packed として出力 (mod scanner 回避)。
// archiveVersion を空にして版番号を名前から外す。版番号入りだと bump のたびに
// build/libs に旧版 lavaplayer-x.y.z.jar.packed が残り、隔離 classloader が
// 同一 impl クラスを多重ロードするため (DependencyManager は *.jar.packed を名前非依存で全読み込み)。
tasks.named<Jar>("jar") {
    archiveExtension = "jar.packed"
    archiveVersion = ""
}

// javasound-alac は SPI (javax.sound 向け) と推移依存 javasound-resloader を含む。
// 隔離 DependencyClassLoader は parent が null で javax.sound も resloader も見えないため、
// 使わない SPI を残すと NoClassDefFound の温床になる。コアだけ (約 33KB) を 1 jar に詰め直す。
val alacJar = library.elements.map { files ->
    files.map { it.asFile }.first { it.name.startsWith("javasound-alac-") }
}
val repackAlac = tasks.register<Jar>("repackAlac") {
    from(alacJar.map { zipTree(it) }) {
        exclude("META-INF/services/**")
        exclude("com/beatofthedrum/alacdecoder/spi/**")
    }
    archiveFileName.set("javasound-alac-0.2.3.jar")
    destinationDirectory.set(layout.buildDirectory.dir("repacked"))
}

// src/main/java/com/sedmelluq/.../natives に同名の差し替えを置いた LavaPlayer の native wrapper。
// 隔離 classloader は jar を名前順に並べるだけなので、同名クラスが 2 つあると勝ち負けが
// 名前次第になる。元の lavaplayer jar から抜いた版を同梱して、差し替え側だけが存在するようにする。
val nativeWrapperOverrides = listOf(
    "opus/OpusDecoder", "opus/OpusEncoder", "mp3/Mp3Decoder",
    "aac/AacDecoder", "vorbis/VorbisDecoder", "samplerate/SampleRateConverter")
val lavaplayerJarName = Regex("""lavaplayer-\d.*\.jar""")
val packedLibraries = library.copyRecursive().apply {
    exclude(mapOf("group" to "org.slf4j", "module" to "slf4j-api"))
    exclude(mapOf("group" to "com.tianscar.javasound", "module" to "javasound-alac"))
}

val repackLavaplayer = tasks.register<Jar>("repackLavaplayer") {
    val lavaplayerJar = packedLibraries.filter { lavaplayerJarName.matches(it.name) }
    from({ lavaplayerJar.map { zipTree(it) } }) {
        nativeWrapperOverrides.forEach {
            exclude("com/sedmelluq/discord/lavaplayer/natives/$it.class")
            exclude("com/sedmelluq/discord/lavaplayer/natives/$it\$*.class")
        }
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName = provider { lavaplayerJar.singleFile.name }
    destinationDirectory = layout.buildDirectory.dir("repacked")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// LavaPlayer 依存を build/dependencies/*.jar.packed に書き出す。
// slf4j-api だけは mod 側提供なので除外。
// javasound-alac は生の jar を入れず、上の repackAlac (SPI 除去後) だけを入れる。
// Sync = 宛先の余分なファイルを削除する。依存の版を上げた時に旧版の .jar.packed が
// 残ると隔離 classloader が両版を二重ロードするため、Copy ではなく Sync を使う。
tasks.register<Sync>("copyDependencies") {
    from(repackAlac)
    from(repackLavaplayer)
    from(packedLibraries.filter { !lavaplayerJarName.matches(it.name) })
    into(layout.buildDirectory.dir("dependencies"))
    rename { it + ".packed" }
}

tasks.named("assemble") {
    dependsOn(tasks.named("copyDependencies"))
}
