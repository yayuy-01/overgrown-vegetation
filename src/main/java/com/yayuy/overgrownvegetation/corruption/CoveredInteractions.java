package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.OvergrownContainers;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;

/**
 * <b>記録で覆ったブロックの右クリック</b>(2026-10-30。作り直し側 overgrown_vegetation/mod の {@code CoveredInteractions} を手本に、
 * 前の {@code OvergrownLayerInteraction} の「右クリックを全部止める」と、退避した covered_ ブロックの {@code CoveredInteractables}
 * (2026-10-05 ユーザー指定「過繁茂した扉の時折開かない仕様」)を 1 つにした)。
 *
 * <ul>
 *     <li><b>手で動かすもの</b>(扉・トラップドア・フェンスゲート・ボタン・レバー): 開ける・押す・入れる向きのときだけ
 *     {@code cover.json} の {@code stuckChance} で動かない(きしむ音・蔦の破片・アクションバーに一言。過繁茂したドアと同じ
 *     {@link OvergrownContainers#stuckFeedback})。閉じる・戻すは常にできる。鉄の扉・鉄のトラップドアは手で開かないので対象外。
 *     扉は上下どちらが覆われていても</li>
 *     <li><b>それ以外の覆われたブロック</b>: ブロックの右クリックを起こさない(金床・作業台の画面を開かない)。手に持った物は使える
 *     (覆われたブロックに向けてブロックを置ける)</li>
 *     <li>ハサミは {@link CoverPeel}(剥がす)が先</li>
 *     <li>動くかどうかはサーバーだけが決める。動かないことがありうるときは、クライアントは先読みで状態を変えず振るだけ</li>
 * </ul>
 * Mob(村人・ゾンビ・襲撃者)が覆われた扉を開けるときも同じ割合で引っかかる({@code mixin/DoorSetOpenCoveredMixin} が
 * {@link #mobOpenBlocked} を引く)。<b>レッドストーンで動く分は止まらない</b>(覆われた扉がレバーで開く。ブロックを置き換えていないので)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class CoveredInteractions {

    private CoveredInteractions() {
    }

    /** 優先度を上げてあるのは、ほかの MOD が先に「使った」ことにしてしまう前に止めるため。 */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getItemStack().is(Tags.Items.TOOLS_SHEAR)) {
            return;
        }
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState s = level.getBlockState(pos);
        if (!covered(level, pos, s)) {
            return;
        }
        Movable m = movable(s);
        if (m == Movable.RETURNING) {
            return;
        }
        if (m == Movable.NOT) {
            event.setUseBlock(TriState.FALSE);
            return;
        }
        if (level.isClientSide) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            return;
        }
        if (stuck(level, pos, s, event.getEntity(), level.random.nextDouble())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.CONSUME);
        }
    }

    /**
     * 動かそうとした(開ける・押す・入れる)ときの関門(GameTest も)。動かないなら音・粒・一言を出して true。
     *
     * @param roll 0〜1 の乱数
     */
    public static boolean stuck(Level level, BlockPos pos, BlockState s, @Nullable Player player, double roll) {
        if (roll >= DesignData.cover().stuckChance()) {
            return false;
        }
        boolean opens = s.getBlock() instanceof DoorBlock || s.getBlock() instanceof TrapDoorBlock || s.getBlock() instanceof FenceGateBlock;
        OvergrownContainers.stuckFeedback(level, pos, player,
                opens ? OvergrownContainers.STUCK_MESSAGE : OvergrownContainers.STUCK_MOVE_MESSAGE);
        return true;
    }

    /**
     * {@code DoorBlock#setOpen}(Mob が扉を開け閉めする口)の関門(Mixin と GameTest)。覆われた手で開く扉を開ける向きで、
     * 引っかかったら音・粒(プレイヤーなら一言も)を出して true。閉じる向き・クライアント・覆われていない扉・鉄の扉は false。
     *
     * @param roll 0〜1 の乱数
     */
    public static boolean mobOpenBlocked(@Nullable Entity entity, Level level, BlockState s, BlockPos pos, boolean open, double roll) {
        if (!open || level.isClientSide || !(s.getBlock() instanceof DoorBlock door) || s.getValue(DoorBlock.OPEN)
                || !door.type().canOpenByHand() || !covered(level, pos, s)) {
            return false;
        }
        return stuck(level, pos, s, entity instanceof Player player ? player : null, roll);
    }

    /** 手で動かすものの向き。 */
    public enum Movable {
        /** 手で動かすものではない(覆われていれば右クリックを起こさない)。 */
        NOT,
        /** 開ける・押す・入れる向き(時々動かない)。 */
        ACTIVATING,
        /** 閉じる・戻す向き(常にできる)。 */
        RETURNING
    }

    public static Movable movable(BlockState s) {
        if (s.getBlock() instanceof DoorBlock door) {
            return !door.type().canOpenByHand() ? Movable.NOT : open(s);
        }
        if (s.getBlock() instanceof TrapDoorBlock) {
            // 手で開くかは TrapDoorBlock#getType(protected)。バニラで手で開かないのは鉄だけ
            return s.is(Blocks.IRON_TRAPDOOR) ? Movable.NOT : open(s);
        }
        if (s.getBlock() instanceof FenceGateBlock) {
            return open(s);
        }
        if (s.getBlock() instanceof ButtonBlock || s.getBlock() instanceof LeverBlock) {
            return s.getValue(BlockStateProperties.POWERED) ? Movable.RETURNING : Movable.ACTIVATING;
        }
        return Movable.NOT;
    }

    private static Movable open(BlockState s) {
        return s.getValue(BlockStateProperties.OPEN) ? Movable.RETURNING : Movable.ACTIVATING;
    }

    /** 覆われているか(扉は上下どちらかが覆われていれば)。 */
    public static boolean covered(Level level, BlockPos pos, BlockState s) {
        if (OvergrownLayerData.isCovered(level, pos)) {
            return true;
        }
        if (s.getBlock() instanceof DoorBlock && s.hasProperty(DoorBlock.HALF)) {
            BlockPos other = s.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            return OvergrownLayerData.isCovered(level, other);
        }
        return false;
    }
}
