#version 150
// Overgrown Vegetation: the fog of the heat forest (the overgrown Nether; client/atmosphere/heat/HeatFxFog.java holds the explanation,
// 11-10 heat forest shader stage 1). Keep this file ASCII-only.
//
// Distant Horizons switches the vanilla fog off at the end of FogRenderer.setupFog (its API switch does not bring it back in the Nether),
// so the terrain takes the heat forest fog from these uniforms instead of the vanilla fog values. The result is mixed over the usual
// fogged colour by the amount (how much the camera is in the heat forest), so at amount 1 only this fog is left, whatever the vanilla fog did.
//
// OvHeatFog = (amount 0 to 1, 0 = off; fog start; fog end (blocks); 1 = measure the horizontal distance only (the high glowing ceiling stays clear))
// OvHeatFogColor = (fog colour rgb, -)
uniform vec4 OvHeatFog;
uniform vec4 OvHeatFogColor;

// fogged = the colour after the usual fogs, raw = the colour before them, pos = the camera-relative position.
vec3 ovHeatFog(vec3 fogged, vec3 raw, vec3 pos) {
    if (OvHeatFog.x <= 0.0) {
        return fogged;
    }
    float d = OvHeatFog.w > 0.5 ? length(pos.xz) : length(pos);
    float f = clamp((d - OvHeatFog.y) / max(OvHeatFog.z - OvHeatFog.y, 1.0), 0.0, 1.0);
    return mix(fogged, mix(raw, OvHeatFogColor.rgb, f), OvHeatFog.x);
}
