package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.EggPreviewPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * スポーンエッグを「卵の絵」ではなく、そのスポーンエッグが出す Mob 本体の立体モデルで描画する
 * {@link BlockEntityWithoutLevelRenderer}(インベントリ/手持ち/額縁/落ちているアイテムの全てで使われる)。
 *
 * <p>{@code MeganeuraPreyLayer} と同じで、描画用の Mob を種類ごとに 1 体だけ作って使い回す
 * (ワールドには追加しないので AI もティックも動かない)。GeckoLib のアニメーションはワールドの
 * ゲーム時間で進むため待機アニメーションはそのまま再生され、バニラ骨格のモデル(過繁茂したニワトリ)も
 * {@code tickCount} をゲーム時間に合わせることで動く。
 *
 * <p>枠に収める大きさは既定で当たり判定の最大辺だが、尾や脚が当たり判定からはみ出している
 * モデルはそれだと枠からあふれる。その場合は {@code mobs.json} の {@code eggFit} に
 * <b>モデルの見た目の最大辺(ブロック)</b>を入れる — 値は {@code tools/anim-preview}
 * (モーションビューア)の「モデルの大きさ」表示から取れる。
 *
 * <p>アイテムモデル JSON は {@code builtin/entity}(= カスタムレンダラー扱い)にしておく必要がある。
 * インベントリ/手持ちでの向き・大きさの微調整はその {@code display} 側で行う
 * ({@code models/item/_mob_spawn_egg_entity.json} が全スポーンエッグ共通の親)。
 *
 * <p>登録は {@link OvergrownVegetationClient#registerClientExtensions} で、
 * {@code ModItems} に登録された {@link SpawnEggItem} 全てに対してまとめて行う。
 */
public class SpawnEggEntityItemRenderer extends BlockEntityWithoutLevelRenderer {

    /**
     * アイテム枠(1 ブロック分)のうち Mob が占める割合。1.0 でちょうど枠いっぱい。
     *
     * <p>0.7 → 0.9 → <b>1.2</b> と上げてきた(1.2 はユーザー指定 2026-09-15)。
     * 1 を超えるので Mob は枠から少しはみ出す — これは意図した見た目で、
     * {@code eggFit} が<b>外接箱の差し渡し</b>(= 実際のシルエットより一回り大きい)なため、
     * 1.2 でも隣のスロットに食い込むほどにはならない。
     *
     * <p>⚠ ここを動かすと<b>全スポーンエッグ</b>の絵の大きさが一斉に変わる。個別の Mob だけ
     * 直したいときは {@code mobs.json} の {@code eggFit} のほう({@link #fitSizeOf})。
     */
    private static final float SLOT_FILL = 1.2F;

    /** 正面顔だと平面的に見えるので、少し斜めを向かせる(度)。 */
    private static final float PREVIEW_YAW = -25.0F;

    /** 描画用 Mob が更にスポーンエッグを持っていた場合に、描画が無限に入れ子になるのを防ぐ保険。 */
    private static boolean rendering;

    /**
     * スポーンエッグの種類ごとの描画用 Mob。値が {@code null} のエントリは「作れなかった種類」の記録で、
     * 毎フレーム作り直そうとしないための負のキャッシュを兼ねる。
     */
    private final Map<EntityType<?>, Entity> previews = new HashMap<>();

    /** {@link #previews} を作ったワールド。変わったら作り直す(Mob は生成時のワールドを掴んだままなので)。 */
    private Level cachedLevel;

    /**
     * 作った描画器(ふつうは 1 つ)。切断・次元の移動のときに {@link ForgetLevel} が中身を空にする(2026-10-10)。
     * 弱参照なので、描画器が捨てられればここからも消える。触るのはメインスレッドだけ。
     */
    private static final Set<SpawnEggEntityItemRenderer> INSTANCES = Collections.newSetFromMap(new WeakHashMap<>());

    public SpawnEggEntityItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels());
        INSTANCES.add(this);
    }

    /** 描画用の Mob を全部手放す(次に描くときに、そのときのワールドで作り直す)。メインスレッドから呼ぶこと。 */
    private static void forgetAll() {
        for (SpawnEggEntityItemRenderer renderer : INSTANCES) {
            renderer.previews.clear();
            renderer.cachedLevel = null;
        }
    }

    /**
     * 捨てられる ClientLevel を描画用の Mob ごと手放す(ゲームバス・クライアント、2026-10-10)。今までは次にスポーンエッグを
     * 描くまで、前のワールド(とそのチャンク・エンティティ)が丸ごと残っていた。統合サーバーのワールドの Unload は
     * サーバーのスレッドから来るので、クライアントのワールドのときだけ空にする。
     */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
    public static final class ForgetLevel {

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            forgetAll();
        }

        @SubscribeEvent
        public static void onLevelUnload(LevelEvent.Unload event) {
            if (event.getLevel().isClientSide()) {
                forgetAll();
            }
        }

        private ForgetLevel() {
        }
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack,
                             MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (rendering || !(stack.getItem() instanceof SpawnEggItem egg)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        EntityType<?> type = egg.getType(stack);
        Entity preview = type == null ? null : resolvePreview(type);
        // カメラはワールド描画中に設定される。まだ 1 フレームも描いていない間は何も描画しない。
        if (preview == null || dispatcher.camera == null) {
            return;
        }

        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        // バニラ骨格のモデルは tickCount からアニメーションの位相を決めるので、ワールドの時間に合わせる。
        preview.tickCount = minecraft.level == null ? 0 : (int) minecraft.level.getGameTime();

        // 当たり判定は SCALE 属性で伸び縮みするが、描画専用のこの Mob は一度もティックされないので
        // refreshDimensions() が走らず、getBbWidth/Height は<b>素の登録値のまま</b>返ってくる。
        // 絵の大きさも中心合わせも「実際に描かれるモデル」に合わせたいので、ここで自分で掛ける
        // (GeoEntityRenderer 側はちゃんと getScale() を掛けて描く)。
        float nativeScale = preview instanceof LivingEntity living ? living.getScale() : 1.0F;
        EntityDimensions dimensions = type.getDimensions();
        float scaledHeight = dimensions.height() * nativeScale;

        // アイテムモデル空間は 1 ブロック(0..1)。中央に置いて、最大辺が枠に収まるように縮める。
        // 既定は当たり判定の最大辺だが、尾や脚が当たり判定からはみ出しているモデル(アルボラなど)は
        // それだと枠からあふれるので、mobs.json の eggFit(画面上の見かけの差し渡し)があればそちらを使う。
        float hitbox = Math.max(dimensions.width() * nativeScale, scaledHeight);
        float extent = Math.max(fitSizeOf(type, hitbox), 0.05F);
        float scale = SLOT_FILL / extent;

        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.scale(scale, scale, scale);
        // Mob は足元が原点なので、体の中心が枠の中心に来るよう半分だけ下げる。
        poseStack.translate(0.0F, -scaledHeight * 0.5F, 0.0F);

        dispatcher.setRenderShadow(false);
        rendering = true;
        try {
            dispatcher.render(preview, 0.0D, 0.0D, 0.0D, 0.0F, partialTick, poseStack, buffer, packedLight);
        } catch (Exception e) {
            // 描画のためだけに作った Mob なので、相手のレンダラーが想定外の状態で落ちても
            // インベントリごと表示不能にはしない。以後この種類は卵を描画しない(何も出さない)。
            this.previews.put(type, null);
        } finally {
            rendering = false;
            dispatcher.setRenderShadow(true);
            poseStack.popPose();
        }
    }

    /**
     * {@code type} をアイテム枠に収めるときの基準の大きさ(ブロック)。
     * {@code mobs.json} の {@code eggFit} があればそれ、無ければ当たり判定の最大辺
     * ({@code fallback})。このMODのエンティティ以外は常に {@code fallback}。
     *
     * <h2>{@code eggFit} に入れるべき値(2026-09-15 に全 Mob 測り直した)</h2>
     * <p>モデルの<b>素の最大辺</b>ではなく、<b>画面上での見かけの差し渡し</b>を入れる。Mob は
     * {@link #PREVIEW_YAW}(25°)と GUI の 15° 傾きから<b>斜めに</b>見えるので、
     * 奥行き方向は画面上では {@code sin25° ≒ 0.42} にしか広がらない。
     * 首から尾まで 13 ブロックあるフィロセンスは、奥行きを最大辺として収めると
     * <b>実際には枠の半分ほどしか使わない</b>ことになる。
     *
     * <p>枠の中心は<b>原点(足元)から {@code 当たり判定の高さ × SCALE ÷ 2} だけ上</b>に固定なので、
     * 必要な差し渡しは「その中心からいちばん遠い点までの距離 × 2」:
     * <pre>
     *   モデルの全頂点 p(ブロック、SCALE 込み)について
     *     画面X = p を Y 軸まわりに (180 - PREVIEW_YAW)° 回した x
     *     画面Y = (回したあとの y - 当たり判定の高さ/2)・cos15° - 回したあとの z・sin15°
     *   eggFit = 2 × max(|画面X|, |画面Y|)
     * </pre>
     * <b>待機アニメーションを一周ぶんサンプリングした最大値</b>で採ること — 静止ポーズだけ見ると
     * 首や翼が動くモデル(フィロセンス・スポロフィル)で 2 割ほど足りなくなる。
     * スポーンエッグの絵に出るのは各 Mob が<b>何もしていないとき</b>のアニメーション
     * (ヴェロフィルだけは地上の {@code ground_idle} ではなく飛行の {@code glide})。
     *
     * <p>⚠ {@code eggFit} 未設定だと当たり判定の最大辺(× SCALE)で代用するが、これは
     * <b>ほぼ全ての Mob で小さすぎる</b>(尾・脚・翼が当たり判定の外にあるため)。新しい Mob を
     * 足したら必ず実測して入れること。素の 幅×高さ×奥行き は {@code tools/anim-preview}
     * (モーションビューア)の「モデルの大きさ」表示からも読める。
     */
    public static float fitSizeOf(EntityType<?> type, float fallback) {
        ResourceLocation id = EntityType.getKey(type);
        if (!id.getNamespace().equals(OvergrownVegetation.MODID)) {
            return fallback;
        }
        return DesignData.mobs().forId(id.getPath()).eggFit(fallback);
    }

    /**
     * {@code type} の描画用インスタンスを返す(なければ作ってキャッシュする)。
     * 作れない種類やワールドに入っていない間は {@code null}。
     */
    @Nullable
    private Entity resolvePreview(EntityType<?> type) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        if (this.cachedLevel != level) {
            this.cachedLevel = level;
            this.previews.clear();
        }
        if (this.previews.containsKey(type)) {
            return this.previews.get(type);
        }

        Entity preview = null;
        try {
            preview = type.create(level);
            if (preview != null) {
                preview.setPos(0.0D, 0.0D, 0.0D);
                preview.setYRot(PREVIEW_YAW);
                preview.setXRot(0.0F);
                preview.setOldPosAndRot();
                if (preview instanceof LivingEntity living) {
                    // 体と頭の向きは別管理なので、正面向きに戻らないよう両方揃えておく。
                    living.yBodyRot = PREVIEW_YAW;
                    living.yBodyRotO = PREVIEW_YAW;
                    living.yHeadRot = PREVIEW_YAW;
                    living.yHeadRotO = PREVIEW_YAW;
                }
                // tick の中で描く形を作る種(フォディムネスの体)は、ここで決まった姿勢を付けてもらう(この個体は tick しない)
                if (preview instanceof EggPreviewPose pose) {
                    pose.prepareEggPreview();
                }
            }
        } catch (Exception e) {
            // 生成にサーバー側の情報が要るなど、クライアントだけでは作れない種類は表示しない。
            preview = null;
        }
        this.previews.put(type, preview);
        return preview;
    }
}
