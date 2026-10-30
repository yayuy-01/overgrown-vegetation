#version 150

#moj_import <fog.glsl>
#moj_import <ov_light.glsl>
#moj_import <ov_cloud.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 ovPos;
in vec3 ovNormal;
in vec2 ovLight;
in vec4 ovHaze;
in vec4 ovFog;
in vec3 ovAlbedo;

out vec4 fragColor;

// Overgrown Vegetation: the same as vanilla, plus the canopy shade (ov_light.glsl). Grass and ferns are thin, so no
// facing-away shade here.
void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    color.rgb = ovCanopyLight(color.rgb, ovPos, ovNormal, 0.0, ovLight);
    color.rgb = ovCloudShadowApply(color.rgb, ovFog.w, ovNormal, 0.0, ovCanopyShade);
    color.rgb = ovCaveFloor(color.rgb, texture(Sampler0, texCoord0).rgb * ovAlbedo, ovLight);
    color.rgb = ovClimaxUplight(color.rgb, texture(Sampler0, texCoord0).rgb * ovAlbedo, ovNormal);
    // Both mists, computed per vertex (rendertype_*.vsh ovHaze).
    color.rgb = color.rgb * ovHaze.a + ovHaze.rgb;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, vec4(ovFog.rgb, FogColor.a));
}
