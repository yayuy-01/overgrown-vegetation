#version 150

#moj_import <ov_light.glsl>
#moj_import <ov_cloud.glsl>
#moj_import <ov_leaf.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;

in vec4 vertexColor;
in vec4 ovTexLight;
in vec4 ovPosC;
flat in vec4 ovNormThin;
in vec4 ovMix;
in vec3 ovAlbedo;
in vec4 ovLeaf;

out vec4 fragColor;

// Overgrown Vegetation: the same as vanilla, plus the canopy shade (ov_light.glsl). Leaves are cubes, so faces that look
// away from the sun are shaded too. The overgrown leaves are repainted by their colour code first (ov_leaf.glsl).
void main() {
    vec4 tex = texture(Sampler0, ovTexLight.xy);
    // The recolouring keeps the alpha, so the cut is tested first (2026-10-24).
    if (tex.a * vertexColor.a * ColorModulator.a < 0.5) {
        discard;
    }
    tex.rgb = ovLeafRecolor(tex.rgb, ovLeaf);
    vec4 color = tex * vertexColor * ColorModulator;
    // This mod's thin plants (moved from the cutout layer) keep the cutout look: no facing-away shade.
    float facing = 1.0 - ovNormThin.w;
    vec3 normal = ovNormThin.xyz;
    vec2 light = ovTexLight.zw;
    color.rgb = ovCanopyLight(color.rgb, ovPosC.xyz, normal, facing, light);
    color.rgb = ovCloudShadowApply(color.rgb, ovPosC.w, normal, facing, ovCanopyShade);
    color.rgb = ovCaveFloor(color.rgb, tex.rgb * ovAlbedo, light);
    // Both mists and the fog, computed per vertex (rendertype_cutout_mipped.vsh ovMix).
    color.rgb = color.rgb * ovMix.a + ovMix.rgb;
    fragColor = color;
}
