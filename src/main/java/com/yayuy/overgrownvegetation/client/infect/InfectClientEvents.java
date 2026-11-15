package com.yayuy.overgrownvegetation.client.infect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/** 汎用の感染体の、クライアント側の後片付けと確かめ用の入口。 */
public final class InfectClientEvents {

    private InfectClientEvents() {
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent
        public static void onRegisterReload(RegisterClientReloadListenersEvent event) {
            // 資源パックが替わると元の絵が変わる → 塗り替えた絵を作り直す
            event.registerReloadListener((ResourceManagerReloadListener) rm -> InfectRender.reset());
        }
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
    public static final class GameBus {
        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            GenericInfectionClient.clear();
        }

        /** 変わる動きの途中の個体を見て、植物が突き出る瞬間に 1 回だけ粒と音を出す(描画に入っていなくても鳴るよう tick で見る)。 */
        @SubscribeEvent
        public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null || mc.isPaused() || GenericInfectionClient.count() == 0) {
                if (GenericInfectionClient.count() == 0) {
                    BURST_DONE.clear();
                }
                return;
            }
            long now = mc.level.getGameTime();
            for (Entity e : mc.level.entitiesForRendering()) {
                GenericInfectionClient.State s = GenericInfectionClient.of(e);
                if (s == null || s.transformTicks() <= 0) {
                    continue;
                }
                float p = s.progress(now, 0.0F);
                if (p >= InfectSkins.STAGE1_AT && p < 1.0F && BURST_DONE.add(e.getId())) {
                    burst(mc.level, e);
                }
            }
        }

        @SubscribeEvent
        public static void onLeave(EntityLeaveLevelEvent event) {
            if (event.getLevel().isClientSide()) {
                GenericInfectionClient.remove(event.getEntity().getId());
                BURST_DONE.remove(event.getEntity().getId());
            }
        }
    }

    /**
     * 確かめ用: {@code center} から {@code radius} の中の生き物(プレイヤー以外)を、感染体として描かせる。{@code radius <= 0} で全部戻す。
     * 同期(サーバーの印)が入るまでの仮の入口。返り値 = 印を付けた数。
     */
    private static final it.unimi.dsi.fastutil.ints.IntSet BURST_DONE = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

    /**
     * 植物が体を破って突き出る瞬間の粒と音(2026-11-15 ユーザー「足す方向で」)。粒 = 胞子の綿毛 + 苔の破片(体の表面から外へ)。
     * 音 = 苗床の芽吹きの音の流用【仮】(専用の「湿った裂ける音」を作るまで)。数は体の大きさで【仮】。
     */
    private static void burst(ClientLevel level, Entity e) {
        net.minecraft.util.RandomSource r = level.random;
        double w = e.getBbWidth(), h = e.getBbHeight();
        int fluff = (int) Math.min(24, 5 + w * h * 6), bits = (int) Math.min(30, 8 + w * h * 8);
        var moss = new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK,
                com.yayuy.overgrownvegetation.registry.ModBlocks.OVERGROWN_BLOCK.get().defaultBlockState());
        for (int i = 0; i < fluff + bits; i++) {
            double dx = (r.nextDouble() - 0.5) * w, dz = (r.nextDouble() - 0.5) * w, y = e.getY() + h * (0.35 + 0.65 * r.nextDouble());
            if (i < fluff) {
                level.addParticle(com.yayuy.overgrownvegetation.registry.ModParticles.SPORE_FLUFF.get(), e.getX() + dx, y, e.getZ() + dz,
                        dx * 0.08, 0.02 + r.nextDouble() * 0.04, dz * 0.08);
            } else {
                level.addParticle(moss, e.getX() + dx, y, e.getZ() + dz, dx * 0.6, 0.1 + r.nextDouble() * 0.15, dz * 0.6);
            }
        }
        level.playLocalSound(e.getX(), e.getY() + h * 0.5, e.getZ(), com.yayuy.overgrownvegetation.registry.ModSounds.NURSERY_SPROUT.get(),
                e.getSoundSource(), 0.9F, 0.85F + r.nextFloat() * 0.3F, false);
    }

    /** 確かめ用: 体から生える部品の切り替え(重さの比べ)。 */
    public static boolean devParts(boolean on) {
        InfectParts.enabled = on;
        return on;
    }

    /** 確かめ用: 塗りの数をその場で変える(次に塗り直す絵から効く)。 */
    public static String devTune(int index, double value) {
        if (index < 0 || index >= InfectPaint.TUNE.length) {
            return "番号は 0〜" + (InfectPaint.TUNE.length - 1);
        }
        InfectPaint.TUNE[index] = value;
        return java.util.Arrays.toString(InfectPaint.TUNE);
    }

    /** 確かめ用: 塗り替えた絵と位置の絵を書き出すフォルダ(null で止める)。 */
    public static void devDump(@javax.annotation.Nullable java.nio.file.Path dir) {
        InfectSkins.dumpDir = dir;
    }

    public static int devMark(ClientLevel level, Vec3 center, double radius) {
        GenericInfectionClient.clear();
        InfectRender.reset();
        if (radius <= 0.0D) {
            return 0;
        }
        int n = 0;
        for (Entity e : level.entitiesForRendering()) {
            if (e instanceof LivingEntity && !(e instanceof Player) && e.position().distanceToSqr(center) <= radius * radius) {
                GenericInfectionClient.put(e.getId(), e.getUUID().getLeastSignificantBits(), level.getGameTime(), 0, false);
                n++;
            }
        }
        return n;
    }
}
