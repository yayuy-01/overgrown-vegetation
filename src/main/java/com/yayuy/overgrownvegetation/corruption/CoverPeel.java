package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.CoverTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * <b>覆いを剥がす</b>(2026-10-30。作り直し側 overgrown_vegetation/mod の {@code CoverPeel} を手本に。退避した covered_ ブロックの
 * 「ハサミで剥がすと元のバニラのブロック」(2026-10-05 夜、ユーザー指定)の続き)。
 *
 * <ul>
 *     <li>ハサミ(タグ {@code #c:tools/shear})で覆われたブロックを右クリック: 記録を消し(扉は上下とも)、ハサミの耐久を
 *     {@code cover.json} の {@code peelDamage} 減らす。扉・チェストを開けるなどのブロックの右クリックはその回だけ起きない。
 *     クライアントも記録を持っているので、腕を振るのは覆われているときだけ。作り直し側の「進み具合を減らす」は無し(今の汚染は進み具合を持たない)</li>
 *     <li>覆われたブロックを壊した(連動して消えた扉の上下・ベッドの片方なども)・その位置に置いた(2 マス以上を置いたら全部)・ピストンで
 *     動いた・爆発で壊れた: 記録だけ消す。消さないと、空いた位置に後で置いた同じブロックが覆われて見える
 *     ({@link OvergrownLayerData#isCovered} は「覆われる資格」しか見ないので、同じ種類を置き直すと前の記録が生きる)</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class CoverPeel {

    /** 壊した隣で、連動して消えたかを tick の終わりに見る覆いの位置(次元ごと)。 */
    private static final Map<ResourceKey<Level>, LongArrayList> LINKED = new HashMap<>();

    private CoverPeel() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        Level level = event.getLevel();
        if (!stack.is(Tags.Items.TOOLS_SHEAR) || !CoveredInteractions.covered(level, event.getPos(), level.getBlockState(event.getPos()))) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (level instanceof ServerLevel server) {
            peel(server, event.getPos(), stack, event.getEntity(), LivingEntity.getSlotForHand(event.getHand()));
        }
    }

    /**
     * 剥がす本体(GameTest からも呼ぶ)。
     *
     * @param user ハサミを持っている者(null なら耐久だけ減らす)
     * @return 記録があって剥がした
     */
    public static boolean peel(ServerLevel level, BlockPos pos, ItemStack shears, @Nullable LivingEntity user, EquipmentSlot slot) {
        BlockState state = level.getBlockState(pos);
        boolean peeled = OvergrownLayerData.uncover(level, pos);
        if (state.getBlock() instanceof DoorBlock && state.hasProperty(DoorBlock.HALF)) {
            BlockPos other = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            peeled |= OvergrownLayerData.uncover(level, other);
        }
        if (!peeled) {
            return false;
        }
        CoverTable cd = DesignData.cover();
        int damage = cd.peelDamage();
        if (damage > 0) {
            if (user != null) {
                shears.hurtAndBreak(damage, user, slot);
            } else {
                shears.hurtAndBreak(damage, level, null, item -> {
                });
            }
        }
        level.playSound(null, pos, SoundEvents.GROWING_PLANT_CROP, SoundSource.BLOCKS, 1, 1);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MOSS_BLOCK.defaultBlockState()),
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, cd.peelParticles(), cd.peelSpread(), cd.peelSpread(), cd.peelSpread(),
                cd.peelSpeed());
        level.gameEvent(user, GameEvent.SHEAR, pos);
        return true;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!event.isCanceled() && event.getLevel() instanceof ServerLevel level) {
            OvergrownLayerData.uncover(level, event.getPos());
            // 連動して消えるもう片方(扉の上下・ベッド・ピストンの頭)は BreakEvent が来ないので、隣の記録をこの tick の終わりに見直す
            for (Direction dir : Direction.values()) {
                BlockPos n = event.getPos().relative(dir);
                if (OvergrownLayerData.isCovered(level, n)) {
                    LINKED.computeIfAbsent(level.dimension(), k -> new LongArrayList()).add(n.asLong());
                }
            }
        }
    }

    /** 壊した隣で、記録があるのに空気になった位置の記録を消す(次元の tick の終わり)。 */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        LongArrayList list = LINKED.get(level.dimension());
        if (list == null || list.isEmpty()) {
            return;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < list.size(); i++) {
            p.set(list.getLong(i));
            LevelChunk chunk = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
            if (chunk != null && chunk.getBlockState(p).isAir()) {
                OvergrownLayerData.uncover(level, p.immutable());
            }
        }
        list.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LINKED.clear();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
            // 扉・ベッドなど 2 マス以上を置いたときは、置いた全部の位置
            for (BlockSnapshot s : multi.getReplacedBlockSnapshots()) {
                OvergrownLayerData.uncover(level, s.getPos());
            }
        } else {
            OvergrownLayerData.uncover(level, event.getPos());
        }
    }

    /** ピストンで動く・壊れるブロックの記録を消す(動いた先へは移さない。消さないと元の位置のピストンの頭などが覆われて見える)。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPiston(PistonEvent.Pre event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        PistonStructureResolver r = event.getStructureHelper();
        if (r != null && r.resolve()) {
            r.getToPush().forEach(p -> OvergrownLayerData.uncover(level, p));
            r.getToDestroy().forEach(p -> OvergrownLayerData.uncover(level, p));
        }
    }

    /** 爆発で壊れるブロックの記録を消す(壊さない爆発 = mobGriefing false のクリーパーなどは何もしない。一覧には入っている)。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level && event.getExplosion().interactsWithBlocks()) {
            event.getAffectedBlocks().forEach(p -> OvergrownLayerData.uncover(level, p));
        }
    }
}
