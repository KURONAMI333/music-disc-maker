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

// src/main/java/com/sedmelluq/.../natives に同名の差し替えを置いた LavaPlayer の native wrapper。
// 隔離 classloader は jar を名前順に並べるだけなので、同名クラスが 2 つあると勝ち負けが
// 名前次第になる。元の lavaplayer jar から抜いた版を同梱して、差し替え側だけが存在するようにする。
val nativeWrapperOverrides = listOf(
    "opus/OpusDecoder", "opus/OpusEncoder", "mp3/Mp3Decoder",
    "aac/AacDecoder", "vorbis/VorbisDecoder", "samplerate/SampleRateConverter")
val lavaplayerJarName = Regex("""lavaplayer-\d.*\.jar""")
val packedLibraries = library.copyRecursive().apply {
    exclude(mapOf("group" to "org.slf4j", "module" to "slf4j-api"))
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
// Sync = 宛先の余分なファイルを削除する。依存の版を上げた時に旧版の .jar.packed が
// 残ると隔離 classloader が両版を二重ロードするため、Copy ではなく Sync を使う。
tasks.register<Sync>("copyDependencies") {
    from(packedLibraries.filter { !lavaplayerJarName.matches(it.name) })
    from(repackLavaplayer)
    into(layout.buildDirectory.dir("dependencies"))
    rename { it + ".packed" }
}

tasks.named("assemble") {
    dependsOn(tasks.named("copyDependencies"))
}
