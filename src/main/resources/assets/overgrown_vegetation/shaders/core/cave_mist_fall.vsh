#version 150

// CaveMist (see the Java class for the explanation; keep this file ASCII-only: some drivers reject UTF-8 in GLSL).
// Curtains of mist pouring over the edge of a cliff or into a hole. Position is relative to the map origin; Color.r is how
// far down the fall (0 = top, 1 = where it has faded), Color.g the block light and Color.b the sky light at the edge
// (0 to 1), and Color.a its weight.

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
