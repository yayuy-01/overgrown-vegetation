package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.OrganTable;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>胞子腺</b> —— 器官の表面に点在する腺(2026-09-23、要望)。
 *
 * <p>器官そのものではない。器官の本体は<b>節</b>({@link OrganNodeBlock})で、
 * これはその体の表面に浮いた<b>吹き出し口</b>。塔の先端のものが胞子を噴いている。
 *
 * <h2>削ると浴びる</h2>
 * <p>壊すと {@code organs.json} の {@code glandPenalty} に従って、
 * <b>毒・吐き気・盲目・空腹5・胞子感染2・採掘速度低下</b> の 6 つから
 * 重複なしで 2 つ(既定)が掛かる。
 *
 * <p>狙いは<b>節まで掘る道のりに値段を付ける</b>こと。節は根元の塊に埋まっているので、
 * まっすぐ削って進むと必ず腺に当たる。慎重に迂回すれば避けられるし、
 * 薬と食料を積んで正面から削り切ってもいい —— どちらもプレイヤーの選択。
 *
 * <p>掛かるのは<b>壊し切った瞬間</b>({@code playerWillDestroy})。
 * 殴っている間ずっと積むと、1 マス削る間に効果が何十回も更新されて収拾がつかない。
 *
 * <h2>噴き出し</h2>
 * <p>上が開いていれば胞子の粒を上へ吐く({@code animateTick}、クライアントだけ)。
 * 塔の先端はいちばん上のマスが必ず腺になっているので、そこが噴き出し口に見える。
 * 側面の腺も薄く漏らすので、塔全体が「呼吸している」ように見える。
 */
public class SporeGlandBlock extends Block {

    public SporeGlandBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && !player.isCreative()) {
            punish(player, level.random);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** 6 つの候補から重複なしで {@code effects} 個を選んで掛ける。 */
    private static void punish(Player player, RandomSource random) {
        OrganTable.GlandPenalty penalty = DesignData.organs().sporeGland().glandPenalty();
        int ticks = penalty.durationSeconds() * 20;
        List<Integer> pool = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5));
        for (int i = 0; i < penalty.effects() && !pool.isEmpty(); i++) {
            int pick = pool.remove(random.nextInt(pool.size()));
            switch (pick) {
                case 0 -> player.addEffect(new MobEffectInstance(MobEffects.POISON, ticks, 0));
                case 1 -> player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, ticks, 0));
                case 2 -> player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, ticks, 0));
                // 空腹 5 = amplifier 4。食料ゲージを一気に削る。
                case 3 -> player.addEffect(new MobEffectInstance(MobEffects.HUNGER, ticks, 4));
                // 胞子感染 2 = amplifier 1。既に上の段階を持っていれば素通りする(感染の規則どおり)。
                case 4 -> SporeInfectionEffects.infect(player, 1);
                default -> player.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, ticks, 0));
            }
        }
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!level.getBlockState(pos.above()).isAir()) {
            return; // 塞がっている腺は噴けない。
        }
        // 先端ほど派手に見せたいが、高さを見て変えると塔以外の器官で破綻する。
        // 「上が開いている腺だけが噴く」に統一しておけば、どの形でも輪郭から立ち上る。
        for (int i = 0; i < 2; i++) {
            level.addParticle(ModParticles.OVERGROWN_SPORE.get(),
                    pos.getX() + 0.3D + random.nextDouble() * 0.4D,
                    pos.getY() + 1.0D,
                    pos.getZ() + 0.3D + random.nextDouble() * 0.4D,
                    (random.nextDouble() - 0.5D) * 0.02D,
                    0.05D + random.nextDouble() * 0.05D,
                    (random.nextDouble() - 0.5D) * 0.02D);
        }
    }
}
