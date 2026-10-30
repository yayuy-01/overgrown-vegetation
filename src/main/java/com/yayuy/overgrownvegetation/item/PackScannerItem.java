package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import com.yayuy.overgrownvegetation.swarm.SwarmRole;
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

/**
 * 「群れ検査器」— <b>クリエイティブ専用のデバッグ道具</b>(ユーザー要望 2026-09-15:
 * 「群れが形成されているか分かるツール」)。
 *
 * <p>右クリックすると、近くのグループ4 を 1 体つかまえ、<b>その群れの一員全員を光らせる</b>。
 * 同時に種ごとの頭数・群れの種類と出どころ・モード・先導役からの広がりをチャットに出す。
 * Shift + 右クリックで光をすべて消す。
 *
 * <h2>「同じ群れ」の定義(群れ v2、2026-09-25)</h2>
 * 群れは司令塔の名簿({@link SwarmManager#get})そのもの。なのでこの道具は<b>起点の群れの名簿を読むだけ</b>で、
 * 推測がいっさい要らない。<b>見えている光がそのまま群れの実体</b>で、2 つの塊が別々に光ったらそれは本当に別の群れ。
 * 名簿に載るのは読み込まれている一員だけ。
 *
 * <p>起点が群れに居ない個体(スポーンエッグ・{@code /summon}・外湧き・群れから外れた個体)なら 1 体だけ光る
 * —— これも<b>正しい表示</b>で、「群れに入っていない」ことがそのまま見える。
 * 旧い「編成の充足(アルボラ 5/8 …)・揃った群れは消えない」の表示は、ユーザー指定 2026-09-25
 * (編成は湧いた時点で揃う・消えるときは群れごと)で意味が無くなったので消した。
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

        Swarm swarm = SwarmManager.get(seed);
        List<Mob> pack = collectPack(seed, swarm);
        for (Mob mob : pack) {
            mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, GLOW_TICKS, 0, false, false, false));
        }
        report(player, seed, swarm, pack);
        serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 1.8F);
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    /**
     * 群れに入れる種か(アルボラ・スカトゥリウム・スポロフィル・フィロセンス・ヴェロフィル。{@link SwarmRole#of})。
     * ペディキュラは群れに入らない(ユーザー指定 2026-09-25)ので起点にもしない。
     */
    private static boolean isSwarmSpecies(Mob mob) {
        return mob.isAlive() && SwarmRole.of(mob.getType()) != null;
    }

    /** 起点にする、いちばん近いグループ4(群れに入れる種)。 */
    @Nullable
    private static Mob nearestMember(ServerLevel level, Player player) {
        AABB box = player.getBoundingBox().inflate(SEED_RANGE);
        Mob best = null;
        double bestSq = SEED_RANGE * SEED_RANGE;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, PackScannerItem::isSwarmSpecies)) {
            double sq = player.distanceToSqr(mob);
            if (sq <= bestSq) {
                bestSq = sq;
                best = mob;
            }
        }
        return best;
    }

    /**
     * {@code seed} の群れの一員全員(起点を含む)。群れの名簿の写しを読むだけ(読み込まれている一員しか載らない)。
     * 起点が群れに居なければ、起点 1 体だけを返す。
     */
    private static List<Mob> collectPack(Mob seed, @Nullable Swarm swarm) {
        List<Mob> pack = new ArrayList<>();
        pack.add(seed);
        if (swarm == null) {
            return pack;
        }
        for (Mob mob : swarm.members()) {
            if (mob != seed && pack.size() < MAX_MEMBERS) {
                pack.add(mob);
            }
        }
        return pack;
    }

    /** 光を消す(近くの群れ全部)。{@code GLOW_TICKS} を待たずに見た目を戻したいとき用。 */
    private static int clearGlow(ServerLevel level, Player player) {
        AABB box = player.getBoundingBox().inflate(SEED_RANGE * 2.0D);
        int cleared = 0;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, PackScannerItem::isSwarmSpecies)) {
            if (mob.removeEffect(MobEffects.GLOWING)) {
                cleared++;
            }
        }
        return cleared;
    }

    /** 種ごとの頭数・群れの様子・先導役からの広がりをチャットへ。 */
    private static void report(Player player, Mob seed, @Nullable Swarm swarm, List<Mob> pack) {
        player.displayClientMessage(Component
                .translatable("item.overgrown_vegetation.pack_scanner.header",
                        pack.size(), swarm != null ? swarm.shortId() : "-",
                        (int) player.distanceTo(seed))
                .withStyle(ChatFormatting.GREEN), false);
        if (swarm == null) {
            player.displayClientMessage(Component
                    .translatable("item.overgrown_vegetation.pack_scanner.individual")
                    .withStyle(ChatFormatting.YELLOW), false);
            return;
        }

        Map<EntityType<?>, Integer> have = new LinkedHashMap<>();
        for (Mob mob : pack) {
            have.merge(mob.getType(), 1, Integer::sum);
        }
        for (Map.Entry<EntityType<?>, Integer> entry : have.entrySet()) {
            player.displayClientMessage(Component
                    .translatable("item.overgrown_vegetation.pack_scanner.count",
                            Component.translatable(entry.getKey().getDescriptionId()), entry.getValue())
                    .withStyle(ChatFormatting.GRAY), false);
        }

        // 群れの種類(地上 / 空)・出どころ・モード・いちばん多かったときの頭数(デバッグ用なので列挙の名前のまま)。
        player.displayClientMessage(Component
                .translatable("item.overgrown_vegetation.pack_scanner.info",
                        swarm.kind().name(), swarm.origin().name(), swarm.mode().name(), swarm.peak())
                .withStyle(ChatFormatting.AQUA), false);

        // 先導役からいちばん離れている一員 = 隊列が崩れていないかの目安。
        Mob point = swarm.vanguard();
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
