package com.yayuy.overgrownvegetation.base;

import com.yayuy.overgrownvegetation.block.RepellentLampBlock;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.defense.RepellentLamps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 妨害灯の中身(2026-10-01)。データは持たない。ロード/アンロードで忌避灯の台帳と {@link JammingLamps} へ
 * 出入りし(点いている灯りだけ)、{@code jammingLamp.intervalTicks} ごとに半径の中の Mob へ弱体化を付け直す。
 */
public class JammingLampBlockEntity extends BlockEntity {

    public JammingLampBlockEntity(BlockPos pos, BlockState state) {
        super(BaseBlocks.JAMMING_LAMP_ENTITY.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide && RepellentLampBlock.isActive(getBlockState())) {
            RepellentLamps.register(this.level, this.worldPosition);
            JammingLamps.register(this.level, this.worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        unregister();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        unregister();
    }

    private void unregister() {
        if (this.level != null && !this.level.isClientSide) {
            RepellentLamps.unregister(this.level, this.worldPosition);
            JammingLamps.unregister(this.level, this.worldPosition);
        }
    }

    /** 置いて(読み込んで)からの tick。間隔 1 回ぶん経つまでは弱体化を掛けない(GameTest の並列実行で隣の場の Mob に掛けないため)。 */
    private int age;

    /** 灯りごとに位相をずらして(座標から)、間隔ごとに弱体化を付け直す。覆われている間は何もしない。 */
    public static void serverTick(Level level, BlockPos pos, BlockState state, JammingLampBlockEntity lamp) {
        int interval = DesignData.defense().jammingIntervalTicks();
        if (lamp.age < interval) {
            lamp.age++;
            return;
        }
        if (Math.floorMod(level.getGameTime() + pos.hashCode(), interval) != 0 || !RepellentLampBlock.isActive(state)) {
            return;
        }
        JammingLamps.applyDebuffs((ServerLevel) level, pos);
    }
}
