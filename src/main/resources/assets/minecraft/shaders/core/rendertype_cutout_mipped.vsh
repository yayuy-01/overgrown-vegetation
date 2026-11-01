#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>
#moj_import <ov_fog.glsl>
#moj_import <ov_sway.glsl>
#moj_import <ov_leaf.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 ChunkOffset;
uniform int FogShape;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec3 ovPos;
out vec3 ovNormal;
out vec2 ovLight;
out vec4 ovFog;
out vec3 ovTint;
out vec4 ovLeaf;

// Overgrown Vegetation: the same as vanilla, plus the camera-relative position, the normal and the light levels for the
// canopy light (ov_light.glsl), plants swaying in the wind (ov_sway.glsl) and the leaf colour code (ov_leaf.glsl). Keep this file ASCII-only.
void main() {
    // Plants sway in the wind (ov_sway.glsl); marked vertices get their alpha back to 1.
    // The overgrown leaves carry a colour code in their tint (ov_leaf.glsl); read it before the sway resets the alpha.
    vec4 color = Color;
    vec3 tint = Color.rgb;
    ovLeaf = ovLeafDecode(Color, tint);
    color.rgb = tint;
    vec3 pos = Position + ChunkOffset;
    pos += ovDisplace(pos, Normal, color.a);
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexDistance = fog_distance(pos, FogShape);
    vertexColor = color * minecraft_sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
    ovPos = pos;
    ovNormal = Normal;
    ovLight = clamp(vec2(UV2) / 240.0, 0.0, 1.0);
    ovFog = ovFogShaded(pos, ovLight.y);
    // The tint without the light, for the cave point lights (ov_cave.glsl).
    ovTint = color.rgb;
}
