plugins {
    id("java-library")
    id("net.neoforged.moddev") version "2.0.142"
}

group = "com.kuronami.musicdiscmaker"
version = "${rootProject.property("version")}+${stonecutter.current.version}"

// MC 26.1 以降は Java 25、それ未満は Java 21。
val requiredJava: JavaVersion = when {
    stonecutter.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    else -> JavaVersion.VERSION_21
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
}

neoForge {
    neoFormVersion = stonecutter.properties["deps.neoform"]
    val at = file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) accessTransformers.from(at.absolutePath)
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.spongepowered:mixin:0.8.5")
    compileOnly("io.github.llamalad7:mixinextras-common:0.3.5")
    annotationProcessor("io.github.llamalad7:mixinextras-common:0.3.5")
}

tasks.register("printJavaVersion") {
    doLast {
        println("### node=${stonecutter.current.project} requiredJava=$requiredJava toolchain=${java.toolchain.languageVersion.get()}")
        println("### javaSrcDirs=${sourceSets.main.get().java.srcDirs}")
        println("### resSrcDirs=${sourceSets.main.get().resources.srcDirs}")
    }
}

// ─── JUnit 層 ───────────────────────────────────────────────────────────
// 依存展開の入口 (JarFolders) は loader API を触らないので headless で回る。
// 「content root が jar ファイルそのもの」という production 限定の状況を
// ここで固定する (dev は展開済みディレクトリなので実機まで踏めない)。
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // DependencyManager は slf4j でログを出す。MC の classpath は test runtime に
    // 来ないので、headless で回すぶんだけ自前で持たせる (production jar には無影響)。
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Active-node resources list the version overlay before shared resources.
// Preserve the 26.2 mixin override, as loader processResources already does.
tasks.named<ProcessResources>("processResources") {
    filesMatching("music_disc_maker.mixins.json") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}
