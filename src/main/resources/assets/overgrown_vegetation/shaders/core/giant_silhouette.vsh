#version 150

// GiantTreeSilhouettes (see the Java class for the explanation; keep this file ASCII-only: some drivers reject UTF-8 in GLSL).

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
