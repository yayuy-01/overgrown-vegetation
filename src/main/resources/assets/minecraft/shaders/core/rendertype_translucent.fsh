#version 150

#moj_import <fog.glsl>
#moj_import <ov_cave.glsl>
#moj_import <ov_cloud.glsl>
#moj_import <ov_wave.glsl>
#moj_import <ov_water.glsl>

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
in vec3 ovPos;
in vec3 ovNormal;
in vec2 ovLight;
in vec3 ovTint;
in float ovNoGlint;

out vec4 fragColor;

// Overgrown Vegetation: the same as vanilla, plus the height fog of the overgrown forest (ov_fog.glsl) and (11-04) the cave point
// lights and the cave fog by where the fragment is (ov_cave.glsl) on water and glass. Keep this file ASCII-only.
void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    vec4 color = tex * vertexColor * ColorModulator;
    // Shallows and depths, foam along the shore (ov_water.glsl, 11-12). Water only (texel alpha below 1, not the spore dust).
    // The face normal from the derivatives, outside the branch (derivatives in non-uniform control flow are undefined).
    vec3 ovUp = normalize(cross(dFdx(ovPos), dFdy(ovPos)));
    ovUp = dot(ovUp, ovPos) > 0.0 ? -ovUp : ovUp;
    if (color.a < 0.99 && ovNoGlint < 0.5) {
        float ovLitK = max(max(vertexColor.r, vertexColor.g), vertexColor.b) / max(max(max(ovTint.r, ovTint.g), ovTint.b), 0.001);
        color = ovWaterDepth(color, ovPos, ovUp, ovLitK);
    }
    // The point lights of glowing cave plants (ov_cave.glsl), before the fog. Water's vertex normal is up on every face, so no
    // facing-away shade (facing 0, like thin plants); the column above the face decides whether it is underground.
    float ovUnder = ovCaveUnder(ovPos, ovNormal, ovLight.y);
    // The shadows of the drifting voxel clouds on water and glass (ov_cloud.glsl, 11-08 clouds stage 1, improvement 9). None underground.
    color.rgb = ovCloudShadow(color.rgb, ovPos, ovNormal, 0.0, ovLight, vec3(1.0), ovUnder);
    // Water takes half the light of a solid face (11-04 provisional: at full strength the lake looked like it glowed).
    ovCaveSink(ovPos, ovNormal, ovUnder);
    // Water takes none of the raised floor (ov_cave.glsl OV_CAVE_WATER_LIFT, 11-14); glass keeps it.
    ovCaveLift = tex.a < 0.99 && ovNoGlint < 0.5 ? OV_CAVE_WATER_LIFT : 1.0;
    // The side faces of falling water take less of the point lights (OV_CAVE_FALL_GAIN, 11-14).
    float ovWaterGain = tex.a < 0.99 && ovNoGlint < 0.5 && abs(ovUp.y) < 0.5 ? OV_CAVE_FALL_GAIN : OV_CAVE_WATER_GAIN;
    color.rgb = ovCaveLight(color.rgb, tex.rgb * ovTint * ovWaterGain, ovPos, ovNormal, 0.0, ovUnder, ovLight.x);
    // The point lights mirrored on the cave water (11-06, light inspection 10). Water's vertex normal is up on every face, so the face
    // normal comes from the position derivatives, turned towards the camera (seen from below while diving it points down: no mirror).
    vec3 ovFace = normalize(cross(dFdx(ovPos), dFdy(ovPos)));
    if (dot(ovFace, ovPos) > 0.0) {
        ovFace = -ovFace;
    }
    if (color.a < 0.99 && ovNoGlint < 0.5) {
        color.rgb += ovCaveWaterSpecular(ovPos, ovFace, ovUnder);
    }
    // The sun and the moon mirrored on open water (ov_water.glsl, 11-06). Without the canopy map here: the sky light alone keeps it out
    // from under leaves and roofs.
    // Opaque texels (alpha 1) never get it (11-06, see the Sodium shader).
    // The sky and the clouds mirrored on open water (ov_water.glsl, 11-12), under the glint.
    if (color.a < 0.99 && ovNoGlint < 0.5) {
        color = ovWaterReflect(color, ovPos, ovFace, ovLight.y, ovUnder, 0.0);
        // Rain rings on the water (ov_water.glsl, 11-12).
        color = ovWaterRain(color, ovPos, ovFace, ovLight.y, ovUnder);
    }
    vec4 ovGlint = color.a < 0.99 && ovNoGlint < 0.5 ? ovWaterGlint(ovPos, ovFace, ovLight.y, ovUnder, 0.0) : vec4(0.0);
    color.rgb += ovGlint.rgb;
    color.a = mix(color.a, 1.0, ovGlint.a);
    vec3 ovLit = color.rgb;
    color.rgb = mix(color.rgb, ovFog.rgb, ovFog.a);
    vec3 ovSurface = color.rgb;
    // The far forest loses a little saturation (A6; aerial perspective).
    color.rgb = mix(color.rgb, vec3(dot(color.rgb, vec3(0.299, 0.587, 0.114))), OvFogI.x * smoothstep(OvFogI.y, OvFogI.z, vertexDistance));
    fragColor = linear_fog(color, vertexDistance, ovFogS(FogStart), ovFogE(FogEnd), ovShadedFogColor(ovFogC(FogColor), ovFogS(FogStart), ovLight.y, vertexDistance, ovPos));
    // The fog by where the fragment is: cave water seen from outside sinks into black, outside water seen from a cave stays bright.
    // How far the usual fogs moved this fragment toward the haze, for the cave part of the view (ov_cave.glsl, 11-04).
    float ovPathLen = ovCavePath(vertexDistance, ovUnder);
    ovCaveSetHaze(ovFog, ovShadedFogColor(ovFogC(FogColor), ovFogS(FogStart), ovLight.y, vertexDistance, ovPos), vertexDistance, ovPathLen, linear_fog(vec4(0.0, 0.0, 0.0, 1.0), vertexDistance, ovFogS(FogStart), ovFogE(FogEnd), vec4(1.0)).r,
                  linear_fog(vec4(0.0, 0.0, 0.0, 1.0), ovPathLen, ovFogS(FogStart), ovFogE(FogEnd), vec4(1.0)).r);
    fragColor.rgb = ovCaveFog(fragColor.rgb, ovLit, ovSurface, vertexDistance, ovUnder);
}
