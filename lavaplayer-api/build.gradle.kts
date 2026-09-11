// mod <-> LavaPlayer impl の橋渡し interface のみ。
// MC / NeoForge / Fabric / Forge には一切依存しない (隔離 classloader と mod classloader の両方から
// 同一クラスとして見える必要があるため、依存ゼロの素の java で保つ)。
//
// 正典ソースは common (各ローダーの mod classloader に載せるため common にコンパイルさせる)。
// このサブプロジェクトは common の api パッケージ「だけ」をコンパイルし、lavaplayer impl が
// MC/common を引かずに interface へコンパイルできるようにする。DRY: ソースは common 一箇所。
//
// 統合ツリーでは common がノードに分かれているが、api パッケージは Stonecutter の指示子を 1 つも
// 持たず、ノード別オーバーレイも無い (全ノードで同一)。よって前処理前の共有ソースを直接読む。
plugins {
    id("java")
}

// 帯によらず Java 17 で出す。impl と api の .class は隔離 classloader / mod classloader が読むだけで、
// major 61 はどの帯の JVM (17 / 21 / 25) でもロードできる。1.20.1 の JVM は 17 なのでここが上限。
java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}

sourceSets.named("main") {
    java.setSrcDirs(listOf(rootProject.file("common/src/main/java")))
    java.include("**/lavaplayer/api/**")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// ─── api パッケージに Stonecutter の指示子が入っていないことを機械で確かめる ───
// このサブプロジェクトは stonecutterGenerate を通さず前処理前の common/src を直接コンパイルする。
// api に指示子が 1 つでも入ると、非アクティブ側のブランチが裸のテキストとして残り、
// 「impl のコンパイルは通るが実行時に mod classloader 側の api と signature が食い違う」形で黙って壊れる。
// 指示子を入れる必要が出たら、この検査を消すのではなく lavaplayer-api を生成物
// (common/versions/<node>/build/generated/stonecutter/main/java) を読む形へ変える。
val checkApiDirectives by tasks.registering {
    val apiRoot = rootProject.file("common/src/main/java/com/kuronami/musicdiscmaker/lavaplayer/api")
    inputs.dir(apiRoot)
    outputs.upToDateWhen { false }
    doLast {
        // Stonecutter の指示子の全形 (条件分岐 //? ・文字列置換 //$ ・ブロック形 /*? /*$)
        val pattern = Regex("""//\?|//\$|/\*\?|/\*\$""")
        val hits = mutableListOf<String>()
        apiRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".java") }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                if (pattern.containsMatchIn(line)) {
                    hits += "${f.relativeTo(rootProject.projectDir).invariantSeparatorsPath}:${i + 1}: ${line.trim()}"
                }
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException(
                "lavaplayer-api: api パッケージに Stonecutter の指示子がある (" + hits.size + " 箇所)。\n" +
                    hits.joinToString("\n") + "\n" +
                    "このサブプロジェクトは前処理前の common/src を読むので、指示子は裸のテキストとして残る。"
            )
        }
        // ノード別オーバーレイも同じ理由で禁止。common/src しか読まないので、
        // common/versions/<node>/src に api のファイルが置かれてもこのサブプロジェクトは見ない。
        // impl は古い api にコンパイルされ、mod classloader にはオーバーレイ版が載る (指示子と同じ壊れ方)。
        val overlays = rootProject.file("common/versions").walkTopDown()
            .filter {
                val q = it.invariantSeparatorsPath
                it.isFile && it.name.endsWith(".java") &&
                    q.contains("/lavaplayer/api/") &&
                    q.contains("/src/main/java/") && !q.contains("/build/")
            }
            .map { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }
            .toList()
        if (overlays.isNotEmpty()) {
            overlays.forEach { logger.error("  オーバーレイ: " + it) }
            throw GradleException(
                "lavaplayer-api: api パッケージにノード別オーバーレイがある (" + overlays.size +
                    " 本・上のログに一覧)。このサブプロジェクトは common/src しか読まないので、その差分は反映されない。"
            )
        }
        logger.lifecycle("lavaplayer-api: api パッケージの指示子 0 件 / オーバーレイ 0 本")
    }
}

tasks.named("compileJava") { dependsOn(checkApiDirectives) }
