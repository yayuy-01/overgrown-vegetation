package com.yayuy.overgrownvegetation.client.skyboss;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.SkyBossPartsPayload;
import com.yayuy.overgrownvegetation.network.SkyBossStatePayload;
import com.yayuy.overgrownvegetation.skyboss.SkyBossFlight;
import com.yayuy.overgrownvegetation.skyboss.SkyBossHitboxEntity;
import com.yayuy.overgrownvegetation.skyboss.SkyBossParts;
import com.yayuy.overgrownvegetation.skyboss.SkyBossPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.Optional;
import java.util.UUID;

/**
 * 空の巨大生物のクライアント側の状態(2026-10-18)。{@link SkyBossStatePayload} を受け取るたびに頭の姿勢・操作・道を丸ごと差し替え、
 * あいだの tick はサーバーと同じ式({@link SkyBossFlight#step})で自分で進める。描くのは {@link SkyBossRenderer}。
 *
 * <p>位置は<b>受け取った状態からだけ</b>出す(雲の設定はサーバーと同期されないので、雲の計算で位置を決めない)。
 * ワールドへ入る途中で通信が先に届くので、ワールドの替わりは受け取る所と tick の両方で見る(メモリ client-payload-before-first-tick)。
 *
 * <p>10-18 夕方から: 傾き・扇の層の動きも {@link SkyBossFlight} がサーバーと同じ式で進める(当たり判定の形と見た目を合わせるため)。
 * アニメの時刻はゲームの時刻({@link #animClock}。前はこのクライアントの tick の数)。tick ごとに当たり判定の姿勢 {@link #pose()} も出し、
 * 自分の剣の受け手({@link SkyBossHitboxEntity#KIND_MELEE})を視線の先の形へ置く。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientSkyBoss {

    private static boolean active;
    private static final SkyBossFlight FLIGHT = new SkyBossFlight();
    /** 弱点の壊れ具合(10-18 夜。{@link SkyBossPartsPayload} の時刻だけ。体力は持たない)。 */
    private static final SkyBossParts PARTS = new SkyBossParts();
    private static long seed;
    private static ClientLevel level;
    /** 当たり判定の姿勢(tick ごと。形の元が無ければ形なし)。 */
    private static SkyBossPose pose;
    private static long poseTick = Long.MIN_VALUE;
    private static final SkyBossPose.Hit HIT = new SkyBossPose.Hit();

    static {
        SkyBossHitboxEntity.client = new SkyBossHitboxEntity.ClientHooks() {
            @Override
            public void placeMeleeProxy(SkyBossHitboxEntity proxy) {
                ClientSkyBoss.placeMeleeProxy(proxy);
            }

            @Override
            public boolean isLocalPlayer(Optional<UUID> owner) {
                LocalPlayer p = Minecraft.getInstance().player;
                return p != null && owner.isPresent() && owner.get().equals(p.getUUID());
            }
        };
    }

    public static void onPayload(SkyBossStatePayload payload) {
        resetIfLevelChanged();
        if (!payload.active()) {
            active = false;
            return;
        }
        payload.applyTo(FLIGHT);
        seed = payload.seed();
        ClientLevel lv = Minecraft.getInstance().level;
        if (lv != null) {
            FLIGHT.advanceTo(lv.getGameTime(), 400);
        }
        poseTick = Long.MIN_VALUE;
        active = true;
    }

    /** 技の弾(10-19。撃った・終わった)。 */
    public static void onShot(com.yayuy.overgrownvegetation.network.SkyBossShotPayload payload) {
        resetIfLevelChanged();
        if (level != null) {
            SkyBossShotsView.onPayload(payload, level);
        }
    }

    /** 放電の雷 1 本(10-19。サーバーが当てた雷)。 */
    public static void onArc(com.yayuy.overgrownvegetation.network.SkyBossArcPayload payload) {
        resetIfLevelChanged();
        if (level != null) {
            SkyBossArcs.onArc(payload, level.getGameTime());
        }
    }

    public static void onParts(SkyBossPartsPayload payload) {
        resetIfLevelChanged();
        payload.applyTo(PARTS);
        poseTick = Long.MIN_VALUE;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        resetIfLevelChanged();
        if (!active || level == null || Minecraft.getInstance().isPaused()) {
            return;
        }
        FLIGHT.advanceTo(level.getGameTime(), 400);
        SkyBossShotsView.tick(level);
        // 10-19 夕方: 身震いの雨粒・落とし物・雨の日の滝(カメラの近くだけ)
        SkyBossAmbience.tick(level);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        active = false;
        level = null;
    }

    private static void resetIfLevelChanged() {
        ClientLevel lv = Minecraft.getInstance().level;
        if (lv != level) {
            level = lv;
            active = false;
            PARTS.reset();
            SkyBossArcs.clear();
            SkyBossShotsView.clear();
            poseTick = Long.MIN_VALUE;
        }
    }

    public static boolean isActive() {
        return active && level != null;
    }

    public static SkyBossFlight flight() {
        return FLIGHT;
    }

    public static long seed() {
        return seed;
    }

    /** 弱点の壊れ具合(時刻だけ)。 */
    public static SkyBossParts parts() {
        return PARTS;
    }

    /** 曲がるときの体の傾き(度、partialTick で補間)。 */
    public static float bank(float partialTick) {
        return Mth.lerp(partialTick, FLIGHT.pbank, FLIGHT.bank);
    }

    /**
     * アニメの時刻(ゲームの tick + partialTick。描画の時刻と同じ {@code tick - 1 + pt})。サーバーの当たり判定の形も同じ時刻でアニメを引くので、
     * 体のうねり・扇・触手のコントローラはこれで時刻を決める({@link SkyBossAnimController})。
     */
    public static double animClock() {
        float pt = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        return FLIGHT.tick - 1 + pt;
    }

    /** 今の tick の当たり判定の姿勢(サーバーと同じ: 頭の位置・傾き・時刻は tick の値)。居なければ null。 */
    public static SkyBossPose pose() {
        if (!isActive()) {
            return null;
        }
        if (pose == null) {
            pose = new SkyBossPose();
            pose.parts = PARTS;
        }
        if (poseTick != FLIGHT.tick) {
            pose.compute(FLIGHT, FLIGHT.x, FLIGHT.y, FLIGHT.z, FLIGHT.bank, FLIGHT.tick, true);
            poseTick = FLIGHT.tick;
        }
        return pose;
    }

    /** 自分の剣の受け手を、視線と形の交わる所(届く距離の中)へ置く。 */
    static void placeMeleeProxy(SkyBossHitboxEntity proxy) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !proxy.owner().isPresent() || !proxy.owner().get().equals(p.getUUID())) {
            return;
        }
        SkyBossPose ps = pose();
        if (ps == null || !ps.hasShapes()) {
            proxy.setActive(false);
            return;
        }
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1.0F);
        double reach = p.entityInteractionRange() + 0.5;
        if (ps.raycast(eye.x, eye.y, eye.z, eye.x + look.x * reach, eye.y + look.y * reach, eye.z + look.z * reach, HIT)) {
            proxy.place(HIT.x, HIT.y, HIT.z, HIT.part, HIT.index, true);
        } else {
            proxy.place(eye.x, eye.y, eye.z, SkyBossPose.PART_MOSS, false);
        }
    }

    private ClientSkyBoss() {
    }
}
