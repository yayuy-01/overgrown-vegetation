package com.yayuy.overgrownvegetation.item;

import org.jetbrains.annotations.Nullable;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.worldgen.TreeVineConnector;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;
import com.yayuy.overgrownvegetation.port.component.OvItemData;

/**
 * 「蔓」({@link com.yayuy.overgrownvegetation.registry.ModBlocks#OVERGROWN_LIANA})のアイテム。
 *
 * <p>この蔦は1マスのブロックではなく「始点と終点の間に張られた1本の曲線」なので、普通の
 * {@link BlockItem} のように1マス置いても何も見えない(アンカーは
 * {@link com.yayuy.overgrownvegetation.block.CorruptedLianaBlock} の Javadoc のとおり
 * {@code RenderShape#INVISIBLE})。そこで置き方を<b>2回クリック</b>にしてある。
 *
 * <ol>
 *     <li><b>1回目</b>: 右クリックしたブロックを<b>始点</b>として覚える
 *         ({@link ModDataComponents#LIANA_ANCHOR}。アイテムはまだ減らない)。</li>
 *     <li><b>2回目</b>: 別のブロックを右クリックすると、そこを<b>終点</b>として
 *         {@link TreeVineConnector#placeByHand} が世界生成とまったく同じ蔦を1本張り、
 *         アイテムを1つ消費する。</li>
 * </ol>
 *
 * <p>始点と同じブロックをもう一度右クリックすると始点を取り消す(置き直し用)。始点を覚えた
 * ままスニーク右クリックしても同じく取り消せる。始点を覚えたまま別の次元へ行った場合は、
 * 次に右クリックした場所が新しい始点になる(古い座標のまま張ろうとしない)。
 *
 * <p>長さの上限は設定 {@code vinePlaceMaxLength}(既定 32)。遠すぎる終点を選んでも始点は
 * 残るので、選び直せばよい。
 *
 * <p>判定はすべてサーバ側で行い、クライアントへは {@code displayClientMessage} で返す
 * (蔦の実体は {@link com.yayuy.overgrownvegetation.block.CorruptedLianaBlockEntity} で、
 * サーバが置いたものが同期されてはじめて描かれるため)。
 */
public class LianaItem extends BlockItem {

    /** 始点と終点が近すぎると曲線がほぼ点になってしまうので、最低このマス数は離す。 */
    private static final int MIN_LENGTH = 2;

    public LianaItem(net.minecraft.world.level.block.Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        // 判定も配置もサーバ側だけ。クライアントは「成功した」とだけ思っておけばよい
        // (腕を振る演出が出る。実際に蔦が見えるのはサーバから同期されてから)。
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }

        ItemStack stack = context.getItemInHand();
        BlockPos clicked = context.getClickedPos();
        GlobalPos anchor = OvItemData.get(stack, ModDataComponents.LIANA_ANCHOR.get());

        // 覚えている始点が別の次元のものなら、無かったことにしてここを新しい始点にする。
        if (anchor != null && !anchor.dimension().equals(serverLevel.dimension())) {
            anchor = null;
        }

        if (anchor == null) {
            setAnchor(stack, serverLevel, clicked);
            say(player, "message.overgrown_vegetation.liana.start");
            playClick(serverLevel, clicked);
            return InteractionResult.CONSUME;
        }

        BlockPos source = anchor.pos();
        // 同じブロックをもう一度 / スニークしながら = 始点の取り消し。
        if (source.equals(clicked) || player.isShiftKeyDown()) {
            OvItemData.remove(stack, ModDataComponents.LIANA_ANCHOR.get());
            say(player, "message.overgrown_vegetation.liana.cancelled");
            return InteractionResult.CONSUME;
        }

        double distance = Math.sqrt(source.distSqr(clicked));
        int maxLength = OvergrownVegetationConfig.COMMON.vinePlaceMaxLength.get();
        if (distance > maxLength) {
            // 始点は残す——選び直せばそのまま続けられる。
            player.displayClientMessage(Component.translatable(
                    "message.overgrown_vegetation.liana.too_far", maxLength)
                    .withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }
        if (distance < MIN_LENGTH) {
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.liana.too_close")
                    .withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }

        if (!TreeVineConnector.placeByHand(serverLevel, source, clicked, serverLevel.random)) {
            // 始点のまわりが埋まっていてアンカーを置く隙間が無かった。始点は残す。
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.liana.blocked")
                    .withStyle(ChatFormatting.RED), true);
            return InteractionResult.CONSUME;
        }

        OvItemData.remove(stack, ModDataComponents.LIANA_ANCHOR.get());
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        say(player, "message.overgrown_vegetation.liana.placed");
        playPlace(serverLevel, clicked);
        return InteractionResult.CONSUME;
    }

    /** 始点を覚える。コンポーネントが載った時点で、このスタックは他の「蔓」とは混ざらなくなる。 */
    private static void setAnchor(ItemStack stack, ServerLevel level, BlockPos pos) {
        OvItemData.set(stack, ModDataComponents.LIANA_ANCHOR.get(), GlobalPos.of(level.dimension(), pos));
    }

    private static void say(Player player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }

    private static void playClick(ServerLevel level, BlockPos pos) {
        level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.7F, 1.6F);
    }

    private static void playPlace(ServerLevel level, BlockPos pos) {
        level.playSound(null, pos, SoundEvents.VINE_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable net.minecraft.world.level.Level context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        GlobalPos anchor = OvItemData.get(stack, ModDataComponents.LIANA_ANCHOR.get());
        if (anchor == null) {
            tooltip.add(Component.translatable("tooltip.overgrown_vegetation.liana.hint")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        BlockPos pos = anchor.pos();
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.liana.anchor",
                        pos.getX(), pos.getY(), pos.getZ())
                .withStyle(ChatFormatting.GREEN));
    }

    /** 始点を持っている1個を手元で見分けられるようにエンチャント光沢を出す。 */
    @Override
    public boolean isFoil(ItemStack stack) {
        return OvItemData.has(stack, ModDataComponents.LIANA_ANCHOR.get()) || super.isFoil(stack);
    }
}
