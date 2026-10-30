package com.yayuy.overgrownvegetation.item;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * カートリッジの中身 —— 因子の種類 → 個数(順 1 の着手順 8、2026-10-03)。
 *
 * <p>カートリッジのアイテム({@code ModDataComponents#CARTRIDGE_FACTORS})と、パワードアーマーに付いた
 * カートリッジ({@code ModDataComponents#POWERED_ARMOR_CARTRIDGE})の両方がこれを持つ。
 * 種類はキーの文字列({@link CartridgeFactor#id()})なので、後で種類を足しても保存の形は変わらない。
 * 入れた因子は取り出せない(ユーザー指定)ので、減らす操作は持たない。
 */
public record CartridgeContents(Map<String, Integer> counts) {

    public static final CartridgeContents EMPTY = new CartridgeContents(Map.of());

    public static final Codec<CartridgeContents> CODEC =
            Codec.unboundedMap(Codec.STRING, Codec.INT).xmap(CartridgeContents::new, CartridgeContents::counts);

    public static final StreamCodec<ByteBuf, CartridgeContents> STREAM_CODEC =
            ByteBufCodecs.<ByteBuf, String, Integer, Map<String, Integer>>map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT)
                    .map(CartridgeContents::new, CartridgeContents::counts);

    public CartridgeContents {
        counts = Map.copyOf(counts);
    }

    public int count(CartridgeFactor factor) {
        return counts.getOrDefault(factor.id(), 0);
    }

    public int total() {
        int n = 0;
        for (int v : counts.values()) {
            n += v;
        }
        return n;
    }

    public boolean isEmpty() {
        return total() == 0;
    }

    /** {@code factor} を {@code amount} 個足した写し(容量の確かめは呼ぶ側で)。 */
    public CartridgeContents plus(CartridgeFactor factor, int amount) {
        Map<String, Integer> m = new LinkedHashMap<>(counts);
        m.merge(factor.id(), amount, Integer::sum);
        return new CartridgeContents(m);
    }
}
