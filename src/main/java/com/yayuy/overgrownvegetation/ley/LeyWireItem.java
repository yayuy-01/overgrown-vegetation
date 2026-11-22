package com.yayuy.overgrownvegetation.ley;

import org.jetbrains.annotations.Nullable;
import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Locale;
import com.yayuy.overgrownvegetation.port.component.OvItemData;

/**
 * <b>地脈の線の巻き</b>(2026-11-13 サバイバルの背骨 段 2b、決定 13「2 か所をクリックして空中に線を張る」)。銅線を布で巻いた物【仮】。
 * 1 か所目のクリックで端を覚え(アイテムが光る)、2 か所目で線を張る。線 1 本で 1 つ使う。決まりは {@link LeyWires}。
 * 端になれるのは柱と FE の口を持つブロック(機械が右クリックで画面を開くなら、しゃがんでクリック)。しゃがんで空へ右クリックで取り消す。
 */
public class LeyWireItem extends Item {

    public LeyWireItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        Player player = ctx.getPlayer();
        ItemStack stack = ctx.getItemInHand();
        if (!LeyWires.isNode(level, pos)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        GlobalPos start = OvItemData.get(stack, ModLey.WIRE_START.get());
        if (start == null || !start.dimension().equals(level.dimension()) || !LeyWires.isNode(level, start.pos())) {
            OvItemData.set(stack, ModLey.WIRE_START.get(), GlobalPos.of(level.dimension(), pos.immutable()));
            message(player, Component.translatable("message.overgrown_vegetation.ley_wire.start"));
            level.playSound(null, pos, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.6F, 1.4F);
            return InteractionResult.CONSUME;
        }
        if (start.pos().equals(pos)) {
            OvItemData.remove(stack, ModLey.WIRE_START.get());
            message(player, Component.translatable("message.overgrown_vegetation.ley_wire.cancel"));
            return InteractionResult.CONSUME;
        }
        LeyWires.Fail fail = LeyWires.check(level, start.pos(), pos);
        if (fail != null) {
            message(player, Component.translatable("message.overgrown_vegetation.ley_wire." + fail.name().toLowerCase(Locale.ROOT),
                    LeyText.one(LeyWires.length(start.pos(), pos))).withStyle(ChatFormatting.RED));
            return InteractionResult.CONSUME;
        }
        LeyWires.connect(level, start.pos(), pos);
        double len = LeyWires.length(start.pos(), pos);
        OvItemData.remove(stack, ModLey.WIRE_START.get());
        if (player == null || !player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        message(player, Component.translatable("message.overgrown_vegetation.ley_wire.connected", LeyText.one(len)));
        level.playSound(null, pos, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.8F, 0.9F);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown() && OvItemData.has(stack, ModLey.WIRE_START.get())) {
            if (!level.isClientSide) {
                OvItemData.remove(stack, ModLey.WIRE_START.get());
                message(player, Component.translatable("message.overgrown_vegetation.ley_wire.cancel"));
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return OvItemData.has(stack, ModLey.WIRE_START.get());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable net.minecraft.world.level.Level context, List<Component> tooltip, TooltipFlag flag) {
        GlobalPos start = OvItemData.get(stack, ModLey.WIRE_START.get());
        if (start != null) {
            BlockPos p = start.pos();
            tooltip.add(Component.translatable("item.overgrown_vegetation.ley_wire.from", p.getX(), p.getY(), p.getZ())
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    private static void message(Player player, Component text) {
        if (player != null) {
            player.displayClientMessage(text, true);
        }
    }
}
