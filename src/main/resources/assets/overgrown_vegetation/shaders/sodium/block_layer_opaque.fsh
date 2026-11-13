#version 330 core

// Overgrown Vegetation: Sodium's terrain fragment shader (sodium:blocks/block_layer_opaque.fsh, Sodium 0.8.x) with the same
// effect as our vanilla rendertype_{solid,cutout,cutout_mipped}.fsh: the canopy light (ov_light.glsl).
// client/shader/SodiumTerrain.java swaps this in for Sodium's file and expands the #ov_import lines. Keep this file ASCII-only.

#ifndef MAX_TEXTURE_LOD_BIAS
#error "MAX_TEXTURE_LOD_BIAS constant not specified"
#endif

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_material.glsl>
#ov_import <ov_light.glsl>
#ov_import <ov_wet.glsl>
#ov_import <ov_caustic.glsl>
#ov_import <ov_leaf.glsl>
#ov_import <ov_cave.glsl>
#ov_import <ov_ore.glsl>
#ov_import <ov_cloud.glsl>
#ov_import <ov_wave.glsl>
#ov_import <ov_water.glsl>
#ov_import <ov_wake.glsl>

in vec4 v_Color; // The interpolated vertex color
in vec2 v_TexCoord; // The interpolated block texture coordinates
in float v_FragDistance; // The fragment's distance from the camera

flat in uint v_Material;

in vec3 ovPos;
in vec2 ovLight;
in vec4 ovFog;
in vec4 ovLeaf;
in vec3 ovTint;
in float ovNoGlint;

uniform sampler2D u_BlockTex; // The block texture

// Sodium's linear fog, plus the spore haze of the overgrown forest: a fog start below -1000 is the mark MistView sends while the
// camera is in the forest (the same formula as our minecraft:shaders/include/fog.glsl, which Sodium does not use).
vec4 ovSodiumFog(vec4 color, float d, vec4 fogColor, float fogStart, float fogEnd) {
#ifndef USE_FOG
    return color;
#else
    if (fogStart >= -1000.0) {
        return _linearFog(color, d, fogColor, fogStart, fogEnd);
    }
    // The mark carries the spore haze k and (11-06) the aerial perspective a from s (decoded as in fog.glsl).
    float c = -fogStart - 1000.0;
    float h = floor((c + 0.5) / 10000.0);
    float k = (c - h * 10000.0) / 100000.0;
    float s = floor((h + 0.5) / 51.0);
    float a = (h - s * 51.0) / 50.0;
    s /= 20.0;
    float wallStart = fogEnd - clamp(fogEnd / 10.0, 4.0, 64.0);
    float wall = d <= wallStart ? 0.0 : (d < fogEnd ? smoothstep(wallStart, fogEnd, d) : 1.0);
    float q = clamp((d - s * fogEnd) / max(fogEnd * (1.0 - s), 1.0), 0.0, 1.0);
    float v = 1.0 - (1.0 - wall) * exp(-k * d) * (1.0 - a * q * q);
    return vec4(mix(color.rgb, fogColor.rgb, v * fogColor.a), color.a);
#endif
}

uniform vec4 u_FogColor; // The color of the shader fog
uniform float u_FogStart; // The starting position of the shader fog
uniform float u_FogEnd; // The ending position of the shader fog
// OvFogI = (far desaturation, from, to, -) of the overgrown forest (ov_fog.glsl).
uniform vec4 OvFogI;

// 1 while Sodium draws the translucent pass (set by client/shader/SodiumTerrain.java; Sodium has no define for it).
uniform int ovSodiumPass;

out vec4 fragColor; // The output fragment for the color framebuffer

// 1 where the sun map knows a water face is in the shade of the canopy or a solid block (by day; 0 where the map is off or does not
// reach). For the sun on the water (ov_water.glsl, 11-06).
float ovWaterSunShade(vec3 pos) {
    if (OvSunA.z <= 0.0 || OvSunA.w <= 0.0) {
        return 0.0;
    }
    vec2 uv = vec2(pos.x - pos.y * OvSunA.x, pos.z - pos.y * OvSunA.y) - OvMapA.xy - 0.5;
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x >= OvMapA.z - 1.0 || uv.y >= OvMapA.z - 1.0) {
        return 0.0;
    }
    return ovShadowAt(ivec2(floor(uv + 0.5)), pos.y - OvMapA.w);
}

