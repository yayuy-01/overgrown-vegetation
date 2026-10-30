package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 「鳴子の紐」(2026-10-15、サバイバル用アイテム ㊵)。蔦({@code item/LianaItem})と同じ<b>2 回クリック</b>で、柵・塀・柱の類の
 * 2 本の間に紐を張る(ユーザー指定)。1 回目に右クリックした柱を始点として覚え、2 回目の柱との間に張って 1 つ使う。
 * 同じ柱をもう一度・スニークで右クリックすると始点を取り消す。長さは {@link ClapperRope#MIN_LENGTH}〜{@link ClapperRope#MAX_LENGTH}。
 */
public class ClapperRopeItem extends Item {

    public ClapperRopeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        BlockPos clicked = context.getClickedPos();
        if (player == null || !ClapperRope.isPost(level.getBlockState(clicked))) {
            return InteractionResult.PASS;
        }
        if (!(level instanceof ServerLevel server)) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = context.getItemInHand();
        GlobalPos anchor = stack.get(ModDataComponents.CLAPPER_ROPE_ANCHOR.get());
        if (anchor != null && !anchor.dimension().equals(server.dimension())) {
            anchor = null;
        }
        if (anchor == null) {
            stack.set(ModDataComponents.CLAPPER_ROPE_ANCHOR.get(), GlobalPos.of(server.dimension(), clicked.immutable()));
            say(player, "message.overgrown_vegetation.clapper_rope.start", ChatFormatting.WHITE);
            server.playSound(null, clicked, SoundEvents.LEASH_KNOT_PLACE, SoundSource.BLOCKS, 0.8F, 1.2F);
            return InteractionResult.CONSUME;
        }
        BlockPos source = anchor.pos();
        if (source.equals(clicked) || player.isShiftKeyDown()) {
            stack.remove(ModDataComponents.CLAPPER_ROPE_ANCHOR.get());
            say(player, "message.overgrown_vegetation.clapper_rope.cancelled", ChatFormatting.WHITE);
            return InteractionResult.CONSUME;
        }
        if (!ClapperRope.isPost(server.getBlockState(source))) {
            stack.remove(ModDataComponents.CLAPPER_ROPE_ANCHOR.get());
            say(player, "message.overgrown_vegetation.clapper_rope.source_gone", ChatFormatting.RED);
            return InteractionResult.CONSUME;
        }
        double distance = Math.sqrt(source.distSqr(clicked));
        if (distance > ClapperRope.MAX_LENGTH) {
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.clapper_rope.too_far", ClapperRope.MAX_LENGTH)
                    .withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }
        if (distance < ClapperRope.MIN_LENGTH) {
            say(player, "message.overgrown_vegetation.clapper_rope.too_close", ChatFormatting.RED);
            return InteractionResult.CONSUME;
        }
        if (place(server, source, clicked) == null) {
            say(player, "message.overgrown_vegetation.clapper_rope.blocked", ChatFormatting.RED);
            return InteractionResult.CONSUME;
        }
        stack.remove(ModDataComponents.CLAPPER_ROPE_ANCHOR.get());
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        say(player, "message.overgrown_vegetation.clapper_rope.placed", ChatFormatting.WHITE);
        server.playSound(null, clicked, SoundEvents.LEASH_KNOT_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
        return InteractionResult.CONSUME;
    }

    /**
     * {@code source} と {@code target} の柱の間に紐を張る。目印のブロックは始点の柱の隣の空いたマス(上 → 終点に向いた横 → ほかの横 → 下)。
     *
     * @return 目印のブロックの位置(置く隙間が無ければ null)
     */
    @Nullable
    public static BlockPos place(ServerLevel level, BlockPos source, BlockPos target) {
        List<Direction> order = new ArrayList<>(List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST));
        order.sort(Comparator.comparingDouble(d -> source.relative(d).distSqr(target)));
        order.add(0, Direction.UP);
        order.add(Direction.DOWN);
        for (Direction d : order) {
            BlockPos marker = source.relative(d);
            if (!level.getBlockState(marker).isAir()) {
                continue;
            }
            level.setBlock(marker, ModBlocks.CLAPPER_ROPE.get().defaultBlockState(), 3);
            if (level.getBlockEntity(marker) instanceof ClapperRopeBlockEntity rope) {
                rope.setRope(source, target);
                return marker;
            }
        }
        return null;
    }

    private static void say(Player player, String key, ChatFormatting color) {
        player.displayClientMessage(Component.translatable(key).withStyle(color), true);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        GlobalPos anchor = stack.get(ModDataComponents.CLAPPER_ROPE_ANCHOR.get());
        if (anchor == null) {
            tooltip.add(Component.translatable("tooltip.overgrown_vegetation.clapper_rope.hint").withStyle(ChatFormatting.GRAY));
        } else {
            BlockPos p = anchor.pos();
            tooltip.add(Component.translatable("tooltip.overgrown_vegetation.clapper_rope.anchor", p.getX(), p.getY(), p.getZ())
                    .withStyle(ChatFormatting.GREEN));
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return stack.has(ModDataComponents.CLAPPER_ROPE_ANCHOR.get()) || super.isFoil(stack);
    }
}
