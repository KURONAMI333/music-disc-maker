package com.kuronami.musicdiscmaker.depend;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 同梱 jar の展開先を決め、既存の展開を再利用してよいかを判定する純ロジック。
 * ファイルシステムに触らないので GameTest から直接固定できる。
 *
 * <p>元の実装は起動のたびに展開先を丸ごと消して現在時刻の名前で作り直しており、
 * 毎回 34MB を書き直していた。つまり<b>起動のたびに失敗の窓を開けていた</b>
 * (ウイルス対策の走査・temp の空き・権限のどれに当たってもそこで死ぬ)。ここは
 * 「中身が同じなら同じ場所」を決めて、揃っていれば展開ごと飛ばすためにある。
 *
 * <p>展開先の名前を MOD のバージョン文字列でなく<b>同梱 jar 一式の指紋</b>にしているのは、
 * バージョン文字列を runtime に持ち込む配線 (build での定数注入) が要らないため。
 * 依存が変われば指紋も変わるので版の切り替わりは自動で拾えるし、版が上がっても
 * 依存が同一なら再利用が正しい。ファイルの更新時刻は入れない — reproducible build は
 * zip の時刻をゼロに潰すし、dev ではビルドのたびに変わって指紋が無意味に流れる。
 */
public final class ExtractionPlan {

    /** 展開されるべき 1 ファイル。 */
    public record Entry(String name, long size) {
    }

    /** 展開先ディレクトリの観測 (テストでは差し替える)。 */
    public interface DirectoryProbe {

        /** 完了マーカーが在るか。 */
        boolean markerPresent();

        /** そのファイルのサイズ。存在しなければ {@code -1}。 */
        long sizeOf(String name);
    }

    private ExtractionPlan() {
    }

    /**
     * 同梱 jar が 1 つも見つからない状態をここで止める。
     *
     * <p>ここを素通りさせると空の classloader が組み上がり、後段が
     * {@code ClassNotFoundException} という原因の分からない形で落ちる。
     */
    public static List<Entry> requireNonEmpty(List<Entry> entries, String searchedIn) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalStateException(
                    "No bundled LavaPlayer jars (*.jar.packed) were found in " + searchedIn
                            + ". The mod jar is incomplete, or the dependencies folder could not be read.");
        }
        return entries;
    }

    /**
     * 同梱 jar 一式の指紋。名前とサイズだけから決まるので、同じ mod jar なら
     * 起動をまたいで同じ値になる。
     */
    public static String fingerprint(List<Entry> entries) {
        final List<Entry> sorted = sorted(entries);
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", ex);
        }
        for (final Entry entry : sorted) {
            digest.update(entry.name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(entry.size()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    }

    /**
     * 既存の展開を再利用してよいか。
     *
     * <p><b>壊れた展開を掴まないこと</b>がこの判定の仕事。マーカーは全ファイルの
     * 置き換えが済んだ後にしか書かれず、個々のファイルは一時名で書いてから
     * 原子的に最終名へ移すので、「書きかけのファイルが最終名で見えている」状態は
     * 起こらない。それでも念のためサイズを 1 件ずつ突き合わせる (人が消した・
     * ディスクが溢れて 0 バイトになった、を拾う)。
     */
    public static boolean canReuse(List<Entry> expected, DirectoryProbe probe) {
        if (expected == null || expected.isEmpty()) {
            return false;
        }
        if (!probe.markerPresent()) {
            return false;
        }
        for (final Entry entry : expected) {
            if (probe.sizeOf(entry.name()) != entry.size()) {
                return false;
            }
        }
        return true;
    }

    /** 完了マーカーの中身。人が開いて何が入っているか読めるようにしてある。 */
    public static String renderMarker(List<Entry> entries) {
        final StringBuilder sb = new StringBuilder();
        for (final Entry entry : sorted(entries)) {
            sb.append(entry.name()).append('\t').append(entry.size()).append('\n');
        }
        return sb.toString();
    }

    private static List<Entry> sorted(List<Entry> entries) {
        final List<Entry> copy = new ArrayList<>(entries);
        copy.sort(Comparator.comparing(Entry::name));
        return copy;
    }
}
