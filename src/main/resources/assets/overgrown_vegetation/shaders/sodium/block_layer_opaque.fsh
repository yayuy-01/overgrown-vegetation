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

in vec4 v_Color; // The interpolated vertex color
in vec2 v_TexCoord; // The interpolated block texture coordinates
in float v_FragDistance; // The fragment's distance from the camera

flat in uint v_Material;

in vec3 ovPos;
in vec2 ovLight;

uniform sampler2D u_BlockTex; // The block texture

uniform vec4 u_FogColor; // The color of the shader fog
uniform float u_FogStart; // The starting position of the shader fog
uniform float u_FogEnd; // The ending position of the shader fog

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
    color *= v_Color; // Apply per-vertex color modulator

#ifdef USE_FRAGMENT_DISCARD
    if (color.a < _material_alpha_cutoff(v_Material)) {
        discard;
    }
#endif

    if (ovSodiumPass != 1) {
        // Grass and ferns (vanilla cutout layer, alpha cutoff 1) are thin: no facing-away shade, like rendertype_cutout.fsh.
        // Water and glass (the translucent pass) are left as they are, like vanilla's rendertype_translucent.
        float facing = ((v_Material >> 1u) & 3u) == 1u ? 0.0 : 1.0;
        color.rgb = ovCanopyLight(color.rgb, ovPos, n, facing, ovLight);
    }

    fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
}
