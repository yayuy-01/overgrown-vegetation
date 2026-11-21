package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

import java.util.Map;
import java.util.function.Predicate;

/**
 * 植物側の「死ぬと破裂する」「殴った Mob を必ず感染させる」の対象(2026-11-15 ユーザー決定。正本は Vault
 * 「構想と進捗/植物側の死の破裂と攻撃の感染(11-15)」)。
 *
 * <ul>
 *   <li>対象 = G1(段階 0 も)・G2〜G8 のタグ、または汎用の感染体(変わった印 = {@link #TRANSFORMED})。環境生物は入れない
 *   (生態系で生きているだけ)。</li>
 *   <li>破裂だけの外し = タグ {@code overgrown_vegetation:death_burst_exempt}(11-15 ユーザー決定: 根付き・苗床・根の守り手は「枯れて崩れる」のまま、
 *   萌芽体は外す、ペディキュラは段 2 で自前の破れ方)、
 *   G1 の進化体(すでに自前の death_burst で破裂する)、エンダードラゴン(死の演出が 200 tick あり、戦いの終わりがそこに付いている)。</li>
 * </ul>
 * サーバー(破裂)とクライアント(膨らみ)の両方がここを読む —— 片方だけで膨らむ・破裂するずれを出さない。
 */
public final class PlantSideRules {

    /** 自前の死に方を持つので破裂させない種(11-15 の一覧。ユーザーの答えで json を直す)。 */
    public static final TagKey<EntityType<?>> DEATH_BURST_EXEMPT =
            TagKey.create(Registries.ENTITY_TYPE, OvergrownVegetation.id("death_burst_exempt"));

    /**
     * 汎用の感染体(変わった印)か。土台の側(汎用感染システム)が自分の印の問い合わせを差し込む口。差し込むまでは誰も変わっていない。
     * サーバー側の判定。クライアントは {@code client/infect/GenericInfectionClient} を見る({@link #burstsWith})。
     */
    public static volatile Predicate<LivingEntity> TRANSFORMED = e -> false;

    private PlantSideRules() {
    }

    public static boolean isTransformed(LivingEntity e) {
        return TRANSFORMED.test(e);
    }

    /**
     * G1・G2〜G8 のタグのどれかか(変わった印は見ない)。グループの一覧は {@link com.yayuy.overgrownvegetation.phase.MobGroup} の 1 か所
     * (2026-11-16 コードの見直し ③6: 前はここと ParasitizedAttackInfectionHandler に別の一覧があった)。環境生物は生態系で生きているだけなので入れない。
     */
    public static boolean inBurstGroups(EntityType<?> type) {
        for (com.yayuy.overgrownvegetation.phase.MobGroup g : com.yayuy.overgrownvegetation.phase.MobGroup.values()) {
            if (g == com.yayuy.overgrownvegetation.phase.MobGroup.CRITTER_PREY || g == com.yayuy.overgrownvegetation.phase.MobGroup.CRITTER_HUNTER) continue;
            if (g.contains(type)) return true;
        }
        return false;
    }

    /**
     * 「感染させる側か」(2026-11-16 ③6 で 2 つの意味の片方に整理): 殴った・撃った・胞子を浴びせた相手を必ず感染させ、死ぬと破裂する側。
     * = G1・G2〜G8 のタグ または 汎用の感染体。
     */
    public static boolean isInfector(LivingEntity attacker) {
        return inBurstGroups(attacker.getType()) || isTransformed(attacker);
    }

    /**
     * 「植物側の陣営か」(2026-11-16 ③6 で 2 つの意味のもう片方に整理。暴露量を測らない・互いに狙わない・標的に選ばない側)。
     * = タグ {@code plant_side}(G1〜G8・環境生物・萌芽体・ハイヴノード)・どれかのグループ({@link com.yayuy.overgrownvegetation.phase.MobGroup})・
     * {@code ParasitizedMob} の作り・G1 / 萌芽体 / ハイヴノードのクラス(タグから抜かれても漏れない保険)・汎用の感染体。
     * 前は SporeExposureTracker.isPlantSide・ParasitizedAttackInfectionHandler.isOvergrownMob・PlantFoes で少しずつ違っていた。今はどれもここを呼ぶ。
     */
    public static boolean isPlantSide(LivingEntity e) {
        EntityType<?> t = e.getType();
        return t.is(ModTags.PLANT_SIDE)
                || e instanceof com.yayuy.overgrownvegetation.phase.ParasitizedMob
                || e instanceof com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster
                || e instanceof com.yayuy.overgrownvegetation.entity.BlightFernEntity
                || e instanceof com.yayuy.overgrownvegetation.entity.HiveNodeEntity
                || com.yayuy.overgrownvegetation.phase.MobGroup.of(t) != null
                || isTransformed(e);
    }

