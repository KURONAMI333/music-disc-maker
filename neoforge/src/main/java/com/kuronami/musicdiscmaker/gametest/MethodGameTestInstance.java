package com.kuronami.musicdiscmaker.gametest;

//? if >=1.21.2 {
import java.util.function.Consumer;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * MOD 側テスト本体 ({@code static void xxx(GameTestHelper)}) を 26.2 のデータ駆動 GameTest
 * に載せる {@link GameTestInstance}。
 *
 * <p>vanilla の {@code FunctionGameTestInstance} は本体を {@code Registries.TEST_FUNCTION}
 * レジストリから引くが、NeoForge 26.2 には MOD の Consumer をそこへ入れる正規経路が無い。
 * このサブクラスはメソッド参照を直接保持して {@link #run} で呼ぶので、テスト 1 本あたりの
 * 登録が TEST_INSTANCE への 1 回で済む。
 *
 * <p>{@link #codec()} は BUILT_IN 登録 (コードから直接 new する) では決して直列化されない
 * (テスト instance は {@code TestInstanceBlockEntity} には ResourceKey としてのみ記録される)。
 * それでも抽象メソッドなので、TestData 部分だけを丸める自己整合 codec を返しておく。
 * もし未来に BUILT_IN でない経路で直列化する必要が出たら、ここを MapCodec レジストリ
 * ({@code TEST_INSTANCE_TYPE}) 登録付きの本物の codec に差し替える。
 */
public final class MethodGameTestInstance extends GameTestInstance {

    private final Consumer<GameTestHelper> method;

    //? if >=26.1 {
    public MethodGameTestInstance(Consumer<GameTestHelper> method, TestData<Holder<TestEnvironmentDefinition<?>>> info) {
    //?} else {
    /*public MethodGameTestInstance(Consumer<GameTestHelper> method, TestData<Holder<TestEnvironmentDefinition>> info) {
    *///?}
        super(info);
        this.method = method;
    }

    @Override
    public void run(GameTestHelper helper) {
        this.method.accept(helper);
    }

    @Override
    public MapCodec<? extends GameTestInstance> codec() {
        return TestData.CODEC.xmap(info -> new MethodGameTestInstance(method -> { }, info), i -> i.info());
    }

    @Override
    protected MutableComponent typeDescription() {
        return Component.literal("method");
    }
}
//?} else {
//?}
