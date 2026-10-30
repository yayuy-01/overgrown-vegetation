package com.yayuy.overgrownvegetation.td;

import com.yayuy.overgrownvegetation.network.TdHordePayload;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * TD の行進中の群れの<b>隊列</b>(遠景の身代わりの並び)。クライアント({@code client/horde/ClientHorde})が絵をこの位置に並べ、
 * サーバー({@link TdTicker} の実体化)は空の群れをこの位置に湧かせる —— 絵が消えた所に本物が出るように、両方が同じ式で作る
 * (2026-09-25 のユーザー報告「ヴェロフィルが mob になるとき、絵は空中にいるのに mob になった瞬間地面に湧く」。はじめは隊列は
 * クライアントだけにあり、サーバーは空の群れも地上の群れと同じく地面に置いていた)。
 *
 * <ul>
 *     <li>ひまわりの並び(一員 i の半径 = 前の一員の面積の和の平方根)。大きい種ほど中心・間隔も体の大きさに合わせる。
 *     行進の向きに 1.3 倍・横に 0.85 倍(縦に長い群れ)。</li>
 *     <li>乱数は群れの番号から(どのクライアントでも、サーバーでも同じ並び)。</li>
 *     <li>空の群れの高さ: 群れの高さと「梢・地形の上 {@value #AIR_CLEARANCE}」の高い方 + 一員ごとの {@code lift}。</li>
 * </ul>
 */
public final class HordeFormation {

    /** 1 群れで並べる一員の上限(壊れたパケットで大量に作らない)。 */
    public static final int MAX_MEMBERS = 128;
    /** 空の群れが梢・地形の上に空ける高さ(ブロック)。仮決め 2026-09-25。 */
    public static final float AIR_CLEARANCE = 6.0F;
    /** ひまわりの並びの角度の歩み(黄金角)。 */
    private static final float GOLDEN_ANGLE = 2.3999631F;

    /**
     * 隊列の 1 席。位置は群れの向きに対して(ブロック)。
     *
     * @param forward   前(行進の向き)へのずれ
     * @param side      横へのずれ(前を向いて左 = 正。{@link #x}・{@link #z} の式)
     * @param phase     揺れの位相
     * @param lift      空の群れの高さのずれ(−2〜2)
     * @param scale     描く体格(ふだんの体格 × 0.92〜1.08)
     * @param yawJitter 向きのばらし(度、−12〜12)
     */
    public record Slot(EntityType<?> type, float forward, float side, float phase, float lift, float scale, float yawJitter) {
    }

    /** 群れの番号 {@code id} と種ごとの頭数(並びの順)から隊列を作る。 */
    public static List<Slot> slots(int id, List<TdHordePayload.Count> counts) {
        List<EntityType<?>> types = new ArrayList<>();
        for (TdHordePayload.Count c : counts) {
            for (int k = 0; k < c.count() && types.size() < MAX_MEMBERS; k++) {
                types.add(c.type());
            }
        }
        types.sort(Comparator.comparingDouble(t -> -footprint(t)));
        RandomSource random = RandomSource.create(id * 0x9E3779B97F4A7C15L);
        float start = random.nextFloat() * Mth.TWO_PI;
        List<Slot> slots = new ArrayList<>(types.size());
        double area = 0.0D;
        for (int i = 0; i < types.size(); i++) {
            EntityType<?> type = types.get(i);
            double size = footprint(type);
            float radius = (float) (0.55D * Math.sqrt(area + size * size * 0.5D));
            area += size * size;
            float angle = start + i * GOLDEN_ANGLE;
            float forward = radius * Mth.cos(angle) * 1.3F;
            float side = radius * Mth.sin(angle) * 0.85F;
            float scale = baseScale(type) * (0.92F + random.nextFloat() * 0.16F);
            slots.add(new Slot(type, forward, side, random.nextFloat() * Mth.TWO_PI, (random.nextFloat() - 0.5F) * 4.0F,
                    scale, (random.nextFloat() - 0.5F) * 24.0F));
        }
        return slots;
    }

    /** 席のワールドの x(群れの中心 {@code cx}、前 = ({@code fx}, {@code fz}) の単位ベクトル)。 */
    public static double x(double cx, float fx, float fz, Slot slot) {
        return cx + fx * slot.forward() - fz * slot.side();
    }

    /** 席のワールドの z。 */
    public static double z(double cz, float fx, float fz, Slot slot) {
        return cz + fz * slot.forward() + fx * slot.side();
    }

    /** 前 ({@code fx}, {@code fz}) を向く yaw(度。Minecraft の前 = (−sin, 0, cos))。 */
    public static float yaw(float fx, float fz) {
        return (float) Math.toDegrees(Math.atan2(-fx, fz));
    }

    /** 空の群れの一員の高さ: 群れの高さ {@code swarmY} と「梢・地形の一番上 {@code canopyTop} + {@value #AIR_CLEARANCE}」の高い方 + lift。 */
    public static double airY(double swarmY, double canopyTop, Slot slot) {
        return Math.max(swarmY, canopyTop + AIR_CLEARANCE) + slot.lift();
    }

    /** 隊列で 1 体が占める幅の目安(ブロック)= 種の幅 × ふだんの体格 × 1.3(最低 1.2)。 */
    public static double footprint(EntityType<?> type) {
        return Math.max(1.2D, Math.max(1.0D, type.getWidth()) * baseScale(type) * 1.3D);
    }

    /** その種のふだんの体格(属性 SCALE の基礎値)。 */
    @SuppressWarnings("unchecked")
    public static float baseScale(EntityType<?> type) {
        try {
            AttributeSupplier supplier = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);
            return supplier.hasAttribute(Attributes.SCALE) ? (float) supplier.getBaseValue(Attributes.SCALE) : 1.0F;
        } catch (RuntimeException e) {
            return 1.0F;
        }
    }

    private HordeFormation() {
    }
}
