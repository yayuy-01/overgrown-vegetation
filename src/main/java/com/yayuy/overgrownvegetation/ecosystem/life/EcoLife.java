package com.yayuy.overgrownvegetation.ecosystem.life;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;

/**
 * 環境生物の行動の段(2026-11-18)に乗る生き物。土台クラス(地這い {@code CrawlerCritter}・トカゲ {@code SimpleLizard})が実装する。
 * サーバーの心({@link EcoMind})と、同期する動作({@link EcoAct})と、Goal({@link EcoGoals})の口。
 */
public interface EcoLife {

    /** サーバー側の心。 */
    EcoMind ecoMind();

    /** 今の動作(同期した値。クライアントでも読める)。 */
    EcoAct ecoAct();

    /** 動作を決める(サーバー)。 */
    void ecoSetAct(EcoAct act);

    /** 種の技・移動の形の最中か(登る・滑空・跳ぶなど)。真の間は行動の Goal が始まらない。 */
    default boolean ecoBusy() {
        return false;
    }

    /** クライアント: 見た目だけの小さな生き物を口元で食べた(SmallCritters の eatHook から)。止まっていれば eat を少し流す。 */
    default void ecoClientAte() {
    }

    // ---------------------------------------------------------------- 共通の口

    /** 種の id(名前空間なし)。 */
    static String id(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    static EcoBehavior behavior(Entity e) {
        return EcoBehavior.of(id(e));
    }

    /**
     * 種に有るアニメ(11-18 に animation.json の中を見た)。無いアニメは流さない(null)。
     * 驚く startle はモデル制作に頼んである。届くまで、威嚇の有る種は threat で代える。
     */
    Map<String, Set<String>> CLIPS = Map.ofEntries(
            Map.entry("arthropleura", Set.of("eat", "rest", "threat", "startle", "enter_den", "exit_den")),
            Map.entry("giant_snail", Set.of("eat", "rest", "enter_den", "exit_den")),
            Map.entry("protophasma", Set.of("eat", "rest", "hide", "startle")),
            Map.entry("pulmonoscorpius", Set.of("eat", "rest", "threat", "startle", "enter_den", "exit_den")),
            Map.entry("stenodictya_nymph", Set.of("eat", "rest", "cocoon")),
            Map.entry("phalangiotarbus", Set.of("eat", "rest", "startle", "enter_den", "exit_den")),
            Map.entry("petrolacosaurus", Set.of("eat", "rest", "bask", "enter_den", "exit_den")),
            Map.entry("hylonomus", Set.of("eat", "rest", "hide", "enter_den", "exit_den")),
            Map.entry("ianthasaurus", Set.of("eat", "rest", "bask", "enter_den", "exit_den")),
            Map.entry("coelurosauravus", Set.of("eat", "rest")));

    /**
     * 洞の出入りのアニメの長さ(tick)と、その間に進む速さ(ブロック/tick・体格 1)。アニメ担当 11-18: 根の骨は動かさず、前進はコード。
     * walk と同じ足の運びなので、この速さで進めると足が滑らない。
     */
    Map<String, double[]> DEN_GAIT = Map.of(
            "hylonomus", new double[]{20, 0.06}, "petrolacosaurus", new double[]{20, 0.05}, "ianthasaurus", new double[]{20, 0.10},
            "pulmonoscorpius", new double[]{16, 0.06}, "arthropleura", new double[]{18, 0.05}, "phalangiotarbus", new double[]{16, 0.07},
            "giant_snail", new double[]{24, 0.011});

    /** 動作のアニメの名前(種に無ければ null)。 */
    @Nullable
    static String clip(Entity e, EcoAct act) {
        if (act == EcoAct.NONE) return null;
        Set<String> have = CLIPS.get(id(e));
        if (have == null) return null;
        if (have.contains(act.clip)) return act.clip;
        if (act == EcoAct.STARTLE && have.contains("threat")) return "threat";
        return null;
    }

    /** 土台の型(Goal が要る)。 */
    default PathfinderMob ecoSelf() {
        return (PathfinderMob) this;
    }
}