void main() {
    // Sodium's vertices carry no normal. Terrain faces are flat, so the face normal from the position derivatives is exact;
    // turn it towards the camera like the normal of a front face. Computed before any discard.
    vec3 n = normalize(cross(dFdx(ovPos), dFdy(ovPos)));
    if (dot(n, ovPos) > 0.0) {
        n = -n;
    }

    float lodBias = _material_use_mips(v_Material) ? 0.0 : float(-MAX_TEXTURE_LOD_BIAS);

    vec4 color = texture(u_BlockTex, v_TexCoord, lodBias);
    // The overgrown leaves are repainted by their colour code first (ov_leaf.glsl; keeps the alpha).
    color.rgb = ovLeafRecolor(color.rgb, ovLeaf);
    // The texel and the lit-free colour for the cave point lights (ov_cave.glsl).
    vec3 ovTexel = color.rgb;
    vec3 ovAlbedo = color.rgb * ovTint;
    color *= v_Color; // Apply per-vertex color modulator

#ifdef USE_FRAGMENT_DISCARD
    if (color.a < _material_alpha_cutoff(v_Material)) {
        discard;
    }
#endif
    // The floating films: their patchy look and the lanes parted through them (ov_wake.glsl, 11-13).
    vec4 ovWk = ovFilmWake(v_TexCoord, ovPos);
    if (ovWk.x > 0.5) {
        discard;
    }
    color.rgb *= ovWk.yzw;

    float ovUnder = 0.0;
    float ovGlow = 0.0;
    float ovSink = 0.0;
    if (ovSodiumPass != 1) {
        // Grass and ferns (vanilla cutout layer, alpha cutoff 1) are thin: no facing-away shade, like rendertype_cutout.fsh.
        // Water and glass (the translucent pass) are left as they are, like vanilla's rendertype_translucent.
        float facing = ((v_Material >> 1u) & 3u) == 1u ? 0.0 : 1.0;
        color.rgb = ovCanopyLight(color.rgb, ovPos, n, facing, ovLight);
        // The net of sunlight on faces under shallow water (ov_caustic.glsl).
        color.rgb = ovCaustics(color.rgb, ovPos, n, ovLight);
        color.rgb = ovWetSurface(color.rgb, ovPos, n, ovLight);
        // The point lights of glowing cave plants (ov_cave.glsl), before the fog.
        // How much this fragment is underground (ov_cave.glsl, the cave map; 11-01 evening). Thin plants use their own column.
        ovUnder = ovCaveUnder(ovPos, n * facing, ovLight.y);
        // The shadows of the drifting voxel clouds (ov_cloud.glsl, 11-08 clouds stage 1). None underground.
        color.rgb = ovCloudShadow(color.rgb, ovPos, n, facing, ovLight, ovCanopyMul, ovUnder);
        ovSink = ovCaveSink(ovPos, n * facing, ovUnder);
        // 11-09: leaves and other cutout-mipped blocks (alpha cutoff 2, 3) pass facing -1: ovCaveLight takes the foliage facing share
        // (OvCaveLights column 24 row 5 x; 1 = as solid rock).
        uint ovCutoff = (v_Material >> 1u) & 3u;
        color.rgb = ovCaveLight(color.rgb, ovAlbedo, ovPos, n, ovCutoff >= 2u ? -1.0 : facing, ovUnder, ovLight.x);
        // Grains of ore glint where a point light or a light in hand reaches (ov_ore.glsl, 11-13).
        color.rgb = ovOreSparkle(color.rgb, v_TexCoord, ovPos, n, ovCaveLightNow);
        // Faces under cave water get darker with the depth (ov_cave.glsl, 11-06 light inspection 10).
        color.rgb = ovCaveWaterBottom(color.rgb, ovPos, n, ovUnder);
        // The glowing pixels: while the camera is outside they go in before the forest haze, and deep in a cave they stay dim (11-04).
        ovGlow = ovCaveGlow(ovTexel, ovPos, ovUnder);
        color.rgb = mix(color.rgb, ovAlbedo, ovGlow * (1.0 - ovCaveCam()));
    } else {
        // Shallows and depths, foam along the shore (ov_water.glsl, 11-12). Water only.
        if (color.a < 0.99 && ovNoGlint < 0.5) {
            float ovLitK = max(max(v_Color.r, v_Color.g), v_Color.b) / max(max(max(ovTint.r, ovTint.g), ovTint.b), 0.001);
            color = ovWaterDepth(color, ovPos, n, ovLitK);
        }
        // Water and glass get the cave point lights too (11-04), with no facing-away shade, like vanilla's rendertype_translucent.fsh.
        ovUnder = ovCaveUnder(ovPos, n, ovLight.y);
        // The cloud shadows on water and glass too (ov_cloud.glsl, 11-08 clouds stage 1, improvement 9).
        color.rgb = ovCloudShadow(color.rgb, ovPos, n, 0.0, ovLight, vec3(1.0), ovUnder);
        ovSink = ovCaveSink(ovPos, n, ovUnder);
        color.rgb = ovCaveLight(color.rgb, ovAlbedo * OV_CAVE_WATER_GAIN, ovPos, n, 0.0, ovUnder, ovLight.x);
        // The point lights mirrored on the cave water (ov_cave.glsl, 11-06 light inspection 10).
        if (color.a < 0.99 && ovNoGlint < 0.5) {
            color.rgb += ovCaveWaterSpecular(ovPos, n, ovUnder);
        }
        // The sun and the moon mirrored on open water (ov_water.glsl, 11-06), kept out of the canopy shade by the sun map.
        // Opaque texels (alpha 1: grass, stone) never get it, whatever the pass flag says (11-06: the glint showed on a grass slope).
        // The sky and the clouds mirrored on open water (ov_water.glsl, 11-12), under the glint.
        if (color.a < 0.99 && ovNoGlint < 0.5) {
            // Not the sun map's shade: the sky is mirrored in the shadow of a cliff too (it cut the mirror along the shadow's block edge).
            color = ovWaterReflect(color, ovPos, n, ovLight.y, ovUnder, 0.0);
            // Rain rings on the water (ov_water.glsl, 11-12).
            color = ovWaterRain(color, ovPos, n, ovLight.y, ovUnder);
        }
        vec4 ovGlint = color.a < 0.99 && ovNoGlint < 0.5 ? ovWaterGlint(ovPos, n, ovLight.y, ovUnder, ovWaterSunShade(ovPos)) : vec4(0.0);
        color.rgb += ovGlint.rgb;
        color.a = mix(color.a, 1.0, ovGlint.a);
    }
    vec3 ovLit = color.rgb;

    // The height fog (ov_fog.glsl, computed per vertex) on every pass, water included.
    color.rgb = mix(color.rgb, ovFog.rgb, ovFog.a);
    vec3 ovSurface = color.rgb;
    color.rgb = mix(color.rgb, vec3(dot(color.rgb, vec3(0.299, 0.587, 0.114))), OvFogI.x * smoothstep(OvFogI.y, OvFogI.z, v_FragDistance));

    fragColor = ovSodiumFog(color, v_FragDistance, ovShadedFogColor(ovFogC(u_FogColor), ovFogS(u_FogStart), ovLight.y, v_FragDistance, ovPos), ovFogS(u_FogStart), ovFogE(u_FogEnd));
    // The fog by where the fragment is: caves seen from outside sink into black, the outside seen from a cave stays bright (ov_cave.glsl).
    // Every pass, water included (11-04).
    // How far the usual fogs moved this fragment toward the haze, for the cave part of the view (ov_cave.glsl, 11-04).
    float ovPathLen = ovCavePath(v_FragDistance, ovUnder);
    ovCaveSetHaze(ovFog, ovShadedFogColor(ovFogC(u_FogColor), ovFogS(u_FogStart), ovLight.y, v_FragDistance, ovPos), v_FragDistance, ovPathLen, ovSodiumFog(vec4(0.0, 0.0, 0.0, 1.0), v_FragDistance, vec4(1.0), ovFogS(u_FogStart), ovFogE(u_FogEnd)).r,
                  ovSodiumFog(vec4(0.0, 0.0, 0.0, 1.0), ovPathLen, vec4(1.0), ovFogS(u_FogStart), ovFogE(u_FogEnd)).r);
    fragColor.rgb = ovCaveFog(fragColor.rgb, ovLit, ovSurface, v_FragDistance, ovUnder);
    // The glowing pixels of the cave light blocks stay out of the fog (ov_cave.glsl).
    if (ovSodiumPass != 1) {
        fragColor.rgb = mix(fragColor.rgb, ovAlbedo * (1.0 - ovSink * OvCaveMouth.z), ovGlow * max(ovCaveCam(), ovSink));
    }
}
