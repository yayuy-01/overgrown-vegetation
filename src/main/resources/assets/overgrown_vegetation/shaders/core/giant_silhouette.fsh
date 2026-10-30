#version 150

// GiantTreeSilhouettes (see the Java class for the explanation; keep this file ASCII-only: some drivers reject UTF-8 in GLSL).

uniform vec4 ColorModulator;
uniform vec4 FogColor;
uniform int FogShape;
uniform float CutStart;
uniform float CutEnd;
uniform float FogAtCut;
uniform float HazeEnd;
uniform float Brightness;
uniform float FarStart;
uniform float FarEnd;

in vec3 relPos;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    float d = FogShape == 0 ? length(relPos) : max(length(relPos.xz), abs(relPos.y));
    if (d <= CutStart) {
        discard;
    }
    // The far fade uses the horizontal distance: chunks are loaded by horizontal distance, while the crown can be far above.
    float appear = smoothstep(CutStart, CutEnd, d) * (1.0 - smoothstep(FarStart, FarEnd, length(relPos.xz)));
    if (appear <= 0.0) {
        discard;
    }
    float haze = FogAtCut + (1.0 - FogAtCut) * smoothstep(CutEnd, HazeEnd, d);
    vec3 lit = vertexColor.rgb * Brightness;
    fragColor = vec4(mix(lit, FogColor.rgb, haze), appear) * ColorModulator;
}
