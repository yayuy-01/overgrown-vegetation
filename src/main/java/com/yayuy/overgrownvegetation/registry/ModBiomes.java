package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

/**
 * このMODが使うバイオームの {@link ResourceKey} 一覧。
 *
 * <p>バイオームはデータ駆動({@code data/overgrown_vegetation/worldgen/biome/*.json})なので、
 * 他の {@code Mod*} レジストリクラスと違い {@code DeferredRegister} での登録は不要。
 * ここではコード側(バイオーム書き換え処理など)から参照するための {@link ResourceKey} だけを定義する。
 *
 * <h2>「繁茂系」バイオーム = 環境 × 進行度 × 地形</h2>
 * <ul>
 *     <li>環境({@link OvergrowthVariant}): 密林 / 湿地 / 菌湿地帯。ワールドシードから決まる地域マップ
 *     ({@link com.yayuy.overgrownvegetation.corruption.OvergrowthRegionMap})で選ばれる。</li>
 *     <li>進行度: 過繁茂(地表の汚染割合が閾値以上)/ 繁茂(胞子濃度 100% だが閾値未満)。</li>
 *     <li>地形: 陸 / 川。海は環境に関係なく {@link #OVERGROWN_OCEAN} / {@link #OVERGROWTH_OCEAN} に統一。</li>
 * </ul>
 * どれも multi-noise 設定からは参照されない書き換え専用で、
 * {@link com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter} が既存チャンクへ後から書き込む。
 */
public final class ModBiomes {

    /** 陸の過繁茂バイオーム(密林)。{@code worldgen/biome/overgrown.json}。 */
    public static final ResourceKey<Biome> OVERGROWN = OvergrowthVariant.JUNGLE.overgrown();

    /** 海の過繁茂バイオーム(環境を問わず統一)。{@code worldgen/biome/overgrown_ocean.json}。 */
    public static final ResourceKey<Biome> OVERGROWN_OCEAN = key("overgrown_ocean");

    /** 陸の繁茂バイオーム(密林)。{@code worldgen/biome/overgrowth.json}。汚染が進むと {@link #OVERGROWN} へ昇格。 */
    public static final ResourceKey<Biome> OVERGROWTH = OvergrowthVariant.JUNGLE.overgrowth();

    /**
     * 焼けた荒地(順 2 の着手順 9、2026-10-08)。{@code worldgen/biome/burnt_wasteland.json}。火炎放射器で汚染の地面が半分を下回ったチャンクを
     * これに塗り、3 日で世界生成のときのバイオームへ戻す({@code purify/BurntLand})。繁茂系ではない(どの集合にも入れない)ので、
     * 繁茂系を見る湧き・胞子濃度・極相の拾い直しからは外れる。
     */
    public static final ResourceKey<Biome> BURNT_WASTELAND = key("burnt_wasteland");

    /** 海の繁茂バイオーム。{@code worldgen/biome/overgrowth_ocean.json}。汚染が進むと {@link #OVERGROWN_OCEAN} へ昇格。 */
    public static final ResourceKey<Biome> OVERGROWTH_OCEAN = key("overgrowth_ocean");

    /** 川の過繁茂バイオーム(密林)。{@code worldgen/biome/overgrown_river.json}。 */
    public static final ResourceKey<Biome> OVERGROWN_RIVER = OvergrowthVariant.JUNGLE.overgrownRiver();

    /** 川の繁茂バイオーム(密林)。{@code worldgen/biome/overgrowth_river.json}。 */
    public static final ResourceKey<Biome> OVERGROWTH_RIVER = OvergrowthVariant.JUNGLE.overgrowthRiver();

    /** 過繁茂した湿地。 */
    public static final ResourceKey<Biome> OVERGROWN_SWAMP = OvergrowthVariant.SWAMP.overgrown();
    /** 繁茂した湿地。 */
    public static final ResourceKey<Biome> OVERGROWTH_SWAMP = OvergrowthVariant.SWAMP.overgrowth();
    /** 過繁茂した湿地の川。 */
    public static final ResourceKey<Biome> OVERGROWN_SWAMP_RIVER = OvergrowthVariant.SWAMP.overgrownRiver();
    /** 繁茂した湿地の川。 */
    public static final ResourceKey<Biome> OVERGROWTH_SWAMP_RIVER = OvergrowthVariant.SWAMP.overgrowthRiver();

