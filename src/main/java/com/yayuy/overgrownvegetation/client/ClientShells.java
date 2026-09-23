package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.network.ShellFiredPayload;
import com.yayuy.overgrownvegetation.network.ShellImpactPayload;
import com.yayuy.overgrownvegetation.siege.CannonBallistics;
import com.yayuy.overgrownvegetation.siege.ShellTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 砲台の砲弾のクライアント側の状態。{@link ShellFiredPayload} で受け取った「発射位置・初速・発射時刻」から、
 * サーバーと同じ {@link CannonBallistics#positionAt} の式で毎フレームの位置を出す(座標同期なし)。
 * {@link ShellImpactPayload} で消え、着弾の閃光({@link Flash})と音に変わる。描画は {@link CannonShellRenderer}。
 *
 * <p>時刻の基準はクライアントの {@code level.getGameTime()}。サーバーから 20 tick ごとに合わせられるので、
 * サーバーの発射時刻とそのまま比較できる(ずれても数 tick = 数十ブロック以内)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientShells {

    /** 着弾の閃光の長さ(tick)。 */
    public static final int FLASH_TICKS = 18;
    /** これより遠い着弾は、サーバーの爆発音が届かないので代わりに遠雷のような音を鳴らす。 */
    private static final double FAR_SOUND_DISTANCE = 48.0D;

    /** 飛行中の砲弾。{@code kind} は {@link ShellTracker#KIND_CANNON} / {@link ShellTracker#KIND_SPORE}。 */
    public record Shell(int id, int kind, Vec3 origin, Vec3 velocity, double gravity, long startGameTime) {
        public Vec3 positionAt(double t) {
            return CannonBallistics.positionAt(origin, velocity, gravity, t);
        }

        public Vec3 velocityAt(double t) {
            return CannonBallistics.velocityAt(velocity, gravity, t);
        }
    }

    /** 着弾の閃光。{@code kind} は着弾の種類、{@code shellKind} は弾の種類。 */
    public record Flash(Vec3 pos, long startGameTime, int kind, int part, int shellKind) {
    }

    private static final Map<Integer, Shell> SHELLS = new LinkedHashMap<>();
    private static final List<Flash> FLASHES = new ArrayList<>();

    /**
     * 飛んでいる弾のいまの位置。知らない弾(もう着弾した・まだ届いていない)なら {@code null}。
     *
     * <p>機関砲台({@code block/GunTurretBlockEntity})がこれを使って、サーバーと同じ弾を同じ式で狙う ——
     * 砲身の向きを同期しなくても、両側で同じところを向く。
     */
    @Nullable
    public static Vec3 position(int id, long gameTime, float partialTick) {
        Shell shell = SHELLS.get(id);
        if (shell == null) {
            return null;
        }
        double age = (gameTime - shell.startGameTime()) + partialTick;
        return age < 0.0D ? shell.origin() : shell.positionAt(age);
    }

    public static void onFired(ShellFiredPayload payload) {
        SHELLS.put(payload.id(), new Shell(payload.id(), payload.kind(), payload.origin(), payload.velocity(),
                payload.gravity(), payload.startGameTime()));
    }

    public static void onImpact(ShellImpactPayload payload) {
        // 弾の種類は「飛んでいた弾」から引く(着弾のパケットには載せていない)。
        Shell shell = SHELLS.remove(payload.id());
        int shellKind = shell == null ? ShellTracker.KIND_CANNON : shell.kind();
        if (payload.kind() == ShellTracker.IMPACT_EXPIRE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        FLASHES.add(new Flash(payload.pos(), minecraft.level.getGameTime(), payload.kind(), payload.part(), shellKind));

        // 音: ボスへの命中は遠く(1000 ブロック)なので位置付きの音は届かない → 非位置の鈍い音。
        // 地形・生物への着弾は近ければサーバーの爆発音がそのまま届くので、遠いときだけ補う。
        if (payload.kind() == ShellTracker.IMPACT_BOSS) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.GENERIC_EXPLODE.value(), 0.45F, 0.9F));
        } else if (minecraft.player != null && minecraft.player.position().distanceTo(payload.pos()) > FAR_SOUND_DISTANCE) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.GENERIC_EXPLODE.value(), 0.6F, 0.35F));
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            clear();
            return;
        }
        if (SHELLS.isEmpty() && FLASHES.isEmpty()) {
            return;
        }
        long now = minecraft.level.getGameTime();
        int lifetime = OvergrownVegetationConfig.COMMON.cannonShellLifetimeTicks.get() + 60;
        Iterator<Shell> shells = SHELLS.values().iterator();
        while (shells.hasNext()) {
            if (now - shells.next().startGameTime() > lifetime) {
                shells.remove();
            }
        }
        Iterator<Flash> flashes = FLASHES.iterator();
        while (flashes.hasNext()) {
            if (now - flashes.next().startGameTime() > FLASH_TICKS) {
                flashes.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    public static void clear() {
        SHELLS.clear();
        FLASHES.clear();
    }

    public static Collection<Shell> shells() {
        return SHELLS.values();
    }

    public static List<Flash> flashes() {
        return FLASHES;
    }

    private ClientShells() {
    }
}
