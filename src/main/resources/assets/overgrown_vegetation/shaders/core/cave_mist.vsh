#version 150

// CaveMist (see the Java class for the explanation; keep this file ASCII-only: some drivers reject UTF-8 in GLSL).
// Thin sheets of mist lying on the floor of large caves. Position is relative to the map origin; Color.r is the depth of
// the sheet in the layer (0 = top, 1 = bottom), Color.g the block light and Color.b the sky light there (0 to 1), and
// Color.a its weight (open floor, away from walls and the map edge).

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 Origin;

out vec3 relPos;
out vec4 vertexColor;

void main() {
    vec3 pos = Position + Origin;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    relPos = pos;
    vertexColor = Color;
}
