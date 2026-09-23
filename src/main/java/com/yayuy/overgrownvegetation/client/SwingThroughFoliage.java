package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * <b>草に判定を吸われずに武器を振れるようにする</b>クライアント側の処理。
 *
 * <p>バニラの照準決定({@code GameRenderer#pick})は「ブロックへの視線判定」を
 * {@code ClipContext.Block.OUTLINE}(= 輪郭の当たり判定)で行うため、当たり判定を持たない背の高い草や
 * 花・蔦でも<b>ブロックとして命中してしまう</b>。そして Mob はブロックより手前にいなければ
 * 照準に選ばれないので、草の向こう側にいる Mob は狙えず、左クリックは草を刈るだけに終わる
 * (森の中の戦闘が「草斬り」になってしまう原因)。
 *
 * <p>そこで {@link com.yayuy.overgrownvegetation.mixin.client.GameRendererSwingThroughFoliageMixin}
 * からバニラの照準決定<b>直後</b>に割り込み、
 *
 * <ol>
 *     <li>照準が当たっているのが「当たり判定の無いブロック」(下草・花・蔦・苔など。
 *         {@link ModTags#NOT_SWING_THROUGH} の例外は除く)で、</li>
 *     <li>メインハンドが武器(剣・斧・トライデント・メイス、または
 *         {@link ModTags#SWINGS_THROUGH_FOLIAGE})で、</li>
 *     <li>その草を透かした先・かつ<b>当たり判定のあるブロック(壁)より手前</b>に、攻撃できる相手が
 *         攻撃可能距離({@code entityInteractionRange})以内にいる</li>
 * </ol>
 *
 * とき、照準をその相手に差し替える。条件を満たさなければバニラのまま(= 草を刈れる)なので、
 * 「奥に相手がいるときだけ草を無視する」挙動になる。スニーク中は差し替えないので、
 * 相手の手前の草を刈りたいときは Shift を押しながら殴ればよい。
 *
 * <p>差し替えるのは<b>クライアントの照準だけ</b>で、攻撃そのものはバニラと同じパケットで飛ぶ
 * (サーバーは距離しか見ないので、草越しの攻撃もそのまま通る)。サーバー側・他プレイヤーの挙動は
 * 変わらない。
 *
 * @see OvergrownVegetationConfig.Common#swingThroughFoliage
 */
public final class SwingThroughFoliage {

    private SwingThroughFoliage() {
    }

    /**
     * バニラが決めた照準({@link Minecraft#hitResult})が「当たり判定の無い草」なら、その奥の Mob へ
     * 差し替える。毎フレーム、照準決定の直後に呼ばれる。
     *
     * @param partialTick そのフレームの補間係数(バニラの照準決定と同じ値を使う)
     */
    public static void retarget(float partialTick) {
        if (!OvergrownVegetationConfig.COMMON.swingThroughFoliage.get()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || minecraft.getCameraEntity() != player) {
            return;
        }
        // スニーク中は「草そのものを刈りたい」意思表示とみなしてバニラのまま
        if (player.isShiftKeyDown()) {
            return;
        }
        if (!(minecraft.hitResult instanceof BlockHitResult blockHit) || blockHit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        if (!isWeapon(player.getMainHandItem())) {
            return;
        }
        BlockPos pos = blockHit.getBlockPos();
        if (!isSwingThrough(level, pos)) {
            return;
        }

        double reach = player.entityInteractionRange();
        if (reach <= 0.0D) {
            return;
        }
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 view = player.getViewVector(partialTick);
        Vec3 end = eye.add(view.scale(reach));

        // 当たり判定のあるブロック(壁)より奥は狙わせない。草・花のような当たり判定の無いブロックは
        // COLLIDER 判定では素通しになるので、ここで止まるのは実際に体を止める壁だけ。
        double limitSqr = reach * reach;
        BlockHitResult wall = level.clip(
                new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (wall.getType() != HitResult.Type.MISS) {
            limitSqr = Math.min(limitSqr, wall.getLocation().distanceToSqr(eye));
        }

        AABB searchBox = player.getBoundingBox().expandTowards(view.scale(reach)).inflate(1.0D);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                player, eye, end, searchBox, SwingThroughFoliage::canTarget, limitSqr);
        if (entityHit == null) {
            return;
        }

        minecraft.hitResult = entityHit;
        minecraft.crosshairPickEntity = entityHit.getEntity();
    }

    /**
     * そのブロックを「武器の振りが透ける草」として扱うか。
     *
     * <p>当たり判定(衝突判定)が空のブロックを一律に対象にしている。この MOD が増やす下草・蔦・苔を
     * 列挙し忘れて取りこぼすことが無く、他 MOD の草にも効くため。ただし当たり判定が無くても
     * 触りたいブロック(レバー・ボタン・看板など)は {@link ModTags#NOT_SWING_THROUGH} で除外する。
     */
    private static boolean isSwingThrough(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(ModTags.NOT_SWING_THROUGH)) {
            return false;
        }
        return state.getCollisionShape(level, pos).isEmpty();
    }

    /** 草越しに狙ってよい相手か。バニラの照準判定({@code GameRenderer#pick})と同じ条件 + 攻撃可能なもの限定。 */
    private static boolean canTarget(Entity entity) {
        return !entity.isSpectator() && entity.isPickable() && entity.isAttackable();
    }

    /** 草を透かして狙う対象になる「武器」を持っているか。 */
    private static boolean isWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        if (item instanceof SwordItem || item instanceof AxeItem || item instanceof TridentItem
                || item instanceof MaceItem) {
            return true;
        }
        return stack.is(ModTags.SWINGS_THROUGH_FOLIAGE);
    }
}
