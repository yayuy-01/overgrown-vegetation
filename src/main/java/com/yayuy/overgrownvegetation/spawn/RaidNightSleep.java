package com.yayuy.overgrownvegetation.spawn;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;

/**
 * 襲撃が来る夜は寝られない(見直し ②7、11-16 ユーザー決定「襲撃が来る夜は、近くに敵がいるのと同じく寝られない」)。
 * 寝て飛ばした時間も森は稼ぐ(時計は 1 つ)ので、寝ることは襲撃からの逃げ道にならない。
 *
 * <p>認識した拠点の真ん中から {@link #RANGE} ブロックの中で寝ようとしたとき、次の朝までに次のどれかが来るなら、バニラの
 * 「近くに敵がいる」と同じ理由で断る(言葉もバニラのまま。新しい文は足さない):
 * <ul>
 *   <li>その拠点の最初の襲撃(まだ送っていない拠点の、送り込みの時刻が次の朝より前)。</li>
 *   <li>貯めた襲撃(継ぎ足しが続いている・貯める間が次の朝より前に明ける)。</li>
 *   <li>大きな襲撃(本番の最中・前触れが次の朝より前に明ける)。</li>
 * </ul>
 * ふつうの送り込みの夜は寝られる(ユーザーが挙げたのは上の 3 つ)。範囲 128 ブロックは【仮】(送り込みが個体を探す半径と同じ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class RaidNightSleep {

    /** 拠点の真ん中からこの半径の中で寝ようとしたら見る(ブロック)【仮】。 */
    public static final double RANGE = 128.0D;

    private RaidNightSleep() {
    }

    @SubscribeEvent
    public static void onCanSleep(CanPlayerSleepEvent event) {
        if (event.getProblem() != null || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
        if (raidTonight(player.getServer(), player.getX(), player.getZ())) {
            event.setProblem(Player.BedSleepingProblem.NOT_SAFE);
        }
    }

    /** (x, z) のそばの認識した拠点に、次の朝までに襲撃が来るか。 */
    public static boolean raidTonight(MinecraftServer server, double x, double z) {
        double day = BaseMemory.today(server);
        double morning = day + (1.0D - BaseMemory.timeOfDay(server)); // 次の朝 6 時(1 日の中の時刻 0)
        ForestDirector d = ForestDirector.get(server.overworld());
        boolean near = false;
        for (BaseMemory.Base b : BaseMemory.get(server).bases()) {
            if (b.stage() != BaseMemory.Stage.RECOGNIZED) continue;
            double dx = b.centerX() - x, dz = b.centerZ() - z;
            if (dx * dx + dz * dz > RANGE * RANGE) continue;
            near = true;
            if (BaseMemory.firstRaidPending(b) && b.nextWaveDay() <= morning) return true;
        }
        if (!near) return false;
        ForestDirector.Mood mood = d.mood();
        if (mood == ForestDirector.Mood.LARGE) return true;
        return (mood == ForestDirector.Mood.HOARD || mood == ForestDirector.Mood.LARGE_OMEN) && d.moodUntil() <= morning;
    }
}
