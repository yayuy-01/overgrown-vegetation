#version 150

#moj_import <ov_fog.glsl>

// Overgrown Vegetation: the silhouettes of the giant trees beyond the fog (client/atmosphere/GiantSilhouettes.java holds the
// explanation). Keep this file ASCII-only: some drivers reject UTF-8 in GLSL.
//
// Where vanilla fog hides the real tree to about 60 percent (CutEnd), the silhouette takes over (blended over CutStart..CutEnd).
// Beyond the cut: vanilla fog goes on from FogAtCut to full at HazeEnd (stretched), and the height fog of the overgrown forest
// (ov_fog.glsl, per fragment because the merged faces are tens of blocks wide) is the same as on the real tree up to the cut,
// then MistStretch times thinner. Both match the real tree at the cut, so the seam does not show.

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
uniform float MistStretch;
// 11-06 evening: inside the forest haze (the mark of MistView, k per block) the silhouette is drawn too. HazeK = k / hazeStretch (0 = outside
// the haze: the old stretched smoothstep); the haze goes on from FogAtCut as 1 - (1 - FogAtCut) exp(-HazeK (d - CutEnd)), and its colour
// turns from the fog colour of the terrain (darkened in the shade of the canopy) to HazeFar (the fog colour before that darkening) within
// 64 blocks past the cut, so the far crown is a pale shape in the bright haze, not a dark block.
uniform float HazeK;
uniform vec3 HazeFar;

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
    vec3 lit = vertexColor.rgb * Brightness;
    vec4 mist = ovFogShaded(relPos, 1.0);
    if (mist.a > 0.0) {
        float len = length(relPos);
        vec4 atCut = ovFogShaded(relPos * min(1.0, CutEnd / max(len, 0.001)), 1.0);
        float tauFull = -log(max(1.0 - mist.a, 0.0001));
        float tauCut = -log(max(1.0 - atCut.a, 0.0001));
        float tau = min(tauFull, tauCut) + max(tauFull - tauCut, 0.0) / MistStretch;
        lit = mix(lit, mist.rgb, 1.0 - exp(-tau));
    }
    float haze = FogAtCut + (1.0 - FogAtCut) * smoothstep(CutEnd, HazeEnd, d);
    vec3 fogColor = FogColor.rgb;
    if (HazeK > 0.0) {
        haze = 1.0 - (1.0 - FogAtCut) * exp(-HazeK * max(d - CutEnd, 0.0));
        fogColor = mix(FogColor.rgb, HazeFar, smoothstep(CutEnd, CutEnd + 64.0, d));
    }
    fragColor = vec4(mix(lit, fogColor, haze), appear) * ColorModulator;
}
