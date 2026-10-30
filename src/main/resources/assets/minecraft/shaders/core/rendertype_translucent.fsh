#version 150

#moj_import <fog.glsl>
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
in vec2 ovLight;
in vec4 ovHaze;
in vec4 ovFog;

out vec4 fragColor;

// Overgrown Vegetation: the same as vanilla, plus the morning mist (ov_mist.glsl) so that water in a misty valley is
// hazed like the ground around it, and the cloud shadows (ov_cloud.glsl) so that they drift over water too.
void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    color.rgb = ovCloudShadowApply(color.rgb, ovFog.w, vec3(0.0, 1.0, 0.0), 0.0, 0.0);
    // Both mists, computed per vertex (rendertype_*.vsh ovHaze).
    color.rgb = color.rgb * ovHaze.a + ovHaze.rgb;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, vec4(ovFog.rgb, FogColor.a));
}