    /** 過繁茂した菌湿地帯。 */
    public static final ResourceKey<Biome> OVERGROWN_FUNGAL_SWAMP = OvergrowthVariant.FUNGAL_SWAMP.overgrown();
    /** 繁茂した菌湿地帯。 */
    public static final ResourceKey<Biome> OVERGROWTH_FUNGAL_SWAMP = OvergrowthVariant.FUNGAL_SWAMP.overgrowth();
    /** 過繁茂した菌湿地帯の川。 */
    public static final ResourceKey<Biome> OVERGROWN_FUNGAL_SWAMP_RIVER = OvergrowthVariant.FUNGAL_SWAMP.overgrownRiver();
    /** 繁茂した菌湿地帯の川。 */
    public static final ResourceKey<Biome> OVERGROWTH_FUNGAL_SWAMP_RIVER = OvergrowthVariant.FUNGAL_SWAMP.overgrowthRiver();

    /** 陸の極相バイオーム(密林)。{@code worldgen/biome/engulfed.json}。 */
    public static final ResourceKey<Biome> ENGULFED = OvergrowthVariant.JUNGLE.climax();
    /** 川の極相バイオーム(密林)。 */
    public static final ResourceKey<Biome> ENGULFED_RIVER = OvergrowthVariant.JUNGLE.climaxRiver();
    /** 海の極相バイオーム(環境を問わず統一)。 */
    public static final ResourceKey<Biome> ENGULFED_OCEAN = key("engulfed_ocean");
    /**
     * 過繫茂したエンド(2026-10-02)。森がエンドに着くと本島をまるごとこれにする({@code corruption/EndOvergrowth})。
     * 段階は 1 つだけ(繁茂・極相は無い)。湧く Mob は後で作る(今はバニラのエンドと同じエンダーマンだけ)。
     */
    public static final ResourceKey<Biome> OVERGROWN_END = key("overgrown_end");
    /** 極相の湿地。 */
    public static final ResourceKey<Biome> ENGULFED_SWAMP = OvergrowthVariant.SWAMP.climax();
    /** 極相の湿地の川。 */
    public static final ResourceKey<Biome> ENGULFED_SWAMP_RIVER = OvergrowthVariant.SWAMP.climaxRiver();
    /** 極相の菌湿地帯。 */
    public static final ResourceKey<Biome> ENGULFED_FUNGAL_SWAMP = OvergrowthVariant.FUNGAL_SWAMP.climax();
    /** 極相の菌湿地帯の川。 */
    public static final ResourceKey<Biome> ENGULFED_FUNGAL_SWAMP_RIVER = OvergrowthVariant.FUNGAL_SWAMP.climaxRiver();
    /**
     * 極相の<b>結晶地帯</b>(順 1、2026-10-03)。密林の地域の一部が極相になるとこれになる(陸だけ。川・海は普通の極相)。
     * 地脈に沿ってエネルギー結晶が突き出し、地下にチラコイトができる。密林の環境に属する
     * ({@link OvergrowthVariant#CRYSTAL_CLIMAX})。
     */
    public static final ResourceKey<Biome> ENGULFED_CRYSTAL = OvergrowthVariant.CRYSTAL_CLIMAX;

    /** 全繁茂系バイオーム(オーバーワールド 22 種 = 3 環境 × 3 段階 × (陸 + 川) + 海 3 段階 + 結晶地帯、ネザー 10 種、エンド 1 種)。 */
    public static final List<ResourceKey<Biome>> ALL = List.of(
            OVERGROWN, OVERGROWTH, ENGULFED,
            OVERGROWN_RIVER, OVERGROWTH_RIVER, ENGULFED_RIVER,
            OVERGROWN_OCEAN, OVERGROWTH_OCEAN, ENGULFED_OCEAN,
            OVERGROWN_SWAMP, OVERGROWTH_SWAMP, ENGULFED_SWAMP,
            OVERGROWN_SWAMP_RIVER, OVERGROWTH_SWAMP_RIVER, ENGULFED_SWAMP_RIVER,
            OVERGROWN_FUNGAL_SWAMP, OVERGROWTH_FUNGAL_SWAMP, ENGULFED_FUNGAL_SWAMP,
            OVERGROWN_FUNGAL_SWAMP_RIVER, OVERGROWTH_FUNGAL_SWAMP_RIVER, ENGULFED_FUNGAL_SWAMP_RIVER,
            // 結晶地帯(2026-10-03): 極相だけ。
            ENGULFED_CRYSTAL,
            // ネザー(2026-10-02): 5 種 × 繁茂・過繁茂。{@link NetherOvergrowthVariant}
            NetherOvergrowthVariant.WASTES.overgrown(), NetherOvergrowthVariant.WASTES.overgrowth(),
            NetherOvergrowthVariant.CRIMSON.overgrown(), NetherOvergrowthVariant.CRIMSON.overgrowth(),
            NetherOvergrowthVariant.WARPED.overgrown(), NetherOvergrowthVariant.WARPED.overgrowth(),
            NetherOvergrowthVariant.SOUL_VALLEY.overgrown(), NetherOvergrowthVariant.SOUL_VALLEY.overgrowth(),
            NetherOvergrowthVariant.BASALT.overgrown(), NetherOvergrowthVariant.BASALT.overgrowth(),
            // エンド(2026-10-02): 過繁茂だけ。
            OVERGROWN_END);

