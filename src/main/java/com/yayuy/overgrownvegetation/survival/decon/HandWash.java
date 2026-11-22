package com.yayuy.overgrownvegetation.survival.decon;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DeconSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.util.List;

/**
 * <b>手洗い</b>(2026-11-14 サバイバルの背骨 段 3a、決定 30): 水の入った大釜を、洗える物を持って右クリックすると 1 つ洗う。
 * 地脈の設備が無くてもできる序盤の手段で、水が減り、遅い({@code decon.handCooldownTicks}・{@code decon.handWork*})。
 *
 * <p>バニラの大釜の働き(染めた革の色落とし・旗の模様落とし)より先に受ける。汚れた染め革の防具は、まず汚れが落ち、もう一度で色が落ちる。
 * 水の減りは「洗いの重み」をプレイヤーごとに数え({@link #WORK_KEY}。大釜には持たせない)、{@code handWorkPerLevel} たまるごとに 1 段減らす。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class HandWash {

    /** プレイヤーの持続データに持つ洗いの重みの端数。 */
    public static final String WORK_KEY = "overgrown_vegetation:hand_wash_work";

    private HandWash() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        BlockState state = level.getBlockState(event.getPos());
        if (!state.is(Blocks.WATER_CAULDRON)) return;
        Player player = event.getEntity();
        ItemStack stack = player.getItemInHand(event.getHand());
        if (stack.isEmpty()) return;
        Decon.Kind kind = Decon.kind(level, stack);
        boolean wornOut = kind == Decon.Kind.NONE && Decon.filterWornOut(stack);
        if (kind == Decon.Kind.NONE && !wornOut) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        if (!(level instanceof ServerLevel server)) return;
        if (wornOut) {
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.decon.filter_worn_out"), true);
            return;
        }
        if (player.getCooldowns().isOnCooldown(stack.getItem())) return;
        wash(server, event.getPos(), player, event.getHand());
    }

    /**
     * 大釜で手の物を 1 つ洗う(GameTest からも呼ぶ)。洗えたら true。待ち時間は見ない(呼ぶ側)。
     * 洗い上がった物は、手が空になればその手へ、そうでなければ持ち物へ(入らなければ足元へ)。
     */
    public static boolean wash(ServerLevel level, BlockPos pos, Player player, InteractionHand hand) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.WATER_CAULDRON)) return false;
        ItemStack stack = player.getItemInHand(hand);
        Decon.Kind kind = Decon.kind(level, stack);
        if (kind == Decon.Kind.NONE) return false;
        DeconSpec spec = Decon.spec();

        // 水を減らす(重みを数え、1 段分たまるごとに 1 段)
        CompoundTag data = player.getPersistentData();
        int work = data.getInt(WORK_KEY) + (kind == Decon.Kind.MATERIAL ? spec.handWorkMaterial() : spec.handWorkOther());
        int levels = work / spec.handWorkPerLevel();
        data.putInt(WORK_KEY, work % spec.handWorkPerLevel());
        for (int i = 0; i < levels && state.is(Blocks.WATER_CAULDRON); i++) {
            LayeredCauldronBlock.lowerFillLevel(state, level, pos);
            state = level.getBlockState(pos);
        }

        ItemStack one = stack.split(1);
        List<ItemStack> out = Decon.washOne(level, one, level.random, GearDirt.handStep());
        for (ItemStack o : out) {
            if (o.isEmpty()) continue;
            if (player.getItemInHand(hand).isEmpty()) {
                player.setItemInHand(hand, o);
            } else if (!player.getInventory().add(o)) {
                player.drop(o, false);
            }
        }
        if (spec.handCooldownTicks() > 0) {
            player.getCooldowns().addCooldown(one.getItem(), spec.handCooldownTicks());
        }
        level.playSound(null, pos, SoundEvents.GENERIC_SPLASH, SoundSource.BLOCKS, 0.35F, 1.3F + level.random.nextFloat() * 0.3F);
        level.sendParticles(ParticleTypes.SPLASH, pos.getX() + 0.5D, pos.getY() + 0.95D, pos.getZ() + 0.5D, 8, 0.2D, 0.05D, 0.2D, 0.0D);
        return true;
    }
}
