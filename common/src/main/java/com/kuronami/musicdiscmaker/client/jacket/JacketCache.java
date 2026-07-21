package com.kuronami.musicdiscmaker.client.jacket;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.net.ssl.HttpsURLConnection;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.network.UrlGuard;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * ジャケット画像の client 側キャッシュ。URL を非同期で DL → ディスクキャッシュ → {@link DynamicTexture}
 * 登録し、{@link ResourceLocation} を返す。すべて fail-soft (失敗しても再生・GUI に影響しない)。
 *
 * <p>スレッド: DL/デコードは専用スレッド、texture 登録だけ render スレッド ({@link Minecraft#execute})。
 * 上限: メモリ内 texture 数と on-disk 合計サイズを制限。https 以外の URL は拒否。
 */
public final class JacketCache {

    /** DL する画像の最大バイト数 (細工された巨大画像で OOM しない)。 */
    private static final int MAX_IMAGE_BYTES = 3_000_000;
    /** デコード後の各辺の最大ピクセル数 (デコード爆弾対策: 圧縮率が高い画像は byte 上限を抜ける)。 */
    private static final int MAX_IMAGE_DIM = 4096;
    /** デコード後の総ピクセル数上限 (4096×4096 ≈ 1677万px ≈ 64MB@RGBA)。 */
    private static final long MAX_IMAGE_PIXELS = 4096L * 4096L;
    /** メモリに保持する texture の上限枚数 (LRU で追い出す)。 */
    private static final int IN_MEMORY_MAX = 48;
    /** on-disk キャッシュの合計サイズ上限。 */
    private static final long DISK_CACHE_MAX_BYTES = 32L * 1024L * 1024L;
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 6000;
    /** リダイレクトの追従回数上限 (初回接続を含む総接続試行数)。各ホップで SSRF 再検査する。 */
    private static final int MAX_REDIRECTS = 5;
    private static final String CACHE_DIR = "mdm_jacket_cache";

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, runnable -> {
        final Thread t = new Thread(runnable, "music_disc_maker-jacket");
        t.setDaemon(true);
        return t;
    });

    // 登録済み texture (アクセス順 LRU)。render スレッドからのみ触る。
    private static final LinkedHashMap<String, Jacket> READY =
            new LinkedHashMap<>(16, 0.75F, true);
    // DL 中の URL ハッシュ (再要求のスパムを防ぐ)。load 完了/失敗で除かれるので同時実行数で有界。
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    /** 恒久失敗の URL ハッシュ。FIFO で上限を設け、セッション中の単調増加を防ぐ。 */
    private static final int FAILED_MAX = 512;
    private static final Set<String> FAILED = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<String, Boolean>(64, 0.75F, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > FAILED_MAX;
                }
            }));

    private JacketCache() {
    }

    /** 準備済みジャケット: texture の場所と元画像サイズ (アスペクト比の維持に使う)。 */
    public record Jacket(ResourceLocation texture, int width, int height) {
    }

    /**
     * ジャケット URL に対応するジャケットを返す。まだ無ければ非同期 DL を開始して {@code null} を返す
     * (準備できたら次フレーム以降で non-null になる)。render スレッドから呼ぶこと。
     */
    public static Jacket get(String url) {
        // render スレッド: DNS を伴わない安価な scheme チェックのみ (完全なガードは loadAsync で off-thread)。
        if (url == null || url.isBlank() || !isHttpsScheme(url)) {
            return null;
        }
        final String hash = hash(url);
        final Jacket ready = READY.get(hash); // LRU: get がアクセス順を更新
        if (ready != null) {
            return ready;
        }
        if (FAILED.contains(hash) || !PENDING.add(hash)) {
            return null; // 失敗確定 or DL 中
        }
        POOL.submit(() -> loadAsync(url, hash));
        return null;
    }

    /**
     * 画像 URL のガード: scheme を https に限定し、さらに {@link UrlGuard} で内部・予約 IP (SSRF) を遮断する。
     * {@code thumbnailUrl} はサーバ同期のデータコンポーネント = 攻撃者制御値なので必須。
     * DNS 解決を伴うので render スレッドから呼ばない (呼び出しは {@link #loadAsync} / {@link #download} = 専用スレッド)。
     */
    static boolean isAllowedImageUrl(String url) {
        // ジャケット取得は https のみ (UrlGuard は http も許すので scheme をここで絞る)。
        if (!isHttpsScheme(url)) {
            return false;
        }
        // 内部・予約 IP (クラウドメタデータ・LAN・ループバック等) を遮断。
        return UrlGuard.inspect(url) == UrlGuard.Reason.OK;
    }

    /** scheme が https か (DNS を伴わない安価な判定・render スレッド安全)。 */
    private static boolean isHttpsScheme(String url) {
        try {
            final String scheme = URI.create(url).getScheme();
            return scheme != null && scheme.equalsIgnoreCase("https");
        } catch (final RuntimeException ex) {
            return false;
        }
    }

    private static void loadAsync(String url, String hash) {
        // off-thread: https + SSRF(内部 IP) の完全ガード。DNS 解決を伴うので render スレッドから外してある。
        if (!isAllowedImageUrl(url)) {
            markFailed(hash);
            return;
        }
        try {
            byte[] bytes = readFromDisk(hash);
            if (bytes == null) {
                bytes = download(url);
                if (bytes != null) {
                    writeToDisk(hash, bytes);
                }
            }
            if (bytes == null) {
                markFailed(hash);
                return;
            }
            // デコード前に寸法を検査してデコード爆弾を弾く (byte 上限だけでは展開後サイズを防げない)。
            if (!dimensionsWithinLimit(bytes)) {
                MusicDiscMaker.LOGGER.debug("ジャケット拒否 (寸法超過/不明): {}", url);
                markFailed(hash);
                return;
            }
            // NativeImage.read は PNG 署名を必須とする (PngInfo.validateHeader) = PNG 専用。
            // YouTube サムネ等の JPEG は ImageIO で decode → PNG に再エンコードしてから渡す。
            final byte[] pngBytes = toPngBytes(bytes);
            final NativeImage image;
            try (InputStream in = new ByteArrayInputStream(pngBytes)) {
                image = NativeImage.read(in);
            }
            // texture 登録は render スレッドで行う (off-thread 登録は OpenGL 破壊のバグ源)。
            Minecraft.getInstance().execute(() -> register(hash, image));
        } catch (final Throwable t) {
            MusicDiscMaker.LOGGER.debug("ジャケット取得失敗 ({}): {}", url, t.toString());
            markFailed(hash);
        }
    }

    private static void register(String hash, NativeImage image) {
        try {
            final int w = image.getWidth();
            final int h = image.getHeight();
            final ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(
                    MusicDiscMaker.MODID, "jacket/" + hash);
            Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(image));
            READY.put(hash, new Jacket(rl, w, h));
            evictIfNeeded();
        } catch (final Throwable t) {
            image.close();
            markFailed(hash);
        } finally {
            PENDING.remove(hash);
        }
    }

    /** LRU: 上限を超えたら最古の texture を解放する (render スレッド)。 */
    private static void evictIfNeeded() {
        while (READY.size() > IN_MEMORY_MAX) {
            final var it = READY.entrySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            final ResourceLocation oldest = it.next().getValue().texture();
            it.remove();
            try {
                Minecraft.getInstance().getTextureManager().release(oldest);
            } catch (final Throwable ignored) {
                // release 失敗は無視 (次回起動で GC される)
            }
        }
    }

    private static void markFailed(String hash) {
        FAILED.add(hash);
        PENDING.remove(hash);
    }

    /** PNG 署名 (89 50 4E 47 0D 0A 1A 0A)。 */
    private static boolean isPng(byte[] b) {
        return b.length >= 8
                && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A
                && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0x0A;
    }

    /**
     * {@link NativeImage#read} は PNG 署名を必須とする ({@code PngInfo.validateHeader}) ため PNG 専用。
     * PNG ならそのまま返し、JPEG 等はいったん {@link ImageIO} で decode → PNG に再エンコードして返す。
     * 寸法は呼び出し前に {@link #dimensionsWithinLimit} で検査済み。
     */
    private static byte[] toPngBytes(byte[] bytes) throws IOException {
        if (isPng(bytes)) {
            return bytes;
        }
        final java.awt.image.BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) {
            throw new IOException("ImageIO が画像を decode できない");
        }
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", out)) {
            throw new IOException("PNG への再エンコードに失敗 (writer 無し)");
        }
        return out.toByteArray();
    }

    /**
     * デコード前に画像ヘッダから寸法を読み、上限超えを弾く (デコード爆弾対策)。ヘッダだけを読むので
     * ピクセルは展開しない。寸法を読めない/対応外フォーマットの画像は安全側で拒否する。
     */
    private static boolean dimensionsWithinLimit(byte[] bytes) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (iis == null) {
                return false;
            }
            final Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return false; // 対応外フォーマット
            }
            final ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                final int w = reader.getWidth(0);
                final int h = reader.getHeight(0);
                return w > 0 && h > 0 && w <= MAX_IMAGE_DIM && h <= MAX_IMAGE_DIM
                        && (long) w * h <= MAX_IMAGE_PIXELS;
            } finally {
                reader.dispose();
            }
        } catch (final Exception ex) {
            return false; // 寸法を読めない画像は拒否
        }
    }

    private static byte[] download(String startUrl) throws IOException {
        String current = startUrl;
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            // リダイレクトを手動追従し、各ホップの解決先 IP を再検査する (redirect による SSRF を塞ぐ)。
            if (!isAllowedImageUrl(current)) {
                return null;
            }
            final URLConnection raw = URI.create(current).toURL().openConnection();
            if (!(raw instanceof HttpsURLConnection conn)) {
                return null; // https 以外は弾く
            }
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false); // 手動で追い、各リダイレクト先を再検査する
            conn.setRequestProperty("User-Agent", "MusicDiscMaker");
            conn.connect();
            final int code = conn.getResponseCode();
            if (code / 100 == 3) {
                final String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isBlank()) {
                    return null;
                }
                current = URI.create(current).resolve(location).toString(); // 相対 Location も解決
                continue;
            }
            if (code / 100 != 2) {
                conn.disconnect();
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                final byte[] buf = new byte[8192];
                final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_IMAGE_BYTES) {
                        return null; // 上限超過は破棄
                    }
                }
                return out.toByteArray();
            } finally {
                conn.disconnect();
            }
        }
        return null; // リダイレクトが多すぎる
    }

    private static Path cacheDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(CACHE_DIR);
    }

    private static byte[] readFromDisk(String hash) {
        try {
            final Path f = cacheDir().resolve(hash + ".png");
            if (Files.isRegularFile(f)) {
                return Files.readAllBytes(f);
            }
        } catch (final IOException ignored) {
            // 読めなければ DL に回す
        }
        return null;
    }

    private static void writeToDisk(String hash, byte[] bytes) {
        try {
            final Path dir = cacheDir();
            Files.createDirectories(dir);
            Files.write(dir.resolve(hash + ".png"), bytes);
            pruneDisk(dir);
        } catch (final IOException ignored) {
            // ディスクに書けなくてもメモリ内では使える
        }
    }

    /** on-disk 合計が上限を超えたら古いファイルから削除する。 */
    private static void pruneDisk(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            final List<Path> files = new ArrayList<>(stream.filter(Files::isRegularFile).toList());
            long total = 0;
            for (final Path f : files) {
                total += Files.size(f);
            }
            if (total <= DISK_CACHE_MAX_BYTES) {
                return;
            }
            files.sort(Comparator.comparingLong(p -> lastModified(p)));
            for (final Path f : files) {
                if (total <= DISK_CACHE_MAX_BYTES) {
                    break;
                }
                final long size = safeSize(f);
                if (Files.deleteIfExists(f)) {
                    total -= size;
                }
            }
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (final IOException ex) {
            return 0L;
        }
    }

    private static long safeSize(Path p) {
        try {
            return Files.size(p);
        } catch (final IOException ex) {
            return 0L;
        }
    }

    private static String hash(String url) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-1");
            final byte[] digest = md.digest(url.getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (final Exception ex) {
            // SHA-1 は必ず在るが、念のため衝突しにくいフォールバック。
            return Integer.toHexString(url.hashCode());
        }
    }
}
