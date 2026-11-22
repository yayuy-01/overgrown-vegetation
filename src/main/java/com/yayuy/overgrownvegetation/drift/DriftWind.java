package com.yayuy.overgrownvegetation.drift;

import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** 海の上の風(地上の卓越風。凪の日は落ちる)。胞子の場がまだ無ければ無風。 */
final class DriftWind {

    private DriftWind() {
    }

    /** 風の強さ × 向きを out[0..1] へ({@code forced} があればそれ)。 */
    static void now(ServerLevel level, @Nullable Vec3 forced, double[] out) {
        if (forced != null) {
            out[0] = forced.x;
            out[1] = forced.z;
            return;
        }
        PrevailingWind.Sample s = SporeFieldServer.windNow(level);
        if (s == null) {
            out[0] = out[1] = 0.0D;
            return;
        }
        double g = s.ground();
        out[0] = s.dirX * g;
        out[1] = s.dirZ * g;
    }
}
