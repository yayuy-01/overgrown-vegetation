package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.spore.GearContamination;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * <b>汚染耐性</b> — 鍛冶台で樹液結晶を打ち込んだ
 * {@code #overgrown_vegetation:overgrowth_ward} の防具を着ていると、グループ1〜10({@link MobGroup})
 * から受けるダメージが軽くなる(ユーザー要望 2026-09-16)。
 *
 * <h2>効き方</h2>
 * <p>4 部位そろえて着ていると {@code gear.sapCrystalWardMultiplier}(既定 0.8)倍。
 * 部位が欠けているぶんは<b>比例して弱まる</b> — 軽減 20% を 4 部位で割り、
 * 打ち込んである 1 部位につき 5% ずつ効く(2 部位なら 0.9 倍)。
 * 「防具に塗布すると 0.8 倍」という要望をそのまま満たしつつ、部位ごとに意味を持たせるための配分。
 *
 * <p>条件は<b>タグに入っていること</b>と<b>樹液結晶を打ち込んであること</b>の両方。
 * 打ち込んでいないクロロフィライト防具や、打ち込んであってもタグ外の防具には効かない。
 *
 * <h2>何からのダメージに効くか</h2>
 * <ul>
 *     <li>グループ1〜10 の Mob が与えたダメージ。矢や胞子爆弾など<b>間接的なものも含む</b>
 *     ({@code getEntity()} は撃った本人を返すため)。防具なので殴られ方を問わない。</li>
 *     <li>攻城戦ボスの攻撃({@link ModTags#SIEGE_BOSS_ATTACK})。ダメージ源にエンティティが
 *     付いていないので、{@link MobGroupFriendlyFire} と同じくタグで見分ける。</li>
 * </ul>
 *
 * <p>汚染で弱った防具は防御力そのものが落ちる
 * ({@link com.yayuy.overgrownvegetation.spore.GearContaminationEffects})が、この軽減率は別枠で、
 * 汚染度に関係なく一定。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OvergrowthWardDamage {

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide() || !isFromOvergrowth(event.getSource())) {
            return;
        }
        double reduction = totalReduction(victim);
        if (reduction <= 0.0D) {
            return;
        }
        // 完全無敵にはしない(軽減が 100% を超えても 1 ダメージぶんは通す)。
        event.setAmount((float) (event.getAmount() * (1.0D - Math.min(reduction, 1.0D))));
    }

    /** 汚染側から来たダメージか。 */
    private static boolean isFromOvergrowth(DamageSource source) {
        if (source.is(ModTags.SIEGE_BOSS_ATTACK)) {
            return true;
        }
        return source.getEntity() instanceof LivingEntity attacker && MobGroup.of(attacker) != null;
    }

    /**
     * 身に着けている防具から集めた軽減率の合計(0〜1)。2 つの出どころを足し合わせる。
     *
     * <ul>
     *     <li><b>樹液結晶の塗布</b>: {@code #overgrowth_ward} のタグに入っていて、かつ鍛冶台で
     *     樹液結晶を打ち込んである部位。4 部位そろって {@code sapCrystalWardMultiplier} 倍
     *     (既定 0.8 = 軽減 20%)になるよう、1 部位あたりその 1/4。</li>
     *     <li><b>素材そのものの耐性</b>: {@code #overgrowth_resistant} のタグに入っている部位
     *     (強化クロロフィライト合金装備)。1 部位あたり {@code overgrowthResistantPerPiece}(既定 0.1)。
     *     打ち込みは要らない。</li>
     * </ul>
     *
     * <p>両方に当てはまる部位は両方ぶん効く(今は重なる装備は無い)。
     */
    private static double totalReduction(LivingEntity victim) {
        double wardPerPiece =
                (1.0D - OvergrownVegetationConfig.COMMON.sapCrystalWardMultiplier.get()) / ARMOR_SLOTS.length;
        double resistantPerPiece = OvergrownVegetationConfig.COMMON.overgrowthResistantPerPiece.get();
        double total = 0.0D;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = victim.getItemBySlot(slot);
            if (stack.is(ModTags.OVERGROWTH_WARD) && GearContamination.isSapCrystalTreated(stack)) {
                total += wardPerPiece;
            }
            if (stack.is(ModTags.OVERGROWTH_RESISTANT)) {
                total += resistantPerPiece;
            }
        }
        return total;
    }

    private OvergrowthWardDamage() {
    }
}
