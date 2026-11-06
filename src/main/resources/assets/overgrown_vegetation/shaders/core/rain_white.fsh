#version 150

#moj_import <fog.glsl>

// Overgrown Vegetation: the rain, the snow and the water drop particles in translucent white (11-06, client/atmosphere/RainLook.java).
// The drawings stay vanilla (10-31: the water textures of Minecraft); only the colour changes. The texel keeps its pattern of light and
// dark but loses its blue, the vertex colour gives only the opacity (the water drips carry a blue colour from vanilla), and the light
// is grey (the brightness of the lightmap). Keep this file ASCII-only.
//
// OvRainTint = (colour rgb: white with a little of the sky colour of the hour, and the spore tint, opacity multiplier)

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform vec4 OvRainTint;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;
in vec4 lightColor;

out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    float alpha = tex.a * vertexColor.a * ColorModulator.a * OvRainTint.a;
    if (alpha < 0.02) {
        discard;
    }
    // The brightest channel keeps the pattern (the blue rain streak is about 0.76 at its middle): 0.8 .. 1.0 of the tint.
    float v = max(tex.r, max(tex.g, tex.b));
    float light = dot(lightColor.rgb, vec3(0.299, 0.587, 0.114));
    vec3 rgb = vec3(mix(0.8, 1.0, v)) * OvRainTint.rgb * light;
    fragColor = linear_fog(vec4(rgb, alpha), vertexDistance, FogStart, FogEnd, FogColor);
}
