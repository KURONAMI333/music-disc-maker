// 1.20.1 ノード。legacyforge (MCP マッピング) / Java 17。
// 既存2ノードは net.neoforged.moddev + neoFormVersion だが、1.20.1 は NeoForm が無いので
// 同じ artifact (moddev-gradle 2.0.142) が出しているもう一つの plugin id を使う。
plugins {
    id("java-library")
    id("net.neoforged.moddev.legacyforge") version "2.0.142"
}

group = "com.kuronami.musicdiscmaker"
version = "${rootProject.property("version")}+${stonecutter.current.version}"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}

legacyForge {
    mcpVersion = "1.20.1"
    val at = file("src/main/resources/META-INF/accesstransformer.cfg")
    if (at.exists()) accessTransformers.from(at.absolutePath)
    parchment {
        minecraftVersion = "1.20.1"
        mappingsVersion = "2023.09.03"
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Valkyrien Skies 2 の API stub (mod 本体は非同梱の soft-dep)。mod-048 の common/compat-libs と同じ jar。
    compileOnly(files(rootProject.file("common/compat-libs-1.20.1/valkyrienskies-api-2.4.11.jar")))
    compileOnly("org.spongepowered:mixin:0.8.5")
    compileOnly("com.google.code.findbugs:jsr305:3.0.1")
    compileOnly("org.jetbrains:annotations:24.1.0")
}

tasks.register("printJavaVersion") {
    doLast {
        val loc = Class.forName("net.neoforged.moddevgradle.legacyforge.internal.LegacyForgeModDevPlugin")
            .protectionDomain.codeSource.location
        println("### node=${stonecutter.current.project} toolchain=${java.toolchain.languageVersion.get()} legacyforgeJar=$loc")
        println("### javaSrcDirs=${sourceSets.main.get().java.srcDirs}")
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
