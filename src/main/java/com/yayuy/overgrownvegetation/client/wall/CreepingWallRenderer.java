package com.yayuy.overgrownvegetation.client.wall;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 蠢く壁の<b>描く側</b>(2026-11-16 試作)。本当の見た目(根と蔦の濁流・顔や手足・胞子嚢・光る筋)は後で Fable のセッションが作る。
 * そのときは、この形の実装を 1 つ書いて {@link CreepingWallRendering#renderer} を差し替えればよい。
 * データ(どの升のどの面を、どこまで進めて描くか)は {@link ClientCreepingWall.View#collectFaces} が出す。
 */
public interface CreepingWallRenderer {

    /**
     * 1 フレームぶん描く。{@link RenderLevelStageEvent.Stage#AFTER_ENTITIES} で呼ばれる(モデルビュー行列にカメラの回転が入った状態。
     * 位置はカメラからの差で描く)。
     *
     * @param time ゲーム時刻 + 部分 tick
     */
    void render(RenderLevelStageEvent event, ClientLevel level, Camera camera, Iterable<ClientCreepingWall.View> views, float time);
}
