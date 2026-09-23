package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.entity.InvaderPack;
import com.yayuy.overgrownvegetation.entity.InvaderPackBond;
import com.yayuy.overgrownvegetation.entity.InvaderPackComposition;
import com.yayuy.overgrownvegetation.entity.InvaderPackMember;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「群れ検査器」— <b>クリエイティブ専用のデバッグ道具</b>(ユーザー要望 2026-09-15:
 * 「群れが形成されているか分かるツール」)。
 *
 * <p>右クリックすると、近くのグループ4 を 1 体つかまえ、<b>そこから辿れる群れ全員を光らせる</b>。
 * 同時に編成の内訳(アルボラ 5/8 …)と広がり方をチャットに出す。
 * Shift + 右クリックで光をすべて消す。
 *
 * <h2>「同じ群れ」の定義</h2>
 * 群れは各個体が憶えている <b>ID</b>({@link InvaderPackBond})。なのでこの道具は
 * <b>起点と同じ ID を持つ個体を照合して集めるだけ</b>で、推測がいっさい要らない。
 * <b>見えている光がそのまま群れの実体</b>で、2 つの塊が別々に光ったらそれは本当に別の群れ。
 *
 * <p>起点がまだ無所属なら群れは存在しないので 1 体だけ光る —— これも<b>正しい表示</b>で、
 * 「まだ群れになっていない」ことがそのまま見える。
 *
 * <p>光らせるのに使うのは<b>発光の効果</b>。Mob に付いた効果はふつうクライアントへ同期されないが、
 * 発光だけは {@code LivingEntity#updateGlowingStatus} が共有フラグ 6 を立てるので輪郭が出る。
 * {@code setGlowingTag} を直に立てる手もあるが、あちらは<b>消し忘れると永久に光ったまま</b>になる。
 */
public class PackScannerItem extends Item {

    /** 起点の 1 体を探す半径(ブロック)。 */
    private static final double SEED_RANGE = 48.0D;
    /** 光らせておく長さ(tick)。 */
    private static final int GLOW_TICKS = 600;
    /** 同じ群れの仲間を探す半径(ブロック)。読み込まれている範囲しか見えない。 */
    private static final double PACK_SEARCH_RANGE = 160.0D;
    /** 1 回に数える上限(体)。壊れた状況で world 中を光らせ続けないための歯止め。 */
    private static final int MAX_MEMBERS = 256;

    public PackScannerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        if (!player.isCreative()) {
            player.displayClientMessage(Component
                    .translatable("item.overgrown_vegetation.pack_scanner.creative_only")
                    .withStyle(ChatFormatting.RED), true);
            return InteractionResultHolder.fail(stack);
        }

        if (player.isShiftKeyDown()) {
            int cleared = clearGlow(serverLevel, player);
            player.displayClientMessage(Component
                    .translatable("item.overgrown_vegetation.pack_scanner.cleared", cleared)
                    .withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.sidedSuccess(stack, false);
        }

        Mob seed = nearestMember(serverLevel, player);
        if (seed == null) {
            player.displayClientMessage(Component
                    .translatable("item.overgrown_vegetation.pack_scanner.none",
                            (int) SEED_RANGE).withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.fail(stack);
        }

        List<Mob> pack = collectPack(seed);
        for (Mob mob : pack) {
            mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_TICKS, 0, false, false, false));
        }
        report(player, seed, pack);
        serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 1.8F);
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    /** 起点にする、いちばん近いグループ4(動ける種)。 */
    @Nullable
    private static Mob nearestMember(ServerLevel level, Player player) {
        AABB box = player.getBoundingBox().inflate(SEED_RANGE);
        Mob best = null;
        double bestSq = SEED_RANGE * SEED_RANGE;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, InvaderPack::isMember)) {
            double sq = player.distanceToSqr(mob);
            if (sq <= bestSq) {
                bestSq = sq;
                best = mob;
            }
        }
        return best;
    }

    /**
     * {@code seed} と<b>同じ群れ ID を持つ個体</b>全員(起点を含む)。
     *
     * <p>群れは {@link InvaderPackBond} の ID そのものなので、<b>照合するだけで確定する</b>。
     * 読み込まれている範囲({@value #PACK_SEARCH_RANGE} ブロック)しか見えないので、
     * 遠くで固まっている仲間は数に入らない。
     *
     * <p>起点がまだ<b>無所属</b>なら群れは存在しないので、起点 1 体だけを返す。
     */
    private static List<Mob> collectPack(Mob seed) {
        List<Mob> pack = new ArrayList<>();
        pack.add(seed);
        UUID id = ((InvaderPackMember) seed).packBond().id();
        if (id == null) {
            return pack;
        }
        AABB box = seed.getBoundingBox().inflate(PACK_SEARCH_RANGE);
        for (Mob mob : seed.level().getEntitiesOfClass(Mob.class, box, InvaderPack::isMember)) {
            if (mob != seed && id.equals(((InvaderPackMember) mob).packBond().id())
                    && pack.size() < MAX_MEMBERS) {
                pack.add(mob);
            }
        }
        return pack;
    }

    /** 光を消す(近くの群れ全部)。{@code GLOW_TICKS} を待たずに見た目を戻したいとき用。 */
    private static int clearGlow(ServerLevel level, Player player) {
        AABB box = player.getBoundingBox().inflate(SEED_RANGE * 2.0D);
        int cleared = 0;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, InvaderPack::isMember)) {
            if (mob.removeEffect(MobEffects.GLOWING)) {
                cleared++;
            }
        }
        return cleared;
    }

    /** 編成の内訳と広がり方をチャットへ。 */
    private static void report(Player player, Mob seed, List<Mob> pack) {
        Map<EntityType<?>, Integer> have = new LinkedHashMap<>();
        Mob point = null;
        for (Mob mob : pack) {
            have.merge(mob.getType(), 1, Integer::sum);
            if (point == null && mob instanceof InvaderPackMember member && member.isPackPointUnit()) {
                point = mob;
            }
        }

        player.displayClientMessage(Component
                .translatable("item.overgrown_vegetation.pack_scanner.header",
                        pack.size(), ((InvaderPackMember) seed).packBond().shortId(),
                        (int) player.distanceTo(seed))
                .withStyle(ChatFormatting.GREEN), false);

        // 編成表に載っている種は「いま/必要」で、載っていない種はただの頭数で出す。
        Map<EntityType<?>, Integer> required = InvaderPackComposition.required();
        for (Map.Entry<EntityType<?>, Integer> entry : required.entrySet()) {
            int count = have.getOrDefault(entry.getKey(), 0);
            boolean ok = count >= entry.getValue();
            player.displayClientMessage(Component
                    .translatable("item.overgrown_vegetation.pack_scanner.entry",
                            Component.translatable(entry.getKey().getDescriptionId()),
                            count, entry.getValue())
                    .withStyle(ok ? ChatFormatting.GRAY : ChatFormatting.YELLOW), false);
        }
        for (Map.Entry<EntityType<?>, Integer> entry : have.entrySet()) {
            if (required.containsKey(entry.getKey())) {
                continue;
            }
            player.displayClientMessage(Component
                    .translatable("item.overgrown_vegetation.pack_scanner.extra",
                            Component.translatable(entry.getKey().getDescriptionId()), entry.getValue())
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }

        // 編成が揃っていれば、この群れはもう消えない(InvaderDespawn)。
        boolean persistent = ((InvaderPackMember) seed).packBond().completeAt(seed.tickCount);
        player.displayClientMessage(Component
                .translatable(persistent
                        ? "item.overgrown_vegetation.pack_scanner.persistent"
                        : "item.overgrown_vegetation.pack_scanner.not_persistent")
                .withStyle(persistent ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY), false);

        // 先遣隊からいちばん離れている個体 = 隊列が崩れていないかの目安。
        if (point != null) {
            double worst = 0.0D;
            for (Mob mob : pack) {
                if (mob != point) {
                    worst = Math.max(worst, point.distanceTo(mob));
                }
            }
            player.displayClientMessage(Component
                    .translatable("item.overgrown_vegetation.pack_scanner.spread",
                            Component.translatable(point.getType().getDescriptionId()),
                            String.format("%.0f", worst))
                    .withStyle(ChatFormatting.GRAY), false);
        } else {
            player.displayClientMessage(Component
                    .translatable("item.overgrown_vegetation.pack_scanner.no_point")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
    }
}
