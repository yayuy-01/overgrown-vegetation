#version 150

// Overgrown Vegetation: the shelf cloud before a thunderstorm (11-16; client/atmosphere/ArcusCloud.java holds the explanation).
// Keep this file ASCII-only.
//
// The mesh is in the frame of the cloud: x along the length of the shelf, z from its leading edge towards its back, y the world height.
// ArcOrigin = the place of the origin of that frame from the camera (x, -camera y, z), ArcAxes = (length axis x z, back axis x z).
// The shelf is much farther than the far plane. ArcRemap = (d0, d1): vertices farther than d0 are pulled towards the camera along their own
// ray so that every distance up to infinity lands between d0 and d1, in the same order. The shape on screen does not change, the depth
// order within the cloud stays, and the terrain closer than d0 hides it the right way.
// Color: r = how lit the face is (1 top .. 0 underside), g = 0 at the leading edge .. 1 at the back, b = a tone per column.

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec4 ArcOrigin;
uniform vec4 ArcAxes;
uniform vec4 ArcRemap;

out vec4 ovShade;
out float ovDist;

void main() {
    vec3 rel = vec3(ArcOrigin.x + Position.x * ArcAxes.x + Position.z * ArcAxes.z, Position.y + ArcOrigin.y,
            ArcOrigin.z + Position.x * ArcAxes.y + Position.z * ArcAxes.w);
    float d = length(rel);
    ovDist = d;
    float dd = d <= ArcRemap.x ? d : ArcRemap.x + (ArcRemap.y - ArcRemap.x) * (1.0 - ArcRemap.x / d);
    vec3 pos = rel * (dd / max(d, 0.0001));
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    ovShade = Color;
}
