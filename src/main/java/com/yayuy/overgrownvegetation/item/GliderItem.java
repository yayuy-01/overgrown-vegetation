package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.LeyUpdraftBlockEntity;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;

/**
 * 布のグライダー(仮の名。2026-10-22、ユーザー指定: 手に持って使う・両手持ち・浮き袋の落下傘を消してこれに統合)。
 * <ul>
 *     <li>利き手に持って空中で落ち始めると({@link #OPEN_FALL} ブロック落ちたら。上昇気流機の柱の中ならすぐ)開き、毎 tick 前へ {@link #SPEED}・下へ {@link #SINK}
 *     (前 1 : 下 0.25。仮決め)で滑空する。エリトラより遅く、羽ばたき・ロケットは無い。向きは視線の水平の向きへ毎 tick {@link #TURN} ずつ寄せる(ゆっくり曲がる)。</li>
 *     <li>上昇気流機の柱の中({@link LeyUpdraftBlockEntity#isInColumn})では上へ {@link #UPDRAFT_LIFT}(仮決め)で上がる。</li>
 *     <li>地面に着く・水に入る・持ち替える・飛ぶ(クリエイティブ)・エリトラで閉じる。開いている間は落下のダメージが無い。</li>
 *     <li>布なので開いている 1 秒ごとに耐久 1(滑空 10 分 = {@link #DURABILITY}。仮決め)。残り 1 になると開かない(壊れて消えはしない。エリトラと同じ)。
 *     金床で植物の繊維で直す。</li>
 *     <li>同期: プレイヤーの動きは手元のクライアントが決めるので、速さは両サイドの {@link PlayerTickEvent.Post} で同じ計算をする
 *     (浮き袋の落下傘と同じ作り)。落下の距離を消す・耐久を減らすのはサーバー。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public class GliderItem extends Item {

    /** 耐久 = 開いていられる秒数(仮決め 10 分)。 */
    public static final int DURABILITY = 600;
    /** これだけ落ちたら開く(ブロック。仮決め。ふつうのジャンプでは開かない)。 */
    public static final float OPEN_FALL = 1.5F;
    /** 滑空の前への速さ(ブロック/tick。仮決め。エリトラの巡航のおよそ 1/4)。 */
    public static final double SPEED = 0.4D;
    /** 滑空の落ちる速さ(ブロック/tick)= 前の 1/4(ユーザー指定の比)。 */
    public static final double SINK = SPEED * 0.25D;
    /** 横の速さを視線の向きへ寄せる割合(毎 tick)。 */
    public static final double TURN = 0.08D;
    /** 上昇気流の柱の中で上がる速さ(ブロック/tick。仮決め)。 */
    public static final double UPDRAFT_LIFT = 0.15D;
    /** 柱の中で横の速さに掛ける割合(毎 tick。仮決め)。 */
    public static final double COLUMN_DRAG = 0.5D;

    public GliderItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isValidRepairItem(ItemStack stack, ItemStack repair) {
        return repair.is(ModItems.PLANT_FIBER.get());
    }

    /** まだ開けるか(耐久が 1 より多く残っている)。 */
    public static boolean usable(ItemStack stack) {
        return stack.getDamageValue() < stack.getMaxDamage() - 1;
    }

    /** 開いているか。利き手のグライダー・空中・水の外・飛んでいない・落ちている(柱の中なら上がっていても)。 */
    public static boolean isOpen(Player player) {
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof GliderItem) || !usable(stack)) {
            return false;
        }
        if (player.onGround() || player.isInWater() || player.isInLava() || player.getAbilities().flying || player.isFallFlying()
                || player.isPassenger() || player.isSpectator() || player.onClimbable()) {
            return false;
        }
        if (LeyUpdraftBlockEntity.isInColumn(player.level(), player.position())) {
            return true;
        }
        return player.fallDistance > OPEN_FALL || player.getPersistentData().getBoolean(OPEN_TAG);
    }

    /** 開いたままの目印(一度開いたら、着地などで閉じるまで落下の距離が 0 でも開いたまま。柱の上で上がり切っても閉じない)。 */
    private static final String OPEN_TAG = "overgrown_vegetation_glider_open";

    /** 1 tick ぶんの滑空(両サイド)。開いていなければ何もしない。開いていたら true。 */
    public static boolean glide(Player player) {
        boolean open = isOpen(player);
        player.getPersistentData().putBoolean(OPEN_TAG, open);
        if (!open) {
            return false;
        }
        Vec3 v = player.getDeltaMovement();
        float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        double tx = -Mth.sin(yaw) * SPEED, tz = Mth.cos(yaw) * SPEED;
        double x = v.x + (tx - v.x) * TURN, z = v.z + (tz - v.z) * TURN;
        double y = Math.max(v.y, -SINK);
        if (LeyUpdraftBlockEntity.isInColumn(player.level(), player.position())) {
            y = player.isShiftKeyDown() ? Math.max(v.y, LeyUpdraftBlockEntity.SINK) : Math.max(v.y, UPDRAFT_LIFT);
            // 柱は 1 マスの幅しか無いので、中では前へ進まず(横の速さを毎 tick 半分に)真上へ昇る。抜けたら前へ滑空に戻る(仮決め)
            x = v.x * COLUMN_DRAG;
            z = v.z * COLUMN_DRAG;
        }
        player.setDeltaMovement(x, y, z);
        return true;
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (!glide(player) || player.level().isClientSide()) {
            return;
        }
        player.resetFallDistance();
        player.getPersistentData().putBoolean(OPEN_TAG, true);
        if (player.tickCount % 20 == 0 && player.level() instanceof ServerLevel level) {
            wear(level, player, player.getMainHandItem());
        }
    }

    /** 開いている 1 秒ぶん傷める(残り 1 で止める。サーバー)。 */
    public static void wear(ServerLevel level, Player player, ItemStack stack) {
        if (player.getAbilities().instabuild || !usable(stack)) {
            return;
        }
        stack.setDamageValue(stack.getDamageValue() + 1);
        if (!usable(stack)) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.WOOL_BREAK, SoundSource.PLAYERS, 1.0F, 1.2F);
        }
    }

    /** 開いていた tick の着地はダメージなし(落下の距離は毎 tick 消しているが、最後の 1 tick の分も)。 */
    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (event.getEntity() instanceof Player player && player.getPersistentData().getBoolean(OPEN_TAG)) {
            event.setDamageMultiplier(0.0F);
            player.getPersistentData().putBoolean(OPEN_TAG, false);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.glider").withStyle(ChatFormatting.GRAY));
        if (!usable(stack)) {
            tooltip.add(Component.translatable("tooltip.overgrown_vegetation.glider.torn").withStyle(ChatFormatting.RED));
        }
    }
}
