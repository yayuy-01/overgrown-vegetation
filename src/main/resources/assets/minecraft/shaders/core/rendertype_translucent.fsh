#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
// OvFogI = (far desaturation, from, to, -) of the overgrown forest (ov_fog.glsl).
uniform vec4 OvFogI;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec4 ovFog;

out vec4 fragColor;

// Overgrown Vegetation: the same as vanilla, plus the height fog of the overgrown forest (ov_fog.glsl). Keep this file ASCII-only.
void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    color.rgb = mix(color.rgb, ovFog.rgb, ovFog.a);
    // The far forest loses a little saturation (A6; aerial perspective).
    color.rgb = mix(color.rgb, vec3(dot(color.rgb, vec3(0.299, 0.587, 0.114))), OvFogI.x * smoothstep(OvFogI.y, OvFogI.z, vertexDistance));
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
