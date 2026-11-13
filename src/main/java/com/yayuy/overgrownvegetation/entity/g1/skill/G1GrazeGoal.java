package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.entity.DevelopedParasitized;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 羊の 1 段目の (b)「草を食べて綿毛が育つ」(2026-11-13、能力の第 2 回。仕様 Vault「G1 羊(1 段目)」3 節【決定 11-09・数は仮】)。
 * 元の羊の「草を食べて毛が戻る」を植物が使う: 草を食べ、跡を汚染した土にして、綿毛を 1 段育て直す。刈れるのは {@link DevelopedParasitized#shear}。
 *
 * <ul>
 *   <li>食べる条件: 綿毛が {@link DevelopedParasitized#FLUFF_MAX} 段未満・{@value #CALM_RADIUS} ブロック以内に獲物がいない(G1 は 35 ブロック以内の生き物を
 *   必ず標的にするので、「標的がいない」ではなく「近くにいない」で見る)・地面の上・足元に草(草ブロック / 過繁茂の草ブロック、または升の中の草)。
 *   草の無い所(砂利・石)では綿毛が戻らない(仕様の対処)。</li>
 *   <li>段: graze 40 tick(頭を下げて止まる = 近づいて刈る好機)。{@value #EAT_AT} tick 目に食べる。</li>
 *   <li>食べた跡: 草ブロック → <b>汚染した土</b>(過繁茂の土 {@code overgrown_dirt}。過繁茂の土はランダム tick で過繁茂の草に戻るので、汚染地帯では食べ続けられる)。
 *   汚染が切られている・抑える装置の中では、バニラと同じただの土。</li>
 *   <li>冷却 {@value #COOLDOWN} tick【仮】。</li>
 * </ul>
 */
public class G1GrazeGoal extends G1TechniqueGoal {

    static final double CALM_RADIUS = 8.0D;
    static final int EAT_AT = 30;
    static final int COOLDOWN = 200;

    public G1GrazeGoal(DevelopedParasitized mob) {
        super(mob, new G1Move[]{G1Move.GRAZE}, new int[]{40});
    }

    private static boolean isGrassBlock(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(ModBlocks.OVERGROWN_GRASS_BLOCK.get());
    }

    private static boolean isGrassTuft(BlockState s) {
        return s.is(Blocks.SHORT_GRASS) || s.is(Blocks.FERN);
    }

    /** 足元に食べられる草があるか。 */
    static boolean edible(ServerLevel level, BlockPos feet) {
        return isGrassTuft(level.getBlockState(feet)) || isGrassBlock(level.getBlockState(feet.below()));
    }

    @Override
    protected boolean ready() {
        if (!(mob.level() instanceof ServerLevel level)) return false;
        return mob.fluff() < DevelopedParasitized.FLUFF_MAX && mob.onGround() && mob.calm(CALM_RADIUS) && edible(level, mob.blockPosition());
    }

    /** 向きは追わない(標的に向かう技ではない)。 */
    @Override
    protected int aimUntil() {
        return 0;
    }

    @Override
    protected int cooldown() {
        return COOLDOWN;
    }

    @Override
    protected void onTick(int stageIndex, int tickInStage) {
        if (tickInStage != EAT_AT || !(mob.level() instanceof ServerLevel level)) return;
        BlockPos feet = mob.blockPosition();
        BlockPos below = feet.below();
        boolean ate = false;
        if (isGrassTuft(level.getBlockState(feet))) {
            level.destroyBlock(feet, false);
            ate = true;
        }
        BlockState ground = level.getBlockState(below);
        if (isGrassBlock(ground)) {
            level.levelEvent(2001, below, Block.getId(ground));
            ate = true;
        }
        if (ate && (isGrassBlock(ground) || ground.is(Blocks.DIRT))) {
            // 跡を汚染した土に(汚染が切られている・抑える装置の中では、バニラと同じただの土)
            boolean corrupt = !CorruptionSwitch.isOff() && !SporeDeviceData.inhibits(level, below, SporeDeviceData.Inhibit.CORRUPTION);
            BlockState after = corrupt ? ModBlocks.OVERGROWN_DIRT.get().defaultBlockState() : Blocks.DIRT.defaultBlockState();
            if (!ground.is(after.getBlock())) level.setBlock(below, after, Block.UPDATE_ALL);
        }
        if (ate) {
            mob.setFluff(mob.fluff() + 1);
            level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.GRASS_BREAK, SoundSource.HOSTILE, 0.8F, 0.8F);
        }
    }
}
