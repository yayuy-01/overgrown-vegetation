package com.yayuy.overgrownvegetation.wall;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 蠢く壁が自然に起きる条件(決定 その 1・2「F3 から、汚染地帯の下の洞窟で、超低確率」)。
 * {@link WallTuning#naturalCheckTicks} ごとに、オーバーワールドのサバイバル・アドベンチャーのプレイヤーを 1 人ずつ見て:
 * <ul>
 *     <li>フェーズが {@link WallTuning#naturalMinPhase} 以上・ピースフルでない・動いている壁が無い・前に起きてから {@link WallTuning#naturalCooldownTicks} 経った</li>
 *     <li>その人が洞窟の中(足元が空の見えない空き)にいて、真上の地表が汚染地帯(繁茂・過繁茂のバイオーム)</li>
 * </ul>
 * なら {@link WallTuning#naturalChance} で、その人から洞窟をたどって約 48 ブロック先から前触れ付きで起こす(マルチでは、その洞窟にいる全員が対象になる)。
 * 前に起きた時刻はメモリの上だけ(再起動で忘れる)【仮】。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class CreepingWallNatural {

    private static final Map<ResourceKey<Level>, Long> LAST = new HashMap<>();

    private CreepingWallNatural() {
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LAST.clear();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        long now = level.getGameTime();
        if (now % WallTuning.naturalCheckTicks != 0 || !allowed(level, now)) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (level.random.nextDouble() >= WallTuning.naturalChance || !eligible(level, player)) {
                continue;
            }
            BlockPos origin = CreepingWallCommand.farCell(level, player.blockPosition(), 48);
            if (origin == null || origin.distSqr(player.blockPosition()) < 16 * 16) {
                continue; // 狭すぎる洞窟(逃げる数秒が無い)では起こさない【仮】
            }
            CreepingWall w = CreepingWalls.startWithOmen(level, origin);
            LAST.put(level.dimension(), now);
            OvergrownVegetation.LOGGER.info("[creeping_wall] 自然に起きた #{}: {} の近く {}(フェーズ {})", w.id(), player.getGameProfile().getName(),
                    origin.toShortString(), PhaseData.get(level).getPhase());
            return;
        }
    }

    /** 世界の側の条件(フェーズ・難易度・動いている壁・休み)。 */
    public static boolean allowed(ServerLevel level, long now) {
        if (level.getDifficulty() == Difficulty.PEACEFUL || PhaseData.get(level).getPhase() < WallTuning.naturalMinPhase
                || !com.yayuy.overgrownvegetation.difficulty.ExperimentalFeatures.enabled(level.getServer(),
                        com.yayuy.overgrownvegetation.difficulty.ExperimentalFeatures.Feature.CREEPING_WALL)) { // 実験的要素(11-23 夜)。/ov wall は動く
            return false;
        }
        Long last = LAST.get(level.dimension());
        if (last != null && now - last < WallTuning.naturalCooldownTicks) {
            return false;
        }
        for (CreepingWall w : CreepingWalls.walls(level)) {
            if (!w.isStopped()) {
                return false;
            }
        }
        return true;
    }

    /** その人の側の条件: サバイバル・アドベンチャーで、洞窟の中にいて、真上の地表が汚染地帯。 */
    static boolean eligible(ServerLevel level, ServerPlayer player) {
        if (player.isCreative() || player.isSpectator()) {
            return false;
        }
        BlockPos feet = player.blockPosition();
        if (!CreepingWall.loadedNow(level, feet) || WallCells.classify(level, feet) != WallCells.Kind.PASS) {
            return false;
        }
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, feet.getX(), feet.getZ());
        return surface > feet.getY() + 3 && ModBiomes.isOvergrownLike(level.getBiome(new BlockPos(feet.getX(), surface, feet.getZ())));
    }
}
