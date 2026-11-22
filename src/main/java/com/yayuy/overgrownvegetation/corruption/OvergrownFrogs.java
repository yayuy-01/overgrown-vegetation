package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.frog.Tadpole;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

/**
 * 過繁茂のバイオームではカエルが湧かない(2026-11-17 軽量化。ユーザー決定「過繁茂では湧かない」)。
 *
 * <p>測った値: F7 の前線(本物の写し・台帳 50 万チャンク)にカエルが 41 匹いて、バニラの跳ぶ先探し({@code LongJumpToRandomPos})で
 * サーバーの 1 tick の約 13%(1 匹 約 0.14 ms)を使っていた。
 *
 * <p>自然に出てくるカエル・オタマジャクシ(自然湧き・スポナー・構造物)は、過繁茂の中なら出さない。
 * プレイヤーが出した分(スポーンエッグ・命令・バケツ・ディスペンサー)は出す。
 * 既にいるカエルは消さない(11-17 ユーザー: 「カエルが多い状況は局所的な原因すぎるし、過繁茂になるにつれて数は減ると思う」。
 * 見ていない所で消す案は入れて外した)。繁茂の段階(過繁茂の手前)では今までどおり。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class OvergrownFrogs {

    private OvergrownFrogs() {
    }

    private static boolean isFrogKind(Entity e) {
        return e instanceof Frog || e instanceof Tadpole;
    }

    static boolean inOvergrown(ServerLevel level, double x, double y, double z) {
        return ModBiomes.isFullyOvergrown(level.getBiome(net.minecraft.core.BlockPos.containing(x, y, z)));
    }

    /** 自然に出てくる分だけを止める。プレイヤーが出した分は止めない。 */
    @SubscribeEvent
    public static void onFinalizeSpawn(net.minecraftforge.event.entity.living.MobSpawnEvent.FinalizeSpawn event) {
        if (!isFrogKind(event.getEntity())) return;
        ServerLevel level = event.getLevel().getLevel(); // 1.20.1: ServerLevelAccessor#getLevel は ServerLevel を返す
        switch (event.getSpawnType()) {
            case SPAWN_EGG, COMMAND, BUCKET, DISPENSER, TRIGGERED -> {
                return;
            }
            default -> {
            }
        }
        if (inOvergrown(level, event.getX(), event.getY(), event.getZ())) {
            event.setSpawnCancelled(true);
        }
    }
}
