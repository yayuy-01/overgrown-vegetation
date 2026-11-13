package com.yayuy.overgrownvegetation.survival.decon;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 洗い場の上に立った人の装備を洗う(サバイバルの背骨 段 3b で中身を入れる)。
 */
final class GearShower {

    private GearShower() {
    }

    /** 上に立っている人の装備を少し洗う。洗ったら true(FE を使う)。 */
    static boolean washStanding(ServerLevel level, BlockPos pos, WasherBlockEntity be) {
        return false;
    }
}
