package com.kuronami.musicdiscmaker.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * server → client: MDM の wire 版 ({@code ModNetwork.PROTOCOL_VERSION}) を参加直後に 1 回伝える
 * play 段ハンドシェイク (B5/C12)。
 *
 * <p>NeoForge は login 段の registrar 交渉で版が食い違う client を弾き、Forge 1.20.1 も
 * {@code acceptMissingOr} で同じことをしている。<b>Fabric には login 段の交渉が無い</b>ため、
 * ここで版を交換して食い違いを検出する (fabric の 4 ノードだけの経路)。
 *
 * <p>client は受信時に自分の版と比較し、食い違ったら警告を出して client 機能を無効化する
 * (接続は切らない - {@code acceptMissingOr} の「MOD を持たない相手は受け入れる」思想と同じ)。
 *
 * <p><b>wire 形式は文字列 1 個。</b>既存 payload には一切触れないので、この payload の追加で
 * 新しい不一致組 (C14 の排除した形) は生まれない。旧クライアントはこの channel を登録していないので、
 * サーバー側は {@code canSend} でそれを見て送らない (盲送りしない)。
 */
public record VersionPayload(String protocolVersion) implements ModPayload {

    public static final String PATH = "protocol_version";

    /** 現在のビルドの wire 版を運ぶ payload を作るファクトリ。 */
    public static VersionPayload current() {
        return new VersionPayload(ModNetwork.PROTOCOL_VERSION);
    }

    public static VersionPayload read(FriendlyByteBuf buf) {
        return new VersionPayload(buf.readUtf());
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(protocolVersion);
    }
}
