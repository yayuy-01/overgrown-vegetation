package com.yayuy.overgrownvegetation.spawn;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.director.HoardRaids;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.eventbus.api.EventPriority;

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
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class RaidNightSleep {

    /** 拠点の真ん中からこの半径の中で寝ようとしたら見る(ブロック)【仮】。 */
    public static final double RANGE = 128.0D;

    private RaidNightSleep() {
    }

    /**
     * 1.20.1: 本流の {@code CanPlayerSleepEvent} は「バニラの判定の後」に来るが、Forge 1.20.1 の {@link PlayerSleepInBedEvent} は「バニラの判定の前」に来る。
     * そこで、バニラが通す時だけ断るよう、バニラの判定({@code ServerPlayer#startSleepInBed} の前半)をここで同じ順になぞる。
     * どれかで落ちるならバニラに任せる(バニラの理由の言葉が出る)。全部通る時は、バニラと同じく復活の場所を先に決めてから断る。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCanSleep(PlayerSleepInBedEvent event) {
        if (event.getResultStatus() != null || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
        if (!raidTonight(player.getServer(), player.getX(), player.getZ())) return;
        if (!vanillaWouldAllow(player, event.getPos())) return;
        event.setResult(Player.BedSleepingProblem.NOT_SAFE);
    }

    /** バニラが「近くに敵がいる」の手前まで通すか。通るなら、バニラと同じく復活の場所を決める(バニラも断る前に決める)。 */
    private static boolean vanillaWouldAllow(ServerPlayer player, BlockPos at) {
        BlockState bed = player.level().getBlockState(at);
        if (!bed.hasProperty(HorizontalDirectionalBlock.FACING)) return true; // 1.21 と同じ: 向きの無い寝床は判定なしで通る
        Direction dir = bed.getValue(HorizontalDirectionalBlock.FACING);
        if (player.isSleeping() || !player.isAlive()) return false;
        if (!player.level().dimensionType().natural()) return false;
        if (!reachable(player, at) && !reachable(player, at.relative(dir.getOpposite()))) return false;
        BlockPos above = at.above();
        if (!freeAt(player, above) || !freeAt(player, above.relative(dir.getOpposite()))) return false;
        player.setRespawnPosition(player.level().dimension(), at, player.getYRot(), false, true);
        return ForgeEventFactory.fireSleepingTimeCheck(player, java.util.Optional.of(at));
    }

    private static boolean reachable(ServerPlayer player, BlockPos pos) {
        Vec3 c = Vec3.atBottomCenterOf(pos);
        return Math.abs(player.getX() - c.x()) <= 3.0D && Math.abs(player.getY() - c.y()) <= 2.0D && Math.abs(player.getZ() - c.z()) <= 3.0D;
    }

    private static boolean freeAt(ServerPlayer player, BlockPos pos) {
        return !player.level().getBlockState(pos).isSuffocating(player.level(), pos);
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
            if (HoardRaids.raid(b.id()) != null) return true;
        }
        if (!near) return false;
        ForestDirector.Mood mood = d.mood();
        if (mood == ForestDirector.Mood.LARGE) return true;
        return (mood == ForestDirector.Mood.HOARD || mood == ForestDirector.Mood.LARGE_OMEN) && d.moodUntil() <= morning;
    }
}
