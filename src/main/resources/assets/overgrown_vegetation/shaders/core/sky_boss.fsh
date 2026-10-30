#version 150

// Sky leviathan (overgrown_vegetation). rendertype_entity_cutout_no_cull, with distance haze instead of vanilla fog.

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform vec3 HazeColor;

in float hazeAmount;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a < 0.1) {
        discard;
    }
    color *= vertexColor * ColorModulator;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;
    color.rgb = mix(color.rgb, HazeColor, hazeAmount);
    fragColor = color;
}
