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

in vec4 v_Color; // The interpolated vertex color
in vec2 v_TexCoord; // The interpolated block texture coordinates
in float v_FragDistance; // The fragment's distance from the camera

flat in uint v_Material;

in vec3 ovPos;
in vec2 ovLight;
in vec4 ovFog;
in vec4 ovLeaf;
in vec3 ovTint;

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
    float k = (-fogStart - 1000.0) / 100000.0;
    float wallStart = fogEnd - clamp(fogEnd / 10.0, 4.0, 64.0);
    float wall = d <= wallStart ? 0.0 : (d < fogEnd ? smoothstep(wallStart, fogEnd, d) : 1.0);
    float v = 1.0 - (1.0 - wall) * exp(-k * d);
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

    float ovUnder = 0.0;
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
        color.rgb = ovCaveLight(color.rgb, ovAlbedo, ovPos, n, facing, ovUnder);
    }
    vec3 ovLit = color.rgb;

    // The height fog (ov_fog.glsl, computed per vertex) on every pass, water included.
    color.rgb = mix(color.rgb, ovFog.rgb, ovFog.a);
    vec3 ovSurface = color.rgb;
    color.rgb = mix(color.rgb, vec3(dot(color.rgb, vec3(0.299, 0.587, 0.114))), OvFogI.x * smoothstep(OvFogI.y, OvFogI.z, v_FragDistance));

    fragColor = ovSodiumFog(color, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
    // The fog by where the fragment is: caves seen from outside sink into black, the outside seen from a cave stays bright (ov_cave.glsl).
    if (ovSodiumPass != 1) {
        fragColor.rgb = ovCaveFog(fragColor.rgb, ovLit, ovSurface, v_FragDistance, ovUnder);
    }
    // The glowing pixels of the cave light blocks stay out of the fog (ov_cave.glsl).
    if (ovSodiumPass != 1) {
        fragColor.rgb = mix(fragColor.rgb, ovAlbedo, ovCaveGlow(ovTexel, ovPos, ovUnder));
    }
}
