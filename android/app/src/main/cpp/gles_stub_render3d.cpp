#include "gles_stub_render3d.h"

#include <cstring>

namespace {
static GLuint Compile(GLenum type, const char* src)
{
  GLuint sh = glCreateShader(type);
  glShaderSource(sh, 1, &src, nullptr);
  glCompileShader(sh);
  GLint ok = 0;
  glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
  if (!ok)
  {
    glDeleteShader(sh);
    return 0;
  }
  return sh;
}
} // namespace

void GlesStubRender3D::BeginFrame()
{
  if (m_inited)
    return;

  static const char* vs = R"glsl(#version 300 es
    layout(location=0) in vec2 aPos;
    void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
  )glsl";

  static const char* fs = R"glsl(#version 300 es
    precision mediump float;
    out vec4 oColor;
    void main() { oColor = vec4(0.15, 0.2, 0.45, 1.0); }
  )glsl";

  GLuint v = Compile(GL_VERTEX_SHADER, vs);
  GLuint f = Compile(GL_FRAGMENT_SHADER, fs);
  if (!v || !f)
    return;

  m_program = glCreateProgram();
  glAttachShader(m_program, v);
  glAttachShader(m_program, f);
  glLinkProgram(m_program);
  glDeleteShader(v);
  glDeleteShader(f);

  GLint ok = 0;
  glGetProgramiv(m_program, GL_LINK_STATUS, &ok);
  if (!ok)
  {
    glDeleteProgram(m_program);
    m_program = 0;
    return;
  }

  glGenVertexArrays(1, &m_vao);
  glGenBuffers(1, &m_vbo);

  // Small triangle in the top-left corner (NDC).
  const float verts[] = {
    -0.98f,  0.98f,
    -0.70f,  0.98f,
    -0.98f,  0.70f,
  };

  glBindVertexArray(m_vao);
  glBindBuffer(GL_ARRAY_BUFFER, m_vbo);
  glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
  glEnableVertexAttribArray(0);
  glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, sizeof(float) * 2, (void*)0);
  glBindBuffer(GL_ARRAY_BUFFER, 0);
  glBindVertexArray(0);

  m_inited = true;
}

void GlesStubRender3D::RenderFrame()
{
  if (!m_inited)
    BeginFrame();
  if (!m_inited)
    return;

  glUseProgram(m_program);
  glBindVertexArray(m_vao);
  glDisable(GL_DEPTH_TEST);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glBindVertexArray(0);
  glUseProgram(0);
}
