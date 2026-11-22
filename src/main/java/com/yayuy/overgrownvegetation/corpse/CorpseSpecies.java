package com.yayuy.overgrownvegetation.corpse;

import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;

/**
 * 死体ブロック → 種の素材の名前(崩れ落ちの姿勢 {@code corpse_poses/<名前>.json}・死体の geo・絵)。2026-10-09 の対応表を、
 * 2026-11-08 に G1 段階 0 の種の表 {@link G1Species} から引くように書き直した(ネザーの媒介種・ゴーレム・ウォーデンは parked のまま)。
 */
public final class CorpseSpecies {

    private CorpseSpecies() {
    }

    /** 崩れ落ちに対応した死体ブロックなら種の名前(例 {@code overgrown_zombie})、でなければ null。 */
    @Nullable
    public static String speciesOf(Block corpseBlock) {
        G1Species s = G1Species.ofCorpse(corpseBlock);
        return s == null ? null : s.assetName();
    }
}
