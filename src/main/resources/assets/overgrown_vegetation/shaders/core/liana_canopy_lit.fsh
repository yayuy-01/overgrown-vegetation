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
in vec2 ovLight;

out vec4 fragColor;

// Overgrown Vegetation: the lianas (the hanging lianas of client/CorruptedLianaRenderer and the climbing vines of
// client/ClimbingVineRenderer), lit like the thin plants of the terrain (rendertype_cutout.fsh: facing 0, no facing-away shade).
// The normal only decides that sunflecks may land (a side face). The face shade of the climbing vines (a tube like a trunk) is in
// their vertex colour (ClimbingVineRenderer.faceShade); the thin hanging ribbons get none (11-06: seen from below they went 0.5,
// darker than the trunks). Keep this file ASCII-only.
void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    color.rgb = ovCanopyLight(color.rgb, ovPos, vec3(0.0, 0.0, 1.0), 0.0, ovLight);
    // The shadows of the drifting voxel clouds (ov_cloud.glsl, 11-08 clouds stage 1, improvement 9). Lianas hang in the open air: no cave column.
    color.rgb = ovCloudShadow(color.rgb, ovPos, vec3(0.0, 0.0, 1.0), 0.0, ovLight, ovCanopyMul, 0.0);
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
