package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.SiegeCannonBlockEntity;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.SiegeCannonSeatEntity;
import com.yayuy.overgrownvegetation.network.CannonControlPayload;
import com.yayuy.overgrownvegetation.siege.CannonBallistics;
import com.yayuy.overgrownvegetation.siege.SiegeCannonGeometry;
import com.yayuy.overgrownvegetation.siege.TitanicaKinematics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Optional;

/**
 * 砲台に乗っているあいだのクライアント側 — <b>照準器</b>。
 *
 * <h2>3 つの印</h2>
 * <p>砲塔は視線へ<b>ゆっくり</b>追従する({@code cannonTraverseSpeed} / {@code cannonElevationSpeed})ので、
 * 「見ている方向」と「砲身が向いている方向」は別物になる。そのため鏡筒には 3 つの印が出る:
 * <ul>
 *   <li><b>十字線</b>(画面中央) — いま見ている方向 = 砲塔への<b>指示</b>。</li>
 *   <li><b>丸</b> — 砲身が実際に向いている方向。指示を追って遅れてやってくる。<b>撃つと弾はここへ飛ぶ</b>。</li>
 *   <li><b>緑の四角</b> — ボスへ当てるために狙うべき点。<b>測距を終えるまで出ない</b>。</li>
 * </ul>
 * 丸を緑の四角に重ねて撃つ、というのが基本の流れ。
 *
 * <h2>測距</h2>
 * <p>射撃ガイド(緑の四角と必要仰角)は、<b>ボスを視野に捉えてホイールクリック</b>し
 * {@code cannonRangingTicks}(既定 10 秒)待つと出る。測っているあいだボスを外すと中断。
 * 結果は {@code cannonRangingValidTicks}(既定 60 秒)で切れるので測り直す。
 *
 * <p><b>「捉えている」の判定は体のどこでもよい</b>({@link #bossInView})。ボスは空を 33 度ほど
 * 横切る大きさなので、当たり判定パーツ 28 個それぞれの見かけの大きさに
 * {@code cannonRangingConeDegrees} の余裕を足した範囲で見る。
 *
 * <p><b>ホイールクリックはマウスのボタンそのもので拾う</b>({@link #onMouseButton})。
 * {@code isPickBlock()} のイベントは十字線がブロックに当たっているときしか飛んでこないので、
 * 空を狙う砲では使えない。
 *
 * <p><b>被弾すると測距はご破算になる</b>({@link #checkHit})。測っている途中なら中断、
 * 測り終えた諸元なら破棄。撃たれながら悠長に測っていられては困るので。
 *
 * <h2>そのほか</h2>
 * <p>視野を絞って鏡筒を描き、手とバニラの照準点は消す。視線のピッチは砲身の可動域でクランプする。
 * 攻撃キーで発射、ホイールで装薬({@link CannonControlPayload})。照準そのものはサーバーへ送らない
 * — 砲の向きは騎乗者の視線から両側が同じ式で出す({@link SiegeCannonBlockEntity#advanceAim})。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CannonSight {

    private static final ResourceLocation SCOPE = OvergrownVegetation.id("textures/gui/cannon_scope.png");

    /** 鏡筒の内径 / オーバーレイ画像の一辺(テクスチャの作りと合わせる)。 */
    private static final double APERTURE_RATIO = 112.0D / 256.0D;
    /** 砲身の向きと解がこれだけ近ければ「よし」と出す(度)。 */
    private static final double ON_TARGET_DEGREES = 0.2D;
    /** 「測距完了」の大きな表示を出しておく時間(tick)。 */
    private static final int RANGED_BANNER_TICKS = 60;

    private static final int RETICLE = 0xFFBBD4BC;
    private static final int RETICLE_DIM = 0x66BBD4BC;
    private static final int BORE = 0xFFF2F0E4;
    private static final int MARKER = 0xFF7CF08C;
    private static final int MARKER_OFFSCREEN = 0xFFF0C060;
    private static final int TEXT = 0xFFD7E4D2;
    private static final int TEXT_WARN = 0xFFF0D080;
    private static final int TEXT_BAD = 0xFFF08C8C;
    private static final int GAUGE_BG = 0x80202418;

    /** 直近のフレームの視野角(度)。印を画面座標へ落とすのに使う。 */
    private static double fov = 70.0D;

    // --- 測距の状態(乗っている砲台ごと) ---
    @Nullable
    private static BlockPos rangingCannon;
    /** 測距を始めたゲーム内時刻。測っていなければ {@link Long#MIN_VALUE}。 */
    private static long rangingStart = Long.MIN_VALUE;
    /** 測距が終わったゲーム内時刻。結果が無ければ {@link Long#MIN_VALUE}。 */
    private static long rangedAt = Long.MIN_VALUE;
    /**
     * 前の tick の体力(+ 吸収)。これが減ったら「被弾した」とみなして測距をやり直させる
     * ({@link #checkHit})。乗っていないあいだは {@link Float#NaN}。
     */
    private static float lastHealth = Float.NaN;

    /** いま乗っている砲台(乗っていなければ null)。 */
    @Nullable
    public static SiegeCannonBlockEntity ridingCannon() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return null;
        }
        return player.getVehicle() instanceof SiegeCannonSeatEntity seat ? seat.cannon() : null;
    }

    /** 照準器を覗いている(= 乗っていて一人称)か。 */
    private static boolean scoping() {
        return ridingCannon() != null && Minecraft.getInstance().options.getCameraType().isFirstPerson();
    }

    // ------------------------------------------------------------------ 視線 = 砲塔への指示

    /**
     * 砲身の可動域を超えて上下を向けないようにする(視線が砲塔への指示なので)。
     * ついでに測距の進行も見る。
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        SiegeCannonBlockEntity cannon = ridingCannon();
        LocalPlayer player = minecraft.player;
        if (cannon == null || player == null || minecraft.level == null) {
            reset();
            return;
        }
        if (rangingCannon != null && !rangingCannon.equals(cannon.getBlockPos())) {
            reset();
        }
        rangingCannon = cannon.getBlockPos();
        checkHit(player);

        float min = (float) -SiegeCannonGeometry.maxElevation();
        float max = (float) -SiegeCannonGeometry.minElevation();
        player.setXRot(Mth.clamp(player.getXRot(), min, max));
        player.xRotO = Mth.clamp(player.xRotO, min, max);

        tickRanging(minecraft, player, cannon);
    }

    /**
     * <b>被弾したら測距がご破算になる</b>。撃たれながら悠長に測っていられては困るし、
     * 揺さぶられた照準器の目盛りが生きているのもおかしいので、測っている途中なら中断、
     * 測り終えていた諸元なら破棄して、測り直させる。
     *
     * <p>判定は<b>体力(+ 吸収)が前の tick より減ったか</b>だけ。ダメージのイベントは論理サーバー側でしか
     * 飛ばないが、体力は {@code SynchedEntityData} でクライアントへ届くので、これならパケットを足さずに済む。
     * 攻撃の出どころは問わない(ボスのビーム・乱射・胞子爆弾のどれでも、落下でも同じ)。
     */
    private static void checkHit(LocalPlayer player) {
        float health = player.getHealth() + player.getAbsorptionAmount();
        if (Float.isNaN(lastHealth)) {
            lastHealth = health;
            return;
        }
        boolean hurt = health < lastHealth - 1.0E-3F;
        lastHealth = health;
        if (!hurt) {
            return;
        }
        boolean had = rangingStart != Long.MIN_VALUE || rangedAt != Long.MIN_VALUE;
        rangingStart = Long.MIN_VALUE;
        rangedAt = Long.MIN_VALUE;
        if (had) {
            notify(player, "gui.overgrown_vegetation.cannon.ranging_shaken");
        }
    }

    /** 測距の進行。ボスを視野から外したら中断、規定時間まで保てば完了。 */
    private static void tickRanging(Minecraft minecraft, LocalPlayer player, SiegeCannonBlockEntity cannon) {
        if (rangingStart == Long.MIN_VALUE || minecraft.level == null) {
            return;
        }
        long now = minecraft.level.getGameTime();
        if (!ClientSiegeBoss.isActive() || !bossInView(player, cannon, 1.0F)) {
            rangingStart = Long.MIN_VALUE;
            notify(player, "gui.overgrown_vegetation.cannon.ranging_lost");
            return;
        }
        if (now - rangingStart >= OvergrownVegetationConfig.COMMON.cannonRangingTicks.get()) {
            rangingStart = Long.MIN_VALUE;
            rangedAt = now;
            notify(player, "gui.overgrown_vegetation.cannon.ranging_done");
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.6F, 0.6F));
        }
    }

    /**
     * ボスが視野(ホイールクリックで測距できる角度)に入っているか。
     *
     * <p><b>体のどこを見ていてもよい。</b>ボスは全長 615 ブロックあり、1025 ブロック離れていても
     * 空を 33 度ほど横切る。狙点(胴の中心)1 点だけで見ていたころは、頭や尾に十字線を合わせると
     * 胴の中心まで 15 度以上離れていて「照準を合わせているのに測距が始まらない」状態になっていた。
     * そこで当たり判定パーツ 28 個(頭・首・胴・尾)それぞれについて、<b>その部位の見かけの大きさ +
     * {@code cannonRangingConeDegrees} の余裕</b>に十字線が入っているかを見る。
     */
    private static boolean bossInView(LocalPlayer player, SiegeCannonBlockEntity cannon, float partialTick) {
        if (!ClientSiegeBoss.isActive()) {
            return false;
        }
        Vec3 from = cannon.muzzle(cannon.aimElevation(partialTick), cannon.aimAzimuth(partialTick));
        Vec3 look = CannonBallistics.direction(-player.getViewXRot(partialTick),
                SiegeCannonGeometry.azimuthFromYaw(player.getViewYRot(partialTick)));
        double margin = Math.toRadians(OvergrownVegetationConfig.COMMON.cannonRangingConeDegrees.get());

        Vec3[] centers = TitanicaKinematics.partCenters(ClientSiegeBoss.position(partialTick),
                ClientSiegeBoss.yaw(partialTick), ClientSiegeBoss.pitch(partialTick), ClientSiegeBoss.scale(),
                ClientSiegeBoss.neckYaw(partialTick), ClientSiegeBoss.neckPitch(partialTick));
        double[] sizes = TitanicaKinematics.partSizes(ClientSiegeBoss.scale());
        for (int i = 0; i < centers.length; i++) {
            Vec3 offset = centers[i].subtract(from);
            double distance = offset.length();
            if (distance < 1.0E-3D) {
                return true;
            }
            // その部位の見かけの半径(ラジアン)+ 余裕。
            double angle = Math.acos(Mth.clamp(offset.scale(1.0D / distance).dot(look), -1.0D, 1.0D));
            if (angle <= margin + Math.atan(sizes[i] * 0.5D / distance)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static Vec3 bossAimPoint(float partialTick) {
        if (!ClientSiegeBoss.isActive()) {
            return null;
        }
        return TitanicaKinematics.bodyPoint(ClientSiegeBoss.position(partialTick), ClientSiegeBoss.yaw(partialTick),
                ClientSiegeBoss.pitch(partialTick), ClientSiegeBoss.scale(), CannonBallistics.AIM_Z_UNITS);
    }

    /** 測距の結果がまだ生きているか。 */
    private static boolean ranged(long now) {
        return rangedAt != Long.MIN_VALUE
                && now - rangedAt <= OvergrownVegetationConfig.COMMON.cannonRangingValidTicks.get();
    }

    private static void reset() {
        rangingCannon = null;
        rangingStart = Long.MIN_VALUE;
        rangedAt = Long.MIN_VALUE;
        lastHealth = Float.NaN;
    }

    private static void notify(LocalPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }

    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (scoping()) {
            event.setFOV(event.getFOV() * OvergrownVegetationConfig.COMMON.cannonScopeZoom.get());
        }
        fov = event.getFOV();
    }

    /** 砲を操作しているあいだは手も持ち物も見えない。 */
    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (scoping()) {
            event.setCanceled(true);
        }
    }

    /** バニラの照準点は消して、鏡筒の十字線に置き換える。 */
    @SubscribeEvent
    public static void onGuiLayerPre(RenderGuiLayerEvent.Pre event) {
        if (scoping() && VanillaGuiLayers.CROSSHAIR.equals(event.getName())) {
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ 操作

    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft minecraft = Minecraft.getInstance();
        SiegeCannonBlockEntity cannon = ridingCannon();
        LocalPlayer player = minecraft.player;
        if (cannon == null || player == null || minecraft.level == null) {
            return;
        }
        if (event.isAttack()) {
            event.setSwingHand(false);
            event.setCanceled(true);
            PacketDistributor.sendToServer(new CannonControlPayload(CannonControlPayload.ACTION_FIRE, 0));
        } else if (event.isUseItem()) {
            // 乗ったままブロックを置いたりしないように。
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    /**
     * <b>ホイールクリック = 測距の開始 / 中断。</b>
     *
     * <p class="warn">ここをマウスのボタンそのもので拾っているのには理由がある。
     * {@code InputEvent.InteractionKeyMappingTriggered} の {@code isPickBlock()} は、バニラの
     * {@code Minecraft.pickBlock()} の<b>中</b>から呼ばれる — つまり
     * <b>十字線がブロックか生物に当たっているときにしか飛んでこない</b>
     * ({@code hitResult.getType() != MISS} が前提)。砲で狙うのは 1000 ブロック先の空なので
     * {@code hitResult} は常に MISS で、あのイベントは一度も発火しなかった(実際にそうなった)。
     * {@code InputEvent.MouseButton.Pre} は当たり判定と無関係に必ず飛んでくるうえ、
     * ここで打ち切ればキー割り当て自体が立たないので、バニラのブロック複製も起きない。
     *
     * <p>キーボードへ割り当て直している場合のために {@link #onKey} でも同じことをする。
     */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getAction() != InputConstants.PRESS || minecraft.screen != null) {
            return;
        }
        if (minecraft.options.keyPickItem.matchesMouse(event.getButton()) && toggleRanging()) {
            event.setCanceled(true);
        }
    }

    /** ホイールクリックをキーボードへ割り当て直している場合。 */
    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getAction() != InputConstants.PRESS || minecraft.screen != null) {
            return;
        }
        if (minecraft.options.keyPickItem.matches(event.getKey(), event.getScanCode())) {
            toggleRanging();
        }
    }

    /**
     * 測距を始める / 中止する。砲に乗っていなければ何もしない(false を返して入力を通す)。
     *
     * @return この入力を測距に使ったか
     */
    private static boolean toggleRanging() {
        Minecraft minecraft = Minecraft.getInstance();
        SiegeCannonBlockEntity cannon = ridingCannon();
        LocalPlayer player = minecraft.player;
        if (cannon == null || player == null || minecraft.level == null) {
            return false;
        }
        if (rangingStart != Long.MIN_VALUE) {
            rangingStart = Long.MIN_VALUE;
            notify(player, "gui.overgrown_vegetation.cannon.ranging_cancel");
        } else if (!ClientSiegeBoss.isActive()) {
            notify(player, "gui.overgrown_vegetation.cannon.target_none");
        } else if (!bossInView(player, cannon, 1.0F)) {
            notify(player, "gui.overgrown_vegetation.cannon.ranging_aim");
        } else {
            rangingStart = minecraft.level.getGameTime();
            notify(player, "gui.overgrown_vegetation.cannon.ranging_start");
        }
        return true;
    }

    /** ホイールで装薬(1 発に使う火薬の数 = 初速)。 */
    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (ridingCannon() == null) {
            return;
        }
        int delta = event.getScrollDeltaY() > 0.0D ? 1 : (event.getScrollDeltaY() < 0.0D ? -1 : 0);
        if (delta == 0) {
            return;
        }
        event.setCanceled(true);
        PacketDistributor.sendToServer(new CannonControlPayload(CannonControlPayload.ACTION_CHARGE, delta));
    }

    // ------------------------------------------------------------------ 描画

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        SiegeCannonBlockEntity cannon = ridingCannon();
        Minecraft minecraft = Minecraft.getInstance();
        if (cannon == null || minecraft.player == null || minecraft.level == null
                || !minecraft.options.getCameraType().isFirstPerson()) {
            return;
        }
        render(event.getGuiGraphics(), minecraft, cannon,
                event.getPartialTick().getGameTimeDeltaPartialTick(true));
    }

    private static void render(GuiGraphics graphics, Minecraft minecraft, SiegeCannonBlockEntity cannon, float partialTick) {
        LocalPlayer player = minecraft.player;
        Font font = minecraft.font;
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int side = Math.min(width, height);
        int left = (width - side) / 2;
        int top = (height - side) / 2;
        int centerX = width / 2;
        int centerY = height / 2;
        double radius = side * APERTURE_RATIO;
        long now = minecraft.level.getGameTime();

        // 鏡筒(円い窓 + 外側の黒)。バニラの望遠鏡と同じ描き方。
        RenderSystem.enableBlend();
        graphics.blit(SCOPE, left, top, -90, 0.0F, 0.0F, side, side, side, side);
        RenderSystem.disableBlend();
        graphics.fill(RenderType.guiOverlay(), 0, top + side, width, height, -90, 0xFF000000);
        graphics.fill(RenderType.guiOverlay(), 0, 0, width, top, -90, 0xFF000000);
        graphics.fill(RenderType.guiOverlay(), 0, top, left, top + side, -90, 0xFF000000);
        graphics.fill(RenderType.guiOverlay(), left + side, top, width, top + side, -90, 0xFF000000);

        drawCrosshair(graphics, centerX, centerY);
        drawElevationLadder(graphics, font, cannon, centerX, centerY, radius, partialTick);

        // カメラ基準の視線(= 砲塔への指示)。印はすべてこれを基準に画面へ落とす。
        double viewAzimuth = SiegeCannonGeometry.azimuthFromYaw(player.getViewYRot(partialTick));
        double viewElevation = -player.getViewXRot(partialTick);
        double focal = (height / 2.0D) / Math.tan(Math.toRadians(fov) / 2.0D);

        // 砲身が実際に向いている方向。弾はここへ飛ぶ。
        double boreAzimuth = cannon.aimAzimuth(partialTick);
        double boreElevation = cannon.aimElevation(partialTick);
        drawBore(graphics, CannonBallistics.direction(boreElevation, boreAzimuth),
                viewAzimuth, viewElevation, centerX, centerY, focal, radius);

        Optional<CannonBallistics.Solution> solution = ranged(now) ? solve(cannon, partialTick) : Optional.empty();
        boolean onTarget = false;
        if (solution.isPresent() && OvergrownVegetationConfig.COMMON.cannonAutoAim.get()) {
            CannonBallistics.Solution s = solution.get();
            drawMarker(graphics, CannonBallistics.direction(s.elevationDeg(), s.azimuthDeg()),
                    viewAzimuth, viewElevation, centerX, centerY, focal, radius, MARKER, MARKER_OFFSCREEN);
            onTarget = Math.abs(CannonBallistics.azimuthDelta(boreAzimuth, s.azimuthDeg())) < ON_TARGET_DEGREES
                    && Math.abs(s.elevationDeg() - boreElevation) < ON_TARGET_DEGREES;
        }
        drawRangingBanner(graphics, font, centerX, centerY, radius, now);
        drawStatus(graphics, font, cannon, solution, onTarget, centerX, top, side, radius, now);
    }

    /**
     * 十字線の上に出す<b>測距の状態</b>。測っているあいだは残り時間つきで「測距中」、
     * 終わった直後は「測距完了」。鏡筒の下の行だけだと気づきにくいので、目線の高さにも出す。
     */
    private static void drawRangingBanner(GuiGraphics graphics, Font font, int cx, int cy, double radius, long now) {
        int total = OvergrownVegetationConfig.COMMON.cannonRangingTicks.get();
        int y = cy - (int) (radius * 0.45D);
        if (rangingStart != Long.MIN_VALUE) {
            int done = (int) Mth.clamp(now - rangingStart, 0L, total);
            Component label = Component.translatable("gui.overgrown_vegetation.cannon.ranging_banner",
                    fmt1((total - done) / 20.0D));
            graphics.drawCenteredString(font, label, cx, y, TEXT_WARN);
            // 進み具合の帯。十字線のすぐ上なので、狙ったまま進捗が見える。
            int gw = (int) (radius * 0.9D);
            int gx = cx - gw / 2;
            graphics.fill(gx, y + 11, gx + gw, y + 14, GAUGE_BG);
            graphics.fill(gx, y + 11, gx + (total > 0 ? gw * done / total : gw), y + 14, TEXT_WARN);
        } else if (rangedAt != Long.MIN_VALUE && now - rangedAt < RANGED_BANNER_TICKS) {
            graphics.drawCenteredString(font, Component.translatable("gui.overgrown_vegetation.cannon.ranging_done"),
                    cx, y, MARKER);
        }
    }

    /** 中央の十字線 = 見ている方向(砲塔への指示)。控えめに。 */
    private static void drawCrosshair(GuiGraphics graphics, int cx, int cy) {
        graphics.fill(cx - 22, cy, cx - 6, cy + 1, RETICLE_DIM);
        graphics.fill(cx + 6, cy, cx + 22, cy + 1, RETICLE_DIM);
        graphics.fill(cx, cy - 22, cx + 1, cy - 6, RETICLE_DIM);
        graphics.fill(cx, cy + 6, cx + 1, cy + 22, RETICLE_DIM);
    }

    /** 砲身が向いている方向の印(丸)。弾はここへ飛ぶ。 */
    private static void drawBore(GuiGraphics graphics, Vec3 bore, double viewAzimuth, double viewElevation,
                                 int cx, int cy, double focal, double radius) {
        double[] p = project(bore, viewAzimuth, viewElevation, focal, radius);
        int x = cx + (int) Math.round(p[0]);
        int y = cy + (int) Math.round(p[1]);
        // 8 角形っぽい輪(塗りつぶさない)。
        graphics.fill(x - 3, y - 6, x + 4, y - 5, BORE);
        graphics.fill(x - 3, y + 5, x + 4, y + 6, BORE);
        graphics.fill(x - 6, y - 3, x - 5, y + 4, BORE);
        graphics.fill(x + 5, y - 3, x + 6, y + 4, BORE);
        graphics.fill(x - 5, y - 5, x - 3, y - 3, BORE);
        graphics.fill(x + 3, y - 5, x + 5, y - 3, BORE);
        graphics.fill(x - 5, y + 3, x - 3, y + 5, BORE);
        graphics.fill(x + 3, y + 3, x + 5, y + 5, BORE);
    }

    /** 狙うべき点の印(四角)。鏡筒からはみ出すときは縁に貼り付けて色を変える。 */
    private static void drawMarker(GuiGraphics graphics, Vec3 target, double viewAzimuth, double viewElevation,
                                   int cx, int cy, double focal, double radius, int color, int edgeColor) {
        double[] p = project(target, viewAzimuth, viewElevation, focal, radius);
        int x = cx + (int) Math.round(p[0]);
        int y = cy + (int) Math.round(p[1]);
        int c = p[2] > 0.5D ? edgeColor : color;
        graphics.fill(x - 5, y - 5, x + 6, y - 4, c);
        graphics.fill(x - 5, y + 5, x + 6, y + 6, c);
        graphics.fill(x - 5, y - 5, x - 4, y + 6, c);
        graphics.fill(x + 5, y - 5, x + 6, y + 6, c);
    }

    /**
     * 方向ベクトルを「視線を正面とした画面座標」へ落とす。カメラの向きが分かっているので、
     * 右・上の基底に分解して透視で割るだけでよい。
     *
     * @return {@code {dx, dy, はみ出したか(1/0)}}(中心からのピクセル)
     */
    private static double[] project(Vec3 target, double viewAzimuth, double viewElevation, double focal, double radius) {
        Vec3 forward = CannonBallistics.direction(viewElevation, viewAzimuth);
        Vec3 right = CannonBallistics.direction(0.0D, viewAzimuth + 90.0D);
        Vec3 up = right.cross(forward);
        double depth = target.dot(forward);
        double dx;
        double dy;
        boolean offscreen = depth <= 0.05D;
        if (offscreen) {
            dx = target.dot(right);
            dy = -target.dot(up);
            double length = Math.sqrt(dx * dx + dy * dy);
            dx = length < 1.0E-6D ? 0.0D : dx / length * radius;
            dy = length < 1.0E-6D ? radius : dy / length * radius;
        } else {
            dx = focal * target.dot(right) / depth;
            dy = -focal * target.dot(up) / depth;
            double length = Math.sqrt(dx * dx + dy * dy);
            if (length > radius) {
                offscreen = true;
                dx = dx / length * radius;
                dy = dy / length * radius;
            }
        }
        return new double[]{dx, dy, offscreen ? 1.0D : 0.0D};
    }

    /**
     * 左側の仰角目盛り。砲身の可動域を丸ごと描き、いまの仰角に指針を出す。
     * 5 度ごとに刻み、10 度ごとに「その仰角で平地に届く距離(ブロック)」を添える。
     */
    private static void drawElevationLadder(GuiGraphics graphics, Font font, SiegeCannonBlockEntity cannon,
                                            int cx, int cy, double radius, float partialTick) {
        double min = SiegeCannonGeometry.minElevation();
        double max = SiegeCannonGeometry.maxElevation();
        if (max - min < 1.0D) {
            return;
        }
        int x = cx - (int) (radius * 0.62D);
        int half = (int) (radius * 0.55D);
        int topY = cy - half;
        int bottomY = cy + half;
        graphics.fill(x, topY, x + 1, bottomY, RETICLE_DIM);

        double speed = CannonBallistics.speedForCharge(cannon.charge());
        double gravity = OvergrownVegetationConfig.COMMON.cannonGravity.get();
        for (double deg = Math.ceil(min / 5.0D) * 5.0D; deg <= max + 0.001D; deg += 5.0D) {
            int y = (int) Mth.lerp((deg - min) / (max - min), bottomY, topY);
            boolean major = Math.abs(deg % 10.0D) < 0.001D;
            graphics.fill(x - (major ? 7 : 4), y, x, y + 1, RETICLE);
            if (major && gravity > 1.0E-6D) {
                int range = (int) (speed * speed * Math.sin(2.0D * Math.toRadians(deg)) / gravity);
                String label = (int) deg + "° " + range;
                graphics.drawString(font, label, x - 10 - font.width(label), y - 4, RETICLE_DIM, false);
            }
        }

        double elevation = cannon.aimElevation(partialTick);
        int pointerY = (int) Mth.lerp((elevation - min) / (max - min), bottomY, topY);
        graphics.fill(x + 2, pointerY - 1, x + 10, pointerY + 2, BORE);
    }

    /** 鏡筒の下の情報: 測距の状態・標的までの距離・装薬・装填。 */
    private static void drawStatus(GuiGraphics graphics, Font font, SiegeCannonBlockEntity cannon,
                                   Optional<CannonBallistics.Solution> solution, boolean onTarget,
                                   int cx, int top, int side, double radius, long now) {
        int y = top + side / 2 + (int) (radius * 0.62D);

        // 1 行目: 測距の状態か、測れているなら標的の情報。
        Component target;
        int targetColor = TEXT;
        int rangingTotal = OvergrownVegetationConfig.COMMON.cannonRangingTicks.get();
        if (rangingStart != Long.MIN_VALUE) {
            int done = (int) Mth.clamp(now - rangingStart, 0L, rangingTotal);
            target = Component.translatable("gui.overgrown_vegetation.cannon.ranging_progress",
                    fmt1((rangingTotal - done) / 20.0D));
            targetColor = TEXT_WARN;
            // 進行ゲージ。
            int gw = (int) (radius * 1.1D);
            int gx = cx - gw / 2;
            graphics.fill(gx, y + 11, gx + gw, y + 14, GAUGE_BG);
            graphics.fill(gx, y + 11, gx + (rangingTotal > 0 ? gw * done / rangingTotal : gw), y + 14, MARKER);
        } else if (!ClientSiegeBoss.isActive()) {
            target = Component.translatable("gui.overgrown_vegetation.cannon.target_none");
            targetColor = TEXT_WARN;
        } else if (!ranged(now)) {
            target = Component.translatable("gui.overgrown_vegetation.cannon.ranging_needed");
            targetColor = TEXT_WARN;
        } else if (solution.isEmpty()) {
            target = Component.translatable("gui.overgrown_vegetation.cannon.out_of_range");
            targetColor = TEXT_BAD;
        } else {
            CannonBallistics.Solution s = solution.get();
            Vec3 from = cannon.muzzle(cannon.aimElevation(1.0F), cannon.aimAzimuth(1.0F));
            int distance = (int) Math.sqrt(Mth.square(s.target().x - from.x) + Mth.square(s.target().z - from.z));
            target = Component.translatable("gui.overgrown_vegetation.cannon.scope_target",
                    distance, fmt1(s.flightTicks() / 20.0D), fmt1(s.elevationDeg()));
            targetColor = onTarget ? MARKER : TEXT;
        }
        graphics.drawCenteredString(font, target, cx, y, targetColor);

        // 2 行目: 撃てるかどうか。
        int reload = cannon.reloadRemaining(now);
        Component status;
        int statusColor = TEXT;
        if (reload > 0) {
            status = Component.translatable("gui.overgrown_vegetation.cannon.reloading", (int) Math.ceil(reload / 20.0D));
            statusColor = TEXT_WARN;
        } else if (cannon.shellsForDisplay() <= 0) {
            status = Component.translatable("gui.overgrown_vegetation.cannon.no_shell");
            statusColor = TEXT_BAD;
        } else if (cannon.powderForDisplay() < cannon.charge()) {
            status = Component.translatable("gui.overgrown_vegetation.cannon.no_powder", cannon.charge());
            statusColor = TEXT_BAD;
        } else if (onTarget) {
            status = Component.translatable("gui.overgrown_vegetation.cannon.on_target");
            statusColor = MARKER;
        } else {
            status = Component.translatable("gui.overgrown_vegetation.cannon.ready");
        }
        graphics.drawCenteredString(font, status, cx, y + 22, statusColor);

        graphics.drawCenteredString(font, Component.translatable("gui.overgrown_vegetation.cannon.scope_load",
                cannon.shellsForDisplay(), cannon.charge(),
                Math.max(1, OvergrownVegetationConfig.COMMON.cannonMaxCharge.get()), cannon.powderForDisplay()),
                cx, y + 33, TEXT);
        graphics.drawCenteredString(font, Component.translatable("gui.overgrown_vegetation.cannon.scope_hint"),
                cx, y + 44, RETICLE_DIM);
    }

    /**
     * いまの装薬でボスへ当てる解。砲口は仰角によって数ブロック動くので、
     * 「いまの砲口で解く → その解の砲口で解き直す」の 2 段で収束させる
     * (サーバーの発射も解と同じ仰角の砲口から出るので、これで一致する)。
     */
    private static Optional<CannonBallistics.Solution> solve(SiegeCannonBlockEntity cannon, float partialTick) {
        if (!ClientSiegeBoss.isActive()) {
            return Optional.empty();
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        double speed = CannonBallistics.speedForCharge(cannon.charge());
        double gravity = cfg.cannonGravity.get();
        boolean highArc = cfg.cannonAutoAimHighArc.get();
        double elapsed = ClientSiegeBoss.elapsed(partialTick);
        Vec3 from = cannon.muzzle(cannon.aimElevation(partialTick), cannon.aimAzimuth(partialTick));
        Optional<CannonBallistics.Solution> solution = CannonBallistics.lead(from, ClientSiegeBoss.motion(),
                elapsed, ClientSiegeBoss.scale(), speed, gravity, highArc);
        if (solution.isEmpty()) {
            return solution;
        }
        Vec3 corrected = cannon.muzzle(solution.get().elevationDeg(), solution.get().azimuthDeg());
        return CannonBallistics.lead(corrected, ClientSiegeBoss.motion(), elapsed, ClientSiegeBoss.scale(),
                speed, gravity, highArc);
    }

    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private CannonSight() {
    }
}