    /**
     * 「過繁茂<b>以上</b>」段階のキー —— 過繁茂 7 種 <b>と極相 7 種</b>(2026-09-22)。
     *
     * <p>極相をここに含めるのは意図的。{@link #isFullyOvergrown} は
     * 「もう繁茂段階へ戻す必要がない / 胞子の発生源として数える / 昇格の走査は要らない」の意味で
     * 使われているので、極相を外すと
     * {@link com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter#maybeConvert} が
     * <b>極相チャンクを過繁茂へ引き戻す</b>。
     *
     * <p>「極相だけ」を見たいところ(通常 Mob を湧かせない判定など)は {@link #isClimax} を使う。
     */
    private static final Set<ResourceKey<Biome>> FULLY_OVERGROWN = Set.of(
            OVERGROWN, OVERGROWN_RIVER, OVERGROWN_OCEAN,
            OVERGROWN_SWAMP, OVERGROWN_SWAMP_RIVER,
            OVERGROWN_FUNGAL_SWAMP, OVERGROWN_FUNGAL_SWAMP_RIVER,
            ENGULFED, ENGULFED_RIVER, ENGULFED_OCEAN,
            ENGULFED_SWAMP, ENGULFED_SWAMP_RIVER,
            ENGULFED_FUNGAL_SWAMP, ENGULFED_FUNGAL_SWAMP_RIVER,
            ENGULFED_CRYSTAL,
            NetherOvergrowthVariant.WASTES.overgrown(), NetherOvergrowthVariant.CRIMSON.overgrown(),
            NetherOvergrowthVariant.WARPED.overgrown(), NetherOvergrowthVariant.SOUL_VALLEY.overgrown(),
            NetherOvergrowthVariant.BASALT.overgrown(),
            OVERGROWN_END);

    /** 「極相」段階のキーだけ(陸 3 環境 + 川 3 環境 + 海 + 結晶地帯)。 */
    private static final Set<ResourceKey<Biome>> CLIMAX_STAGE = Set.of(
            ENGULFED, ENGULFED_RIVER, ENGULFED_OCEAN,
            ENGULFED_SWAMP, ENGULFED_SWAMP_RIVER,
            ENGULFED_FUNGAL_SWAMP, ENGULFED_FUNGAL_SWAMP_RIVER,
            ENGULFED_CRYSTAL);

    /** 「繁茂」段階のキー。 */
    private static final Set<ResourceKey<Biome>> OVERGROWTH_STAGE = Set.of(
            OVERGROWTH, OVERGROWTH_RIVER, OVERGROWTH_OCEAN,
            OVERGROWTH_SWAMP, OVERGROWTH_SWAMP_RIVER,
            OVERGROWTH_FUNGAL_SWAMP, OVERGROWTH_FUNGAL_SWAMP_RIVER,
            NetherOvergrowthVariant.WASTES.overgrowth(), NetherOvergrowthVariant.CRIMSON.overgrowth(),
            NetherOvergrowthVariant.WARPED.overgrowth(), NetherOvergrowthVariant.SOUL_VALLEY.overgrowth(),
            NetherOvergrowthVariant.BASALT.overgrowth());

    /** 川側のキー(3 環境 × 3 段階)。 */
    private static final Set<ResourceKey<Biome>> RIVER = Set.of(
            OVERGROWN_RIVER, OVERGROWTH_RIVER, ENGULFED_RIVER,
            OVERGROWN_SWAMP_RIVER, OVERGROWTH_SWAMP_RIVER, ENGULFED_SWAMP_RIVER,
            OVERGROWN_FUNGAL_SWAMP_RIVER, OVERGROWTH_FUNGAL_SWAMP_RIVER, ENGULFED_FUNGAL_SWAMP_RIVER);

    private static final Set<ResourceKey<Biome>> ALL_SET = Set.copyOf(ALL);

    private static ResourceKey<Biome> key(String path) {
        return ResourceKey.create(Registries.BIOME, OvergrownVegetation.id(path));
    }

    @Nullable
    private static ResourceKey<Biome> keyOf(Holder<Biome> biome) {
        return biome.unwrapKey().orElse(null);
    }

