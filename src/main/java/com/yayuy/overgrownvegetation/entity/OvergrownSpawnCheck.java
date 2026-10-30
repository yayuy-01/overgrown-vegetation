package com.yayuy.overgrownvegetation.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * 自然湧きの<b>最後の関門</b>を種ごとに持たせる口(2026-10-01、G5 の低キューブ 4 種の足場で追加)。
 *
 * <p>湧かせ役({@link com.yayuy.overgrownvegetation.phase.OvergrownBiomeSpawner} の地上・空・水中・洞窟の 4 経路、
 * {@link SporeSpreadSession} の「終わりぎわに 1 体増やす」、{@code ecosystem/EcosystemDefense} の防衛の兵)は、
 * 位置を決めて実体を作り({@code EntityType#create})、<b>世界に足す直前</b>に {@link #accept} を呼ぶ。
 * false なら湧かせずに捨てる(実体は {@code discard} される)。
 *
 * <p>湧かせ役は種ごとの事情を知らない(バイオーム・地上/地下・体の入る隙間 {@code SpawnFit} までは見る)。
 * 「深い水にだけ」「大きな空洞だけ」「近くの岩の中へ潜ってから」のような種ごとの決まりは、
 * 実体のクラスにこれを実装して書く。<b>自分の位置を動かしてよい</b>(地中へ潜る・空中へ持ち上げる)——
 * 呼ばれた時点ではまだ世界に居ないので、{@code moveTo} で置き直せばそのまま足される。
 *
 * <p>スポーンエッグ・{@code /summon}・GameTest の {@code spawn} など<b>意図して出す湧き</b>は通らない(呼ばない)。
 */
public interface OvergrownSpawnCheck {

    /**
     * 湧かせ役が位置を決めて置いた後・世界に足す前に呼ぶ。サーバー側だけ。
     *
     * @param level 湧く世界
     * @return false ならここには湧かない(湧かせ役が捨てる)。位置を動かしてから true を返してもよい
     */
    boolean acceptOvergrownSpawn(ServerLevel level);

    /**
     * 湧かせ役が呼ぶ補助。{@code entity} が {@link OvergrownSpawnCheck} でなければ常に true。
     */
    static boolean accept(Entity entity, ServerLevel level) {
        return !(entity instanceof OvergrownSpawnCheck check) || check.acceptOvergrownSpawn(level);
    }
}
