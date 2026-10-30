#version 150

#moj_import <fog.glsl>
#moj_import <ov_light.glsl>
#moj_import <ov_leaf.glsl>

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
in vec4 ovLeaf;

out vec4 fragColor;

// Overgrown Vegetation: the same as vanilla, plus the canopy light (ov_light.glsl). Leaves are cubes, so faces that look away from the sun are shaded too.
// Keep this file ASCII-only.
void main() {
    // The overgrown leaves are repainted by their colour code first (ov_leaf.glsl; keeps the alpha).
    vec4 tex = texture(Sampler0, texCoord0);
    tex.rgb = ovLeafRecolor(tex.rgb, ovLeaf);
    vec4 color = tex * vertexColor * ColorModulator;
    if (color.a < 0.5) {
        discard;
    }
    color.rgb = ovCanopyLight(color.rgb, ovPos, ovNormal, 1.0, ovLight);
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
