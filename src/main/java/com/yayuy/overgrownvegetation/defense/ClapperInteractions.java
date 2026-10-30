package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;

/**
 * 鳴子の紐への右クリック(2026-10-15)。紐には当たり判定が無いので、視線と紐の曲線の近さで「紐を見ている」を判定する
 * ({@link ClapperRope#raycast})。
 * <ul>
 *     <li>「鳴子の板」を持って紐を右クリック → その場所に板を 1 枚吊る(ユーザー指定)。</li>
 *     <li>ハサミを持って紐を右クリック → 紐を切る(紐と板を落とす)。</li>
 *     <li>燻製器のレシピの入力にある物を持って紐を右クリック → 1 つ吊るす(2026-10-22、点いた焚き火の真上の煙で燻す)。素手で吊るした物を右クリック → 外す
 *     (何も無い所への素手の右クリックはクライアントにしか来ないので、サーバーへ {@code network/RopeTakePayload} を送る)。</li>
 * </ul>
 * 紐の向こうにブロックがあるとブロックへの右クリックが先に働くので、ブロックへの右クリック({@link PlayerInteractEvent.RightClickBlock})でも
 * 紐の方が手前なら横取りする。何も無い所への右クリック({@link PlayerInteractEvent.RightClickItem})も見る。両サイドで同じ判定をする
 * (クライアントにも紐の台帳がある)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ClapperInteractions {

    /** 紐を見ている位置。 */
    public record RopeHit(BlockPos rope, float t, double distance) {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        boolean bare = stack.isEmpty() && event.getHand() == InteractionHand.MAIN_HAND;
        if (!bare && !isTool(player.level(), stack)) {
            return;
        }
        RopeHit hit = pick(player.level(), player);
        if (hit == null || bare && !hangingAt(player.level(), hit)) {
            return;
        }
        double blockDistance = player.getEyePosition().distanceTo(event.getHitVec().getLocation());
        if (hit.distance() > blockDistance + 0.05D) {
            return; // ブロックの方が手前
        }
        act(player, event.getHand(), stack, hit);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        if (!isTool(player.level(), stack)) {
            return;
        }
        RopeHit hit = pick(player.level(), player);
        if (hit == null) {
            return;
        }
        act(player, event.getHand(), stack, hit);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /**
     * 素手で何も無い所を右クリック(クライアントだけに来るイベント)。吊るした物を見ていればサーバーへ「外す」を送る
     * ({@code network/RopeTakePayload}。サーバーで見直してから外す)。
     */
    @SubscribeEvent
    public static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        Player player = event.getEntity();
        if (event.getHand() != InteractionHand.MAIN_HAND || !player.getMainHandItem().isEmpty()) {
            return;
        }
        RopeHit hit = pick(player.level(), player);
        if (hit != null && hangingAt(player.level(), hit)) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.yayuy.overgrownvegetation.network.RopeTakePayload());
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** サーバー: 素手のプレイヤーが見ている紐から吊るした物を外す({@code network/RopeTakePayload} から)。 */
    public static void takeLookedAt(ServerPlayer player) {
        if (!player.getMainHandItem().isEmpty()) {
            return;
        }
        RopeHit hit = pick(player.level(), player);
        if (hit != null) {
            act(player, InteractionHand.MAIN_HAND, ItemStack.EMPTY, hit);
        }
    }

    private static boolean hangingAt(Level level, RopeHit hit) {
        return level.getBlockEntity(hit.rope()) instanceof ClapperRopeBlockEntity rope && rope.hasHangingNear(hit.t());
    }

    private static boolean isTool(Level level, ItemStack stack) {
        return stack.is(ModItems.CLAPPER_BOARD.get()) || stack.getItem() instanceof ShearsItem
                || ClapperRope.smokingRecipe(level, stack).isPresent();
    }

    /** 板を吊る / 紐を切る / 燻す物を吊るす・外す(サーバーだけで実際に変える)。 */
    private static void act(Player player, InteractionHand hand, ItemStack stack, RopeHit hit) {
        if (!(player.level() instanceof ServerLevel level) || !(level.getBlockEntity(hit.rope()) instanceof ClapperRopeBlockEntity rope)) {
            return;
        }
        Vec3 at = ClapperRope.curvePoint(rope.start(), rope.end(), rope.getSag(), hit.t());
        if (stack.isEmpty()) {
            ItemStack taken = rope.takeHanging(hit.t());
            if (!taken.isEmpty()) {
                if (!player.getInventory().add(taken)) {
                    player.drop(taken, false);
                }
                level.playSound(null, at.x, at.y, at.z, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6F, 1.0F);
            }
            return;
        }
        if (!stack.is(ModItems.CLAPPER_BOARD.get()) && !(stack.getItem() instanceof ShearsItem)) {
            // 燻製器のレシピの入力にある物 → 1 つ吊るす(2026-10-22、点いた焚き火の真上の煙で燻す)
            if (rope.addHanging(hit.t(), stack)) {
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }
                level.playSound(null, at.x, at.y, at.z, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 0.8F, 1.1F);
            } else {
                player.displayClientMessage(Component.translatable("message.overgrown_vegetation.clapper_board.no_room")
                        .withStyle(ChatFormatting.RED), true);
            }
            return;
        }
        if (stack.getItem() instanceof ShearsItem) {
            ClapperRopeBlock.cut(level, hit.rope(), !player.getAbilities().instabuild);
            stack.hurtAndBreak(1, player, hand == InteractionHand.MAIN_HAND
                    ? net.minecraft.world.entity.EquipmentSlot.MAINHAND : net.minecraft.world.entity.EquipmentSlot.OFFHAND);
            return;
        }
        if (rope.addBoard(hit.t())) {
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
            Vec3 p = ClapperRope.curvePoint(rope.start(), rope.end(), rope.getSag(), hit.t());
            level.playSound(null, p.x, p.y, p.z, SoundEvents.BAMBOO_PLACE, SoundSource.BLOCKS, 1.0F, 1.2F);
        } else {
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.clapper_board.no_room")
                    .withStyle(ChatFormatting.RED), true);
        }
    }

    /** プレイヤーが見ている紐(手の届く範囲で、いちばん手前)。無ければ null。 */
    @Nullable
    public static RopeHit pick(Level level, Player player) {
        Vec3 from = player.getEyePosition();
        double reach = player.blockInteractionRange();
        Vec3 to = from.add(player.getViewVector(1.0F).scale(reach));
        RopeHit best = null;
        for (BlockPos pos : ClapperNetwork.ropesNear(level, player.blockPosition(), ClapperRope.MAX_LENGTH + reach + 2.0D)) {
            if (!(level.getBlockEntity(pos) instanceof ClapperRopeBlockEntity rope) || !rope.hasRope()) {
                continue;
            }
            ClapperRope.Hit hit = ClapperRope.raycast(rope.start(), rope.end(), rope.getSag(), from, to);
            if (hit != null && (best == null || hit.distanceAlongRay() < best.distance())) {
                best = new RopeHit(pos, (float) hit.t(), hit.distanceAlongRay());
            }
        }
        return best;
    }

    private ClapperInteractions() {
    }
}