    /**
     * 「繁茂系」バイオーム(過繁茂・繁茂、陸・海・川、全環境のいずれか)か。
     *
     * <p>繁茂バイオームは「基本的な仕様はすべて過繁茂バイオームと同じ」、海・川・湿地も陸と同じなので、
     * 霧の演出・胞子濃度・AI・地表浸食などの「繁茂系バイオームの中か?」判定はこのメソッドを通す。
     * 例外は次の 3 つ:
     * <ol>
     *     <li>{@link com.yayuy.overgrownvegetation.spore.SporeConcentration} の<b>近隣チャンクの過繁茂率</b>算出
     *     ({@link #isFullyOvergrown} を使う。繁茂バイオームを胞子の発生源に数えると際限なく外へ広がるため)。</li>
     *     <li>{@link com.yayuy.overgrownvegetation.corruption.OvergrownBiomeConverter#maybeConvert} の
     *     「既に過繁茂なので走査不要」早期リターン({@link #isFullyOvergrown}。繁茂 → 過繁茂の昇格を止めないため)。</li>
     *     <li>「過繁茂した Mob」の自然湧き({@link com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner} は
     *     {@link #isFullyOvergrown} を使う。過繁茂バイオームにしか湧かせない —— 繁茂バイオームには湧かない)。</li>
     * </ol>
     */
    public static boolean isOvergrownLike(Holder<Biome> biome) {
        ResourceKey<Biome> key = keyOf(biome);
        return key != null && ALL_SET.contains(key);
    }

    /** 「過繁茂<b>以上</b>」(過繁茂 or 極相、陸 or 海 or 川、全環境)のバイオームか。繁茂段階は含まない。極相だけを見たいときは {@link #isClimax}。 */
    public static boolean isFullyOvergrown(Holder<Biome> biome) {
        ResourceKey<Biome> key = keyOf(biome);
        return key != null && FULLY_OVERGROWN.contains(key);
    }

    /** {@link #isOvergrownLike(Holder)} の {@link ResourceKey} 版。 */
    public static boolean isOvergrownKey(ResourceKey<Biome> key) {
        return ALL_SET.contains(key);
    }

    /** {@link #isFullyOvergrown(Holder)} の {@link ResourceKey} 版(極相も true)。 */
    public static boolean isFullyOvergrownKey(ResourceKey<Biome> key) {
        return FULLY_OVERGROWN.contains(key);
    }


    /**
     * 「極相」段階(陸 or 海 or 川、全環境)のバイオームか。過繁茂・繁茂段階は含まない。
     *
     * <p>{@link #isFullyOvergrown} は極相も {@code true} を返す(「過繁茂以上」の意味)ので、
     * <b>極相だけを別扱いしたいところは必ずこちらを使う</b>。いまのところ:
     * 通常 Mob を湧かせない判定、浸食度の増加、器官の生成。
     */
    public static boolean isClimax(Holder<Biome> biome) {
        ResourceKey<Biome> key = keyOf(biome);
        return key != null && CLIMAX_STAGE.contains(key);
    }

    /** {@link #isClimax(Holder)} の {@link ResourceKey} 版。 */
    public static boolean isClimaxKey(ResourceKey<Biome> key) {
        return CLIMAX_STAGE.contains(key);
    }
    /** 「繁茂」段階(陸 or 海 or 川)のバイオームか。過繁茂段階は含まない。 */
    public static boolean isOvergrowthStage(Holder<Biome> biome) {
        ResourceKey<Biome> key = keyOf(biome);
        return key != null && OVERGROWTH_STAGE.contains(key);
    }

    /** 「海」側の繁茂系バイオーム(過繁茂した海 or 繁茂した海)か。 */
    public static boolean isOvergrownOcean(Holder<Biome> biome) {
        return biome.is(OVERGROWN_OCEAN) || biome.is(OVERGROWTH_OCEAN) || biome.is(ENGULFED_OCEAN);
    }

    /** 「川」側の繁茂系バイオーム(全環境の 過繁茂した川 / 繁茂した川)か。 */
    public static boolean isOvergrownRiver(Holder<Biome> biome) {
        ResourceKey<Biome> key = keyOf(biome);
        return key != null && RIVER.contains(key);
    }

    /** 水中側(海 or 川)の繁茂系バイオームか。地表走査で水底を見るべきチャンクの判定に使う。 */
    public static boolean isOvergrownWatery(Holder<Biome> biome) {
        return isOvergrownOcean(biome) || isOvergrownRiver(biome);
    }

    /** 湿地系(湿地 / 菌湿地帯、陸・川、両段階)のバイオームか。 */
    public static boolean isSwampLike(Holder<Biome> biome) {
        OvergrowthVariant variant = OvergrowthVariant.fromBiome(biome);
        return variant != null && variant.isSwamp();
    }

    private ModBiomes() {
    }
}
