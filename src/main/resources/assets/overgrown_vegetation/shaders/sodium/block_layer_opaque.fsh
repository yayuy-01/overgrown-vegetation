#version 330 core

// Overgrown Vegetation: Sodium's terrain fragment shader (sodium:blocks/block_layer_opaque.fsh, Sodium 0.8.x) with the same
// effects as our vanilla rendertype_{solid,cutout,cutout_mipped,translucent}.fsh: canopy shade (ov_light.glsl), cloud
// shadows (ov_cloud.glsl), morning mist and aerial fog (ov_mist.glsl), cave mist (ov_cave_mist.glsl) and the leaf colours
// (ov_leaf.glsl). client/SodiumTerrain.java swaps this in for Sodium's file and expands the #ov_import lines.
// Keep this file ASCII-only.

#ifndef MAX_TEXTURE_LOD_BIAS
#error "MAX_TEXTURE_LOD_BIAS constant not specified"
#endif

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_material.glsl>
#ov_import <ov_light.glsl>
#ov_import <ov_cloud.glsl>
#ov_import <ov_leaf.glsl>

in vec4 v_Color; // The interpolated vertex color
in vec2 v_TexCoord; // The interpolated block texture coordinates
in float v_FragDistance; // The fragment's distance from the camera

flat in uint v_Material;

in vec3 ovPos;
in vec2 ovLight;
in vec3 ovAlbedo;
in vec4 ovLeaf;
in float ovThin;
in vec4 ovHaze;
in vec4 ovFog;

uniform sampler2D u_BlockTex; // The block texture

uniform vec4 u_FogColor; // The color of the shader fog
uniform float u_FogStart; // The starting position of the shader fog
uniform float u_FogEnd; // The ending position of the shader fog

// 1 while Sodium draws the translucent pass (set by client/SodiumTerrain.java; Sodium has no define for it).
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

    vec4 tex = texture(u_BlockTex, v_TexCoord, lodBias);
    tex.rgb = ovLeafRecolor(tex.rgb, ovLeaf);
    vec4 color = tex * v_Color; // Apply per-vertex color modulator

#ifdef USE_FRAGMENT_DISCARD
    if (color.a < _material_alpha_cutoff(v_Material)) {
        discard;
    }
#endif

    if (ovSodiumPass == 1) {
        // Translucent (water, glass): like our rendertype_translucent.fsh.
        color.rgb = ovCloudShadowApply(color.rgb, ovFog.w, vec3(0.0, 1.0, 0.0), 0.0, 0.0);
    } else {
        // Grass and ferns (vanilla cutout layer, alpha cutoff 1) are thin: no facing-away shade, like rendertype_cutout.fsh.
        float facing = ((v_Material >> 1u) & 3u) == 1u || ovThin > 0.5 ? 0.0 : 1.0;
        vec3 albedo = tex.rgb * ovAlbedo;
        color.rgb = ovCanopyLight(color.rgb, ovPos, n, facing, ovLight);
        color.rgb = ovCloudShadowApply(color.rgb, ovFog.w, n, facing, ovCanopyShade);
        color.rgb = ovCaveFloor(color.rgb, albedo, ovLight);
    }
    // Both mists, computed per vertex (block_layer_opaque.vsh ovHaze).
    color.rgb = color.rgb * ovHaze.a + ovHaze.rgb;

    fragColor = _linearFog(color, v_FragDistance, vec4(ovFog.rgb, u_FogColor.a),
            u_FogStart, u_FogEnd);
}
