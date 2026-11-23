#version 150

#moj_import <fog.glsl>
#moj_import <ov_light.glsl>
#moj_import <ov_wet.glsl>
#moj_import <ov_caustic.glsl>
#moj_import <ov_cave.glsl>
#moj_import <ov_cloud.glsl>
#moj_import <ov_leaf.glsl>
#moj_import <ov_wake.glsl>

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
in vec4 normal;
in vec3 ovPos;
in vec3 ovNormal;
in vec2 ovLight;
in vec4 ovFog;
in vec3 ovTint;
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
    // The floating films: their patchy look and the lanes parted through them (ov_wake.glsl, 11-13).
    vec4 ovWk = ovFilmWake(texCoord0, ovPos);
    if (ovWk.x > 0.5) {
        discard;
    }
    color.rgb *= ovWk.yzw;
    color.rgb = ovCanopyLight(color.rgb, ovPos, ovNormal, 1.0, ovLight);
    // The net of sunlight on faces under shallow water (ov_caustic.glsl).
    color.rgb = ovCaustics(color.rgb, ovPos, ovNormal, ovLight);
    // Wet faces in rain and after rain (ov_wet.glsl).
    color.rgb = ovWetSurface(color.rgb, ovPos, ovNormal, ovLight);
    // The point lights of glowing cave plants (ov_cave.glsl), before the fog.
    // How much this fragment is underground (ov_cave.glsl, the cave map; 11-01 evening).
    float ovUnder = ovCaveUnder(ovPos, ovNormal * 1.0, ovLight.y);
    // The shadows of the drifting voxel clouds (ov_cloud.glsl, 11-08 clouds stage 1). None underground.
    color.rgb = ovCloudShadow(color.rgb, ovPos, ovNormal, 1.0, ovLight, ovCanopyMul, ovUnder);
    float ovSink = ovCaveSink(ovPos, ovNormal * 1.0, ovUnder);
    // 11-09: facing -1 = foliage (leaves, plants): ovCaveLight takes its own facing share for it (OvCaveLights column 24 row 5 x).
    color.rgb = ovCaveLight(color.rgb, tex.rgb * ovTint, ovPos, ovNormal, -1.0, ovUnder, ovLight.x);
    // Faces under cave water get darker with the depth (ov_cave.glsl, 11-06 light inspection 10).
    color.rgb = ovCaveWaterBottom(color.rgb, ovPos, ovNormal, ovUnder);
    // The glowing pixels: while the camera is outside they go in before the forest haze, and deep in a cave they stay dim (11-04).
    float ovGlow = ovCaveGlow(tex.rgb, ovPos, ovUnder);
    color.rgb = mix(color.rgb, tex.rgb * ovTint, ovGlow * (1.0 - ovCaveCam()));
    vec3 ovLit = color.rgb;
    color.rgb = mix(color.rgb, ovFog.rgb, ovFog.a);
    vec3 ovSurface = color.rgb;
    // The far forest loses a little saturation (A6; aerial perspective).
    color.rgb = mix(color.rgb, vec3(dot(color.rgb, vec3(0.299, 0.587, 0.114))), OvFogI.x * smoothstep(OvFogI.y, OvFogI.z, vertexDistance));
    fragColor = linear_fog(color, vertexDistance, ovFogS(FogStart), ovFogE(FogEnd), ovShadedFogColor(ovFogC(FogColor), ovFogS(FogStart), ovLight.y, vertexDistance, ovPos));
    // The fog by where the fragment is: caves seen from outside sink into black, the outside seen from a cave stays bright (ov_cave.glsl).
    // How far the usual fogs moved this fragment toward the haze, for the cave part of the view (ov_cave.glsl, 11-04).
    float ovPathLen = ovCavePath(vertexDistance, ovUnder);
    ovCaveSetHaze(ovFog, ovShadedFogColor(ovFogC(FogColor), ovFogS(FogStart), ovLight.y, vertexDistance, ovPos), vertexDistance, ovPathLen, linear_fog(vec4(0.0, 0.0, 0.0, 1.0), vertexDistance, ovFogS(FogStart), ovFogE(FogEnd), vec4(1.0)).r,
                  linear_fog(vec4(0.0, 0.0, 0.0, 1.0), ovPathLen, ovFogS(FogStart), ovFogE(FogEnd), vec4(1.0)).r);
    fragColor.rgb = ovCaveFog(fragColor.rgb, ovLit, ovSurface, vertexDistance, ovUnder);
    // The glowing pixels of the cave light blocks stay out of the fog (ov_cave.glsl).
    fragColor.rgb = mix(fragColor.rgb, tex.rgb * ovTint * (1.0 - ovSink * OvCaveMouth.z), ovGlow * max(ovCaveCam(), ovSink));
}