    /** 死ぬと破裂する側か(サーバー)。 */
    public static boolean bursts(LivingEntity e) {
        return burstsWith(e, isTransformed(e));
    }

    /** 死ぬと破裂する側か。{@code transformed} = 変わった印(クライアントは自分の同期の表から渡す)。 */
    public static boolean burstsWith(LivingEntity e, boolean transformed) {
        if (!transformed && !inBurstGroups(e.getType())) return false;
        if (e.getType().is(DEATH_BURST_EXEMPT)) return false;
        if (e instanceof com.yayuy.overgrownvegetation.entity.DevelopedParasitized) return false;
        if (ownsLongDeath(e)) return false; // 自前の死に際を持つほかの MOD の Mob は、消える瞬間に破裂させる({@link #burstsAtRemoval})
        return !(e instanceof EnderDragon);
    }

    /**
     * 死に際を最後まで流し、消える瞬間に破裂する側か(2026-11-16 コードの見直し ③14: 15〜18 tick で消すと、ほかの MOD の Mob の長い死に際が切れた)。
     * = 破裂の対象で、自前の死に際({@link #ownsLongDeath})を持つ。膨らみの描画は掛けない。エンダードラゴンは別の道(PlantSideBurst#onEntityTickPre)。
     */
    public static boolean burstsAtRemoval(LivingEntity e, boolean transformed) {
        if (!transformed && !inBurstGroups(e.getType())) return false;
        if (e.getType().is(DEATH_BURST_EXEMPT) || e instanceof EnderDragon) return false;
        return ownsLongDeath(e);
    }

    /**
     * 自前の死に際を持つほかの MOD の Mob か: この MOD の外の種で、クラスが {@code tickDeath} を上書きしている(バニラの 20 tick で消すのと違う)。
     * この MOD の種は自分で長さを持つ({@link #deathAnimTicks})。クラスごとに 1 回だけ調べて覚える。
     */
    public static boolean ownsLongDeath(LivingEntity e) {
        if (OvergrownVegetation.MODID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getNamespace())) return false;
        return OVERRIDES_TICK_DEATH.get(e.getClass());
    }

    private static final ClassValue<Boolean> OVERRIDES_TICK_DEATH = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != LivingEntity.class; c = c.getSuperclass()) {
                try {
                    c.getDeclaredMethod("tickDeath");
                    return true;
                } catch (NoSuchMethodException ignored) {
                    // 上へ
                } catch (SecurityException | LinkageError ex) {
                    return false;
                }
            }
            return false;
        }
    };

    /**
     * 破裂する tick(deathTime)。15〜18 を個体ごとにずらす(大の襲撃で一斉に死んでも同じ tick に重ならない)。
     * バニラは 20 tick 目に白い煙を出して消すので、それより前。クライアントは同じ式で膨らみの進みを出す(id はサーバーと同じ)。【仮】
     */
    public static int burstTick(LivingEntity e) {
        return deathAnimTicks(e) + BURST_TICK_MIN + Math.floorMod(e.getId() * 31, BURST_TICK_SPREAD);
    }

    /**
     * 死に際のアニメを持つ種の、そのアニメの長さ(tick)。膨らみはこの後に始まる(11-15 ユーザー決定「アニメの後に破裂」)。無い種は 0。
     * 値は各 Mob の倒れる長さと同じにする: ファルキムネス {@code DEATH_TICKS}(移り 4 + 1.5 秒)・フィロセンス {@code DEATH_TICKS}(2 秒)・
     * アクムネス death(1.5 秒 + 移り 2)。
     */
    public static int deathAnimTicks(LivingEntity e) {
        Integer t = DEATH_ANIM_TICKS.get(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
        return t == null ? 0 : t;
    }

    private static final Map<ResourceLocation, Integer> DEATH_ANIM_TICKS = Map.of(
            OvergrownVegetation.id("falcimnes"), 34,
            OvergrownVegetation.id("phyllosens"), 40,
            OvergrownVegetation.id("acumnes"), 32);

    /** 【仮】膨らみの長さの最短(tick)と、ずらしの幅。 */
    public static final int BURST_TICK_MIN = 15, BURST_TICK_SPREAD = 4;
}
