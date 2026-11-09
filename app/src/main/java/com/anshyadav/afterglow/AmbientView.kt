package com.anshyadav.afterglow

import android.content.Context
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*
import kotlin.random.Random

/** The moving wash of album colour behind everything, plus a little drifting dust. */
class AmbientView(ctx: Context) : GLSurfaceView(ctx), GLSurfaceView.Renderer {

    private val targetCol = FloatArray(15)
    private val col = FloatArray(15)
    private val targetGlow = FloatArray(3)
    private val glow = FloatArray(3)
    private val pos = FloatArray(10)
    private var hasMood = false
    private var colorTau = 0.6f

    @Volatile var energy = 0f
    @Volatile var beat = 0f
    @Volatile var motion = 0.3f
    @Volatile var dim = 1f
    @Volatile var motes = 0f
    @Volatile var artX = 0.28f
    @Volatile var artY = 0.5f

    private var prog = 0
    private var uRes = 0; private var uT = 0; private var uE = 0; private var uB = 0; private var uD = 0
    private var uP = 0; private var uC = 0; private var uArt = 0; private var uGlow = 0
    private var moteProg = 0
    private var uMRes = 0; private var uMB = 0; private var uMCol = 0
    private var w = 1; private var h = 1
    private var time = 0f
    private var last = 0L

    private val quad: FloatBuffer = floatBuf(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f))
    private val moteCount = 30
    private val moteData = FloatArray(moteCount * 4)
    private val moteBuf: FloatBuffer = floatBuf(moteData)
    private val moteSeed = FloatArray(moteCount) { Random.nextFloat() * 6.28f }

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
        for (i in 0 until moteCount) {
            moteData[i * 4] = Random.nextFloat()
            moteData[i * 4 + 1] = Random.nextFloat()
            moteData[i * 4 + 2] = 0.3f + Random.nextFloat() * 0.7f
        }
    }

    private fun floatBuf(a: FloatArray) = ByteBuffer.allocateDirect(a.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).also { it.position(0) }

    fun setMood(m: Mood, tau: Float) {
        System.arraycopy(m.ambient, 0, targetCol, 0, 15)
        System.arraycopy(m.glow, 0, targetGlow, 0, 3)
        colorTau = tau
        if (!hasMood) {
            hasMood = true
            System.arraycopy(targetCol, 0, col, 0, 15)
            System.arraycopy(targetGlow, 0, glow, 0, 3)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        prog = link(VERT, FRAG)
        uRes = glGetUniformLocation(prog, "uRes"); uT = glGetUniformLocation(prog, "uT")
        uE = glGetUniformLocation(prog, "uE"); uB = glGetUniformLocation(prog, "uB")
        uD = glGetUniformLocation(prog, "uD"); uP = glGetUniformLocation(prog, "uP")
        uC = glGetUniformLocation(prog, "uC"); uArt = glGetUniformLocation(prog, "uArt")
        uGlow = glGetUniformLocation(prog, "uGlow")
        moteProg = link(MOTE_V, MOTE_F)
        uMRes = glGetUniformLocation(moteProg, "uRes"); uMB = glGetUniformLocation(moteProg, "uB")
        uMCol = glGetUniformLocation(moteProg, "uCol")
        glDisable(GL_DEPTH_TEST)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE)
        last = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = width; h = height
        glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - last) / 1e9f).coerceIn(0.001f, 0.1f)
        last = now
        time += dt * (motion + energy * 1.3f)

        for (i in 0 until 15) col[i] = smooth(col[i], targetCol[i], dt, colorTau)
        for (i in 0 until 3) glow[i] = smooth(glow[i], targetGlow[i], dt, colorTau)

        val asp = w / h.toFloat()
        val t = time
        pos[0] = asp * (0.24f + 0.20f * sin(t * 0.37f)); pos[1] = 0.62f + 0.26f * cos(t * 0.29f)
        pos[2] = asp * (0.76f + 0.21f * cos(t * 0.31f + 1.1f)); pos[3] = 0.70f + 0.24f * sin(t * 0.43f + 2.0f)
        pos[4] = asp * (0.52f + 0.34f * sin(t * 0.23f + 4.0f)); pos[5] = 0.22f + 0.20f * cos(t * 0.37f + 0.6f)
        pos[6] = asp * (0.10f + 0.16f * cos(t * 0.41f + 3.0f)); pos[7] = 0.16f + 0.20f * sin(t * 0.33f + 5.0f)
        pos[8] = asp * (0.92f + 0.12f * sin(t * 0.27f + 1.7f)); pos[9] = 0.18f + 0.22f * cos(t * 0.21f + 3.3f)

        glDisable(GL_BLEND)
        glUseProgram(prog)
        glUniform2f(uRes, w.toFloat(), h.toFloat())
        glUniform1f(uT, time)
        glUniform1f(uE, energy)
        glUniform1f(uB, beat)
        glUniform1f(uD, dim)
        glUniform2fv(uP, 5, pos, 0)
        glUniform3fv(uC, 5, col, 0)
        glUniform2f(uArt, artX * asp, artY)
        glUniform3fv(uGlow, 1, glow, 0)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, quad)
        glDrawArrays(GL_TRIANGLES, 0, 3)

        if (motes > 0.01f) drawMotes(dt)
        else glDisableVertexAttribArray(1)
    }

    private fun drawMotes(dt: Float) {
        val sp = dt * (0.012f + energy * 0.05f + beat * 0.05f)
        for (i in 0 until moteCount) {
            val z = moteData[i * 4 + 2]
            var y = moteData[i * 4 + 1] + sp * z * 1.4f
            var x = moteData[i * 4] + sin(time * 0.6f + moteSeed[i]) * dt * 0.012f
            if (y > 1.05f) { y = -0.05f; x = Random.nextFloat() }
            moteData[i * 4] = x
            moteData[i * 4 + 1] = y
            moteData[i * 4 + 3] = motes * z * (0.16f + 0.14f * sin(time * 1.3f + moteSeed[i]) + beat * 0.34f)
        }
        moteBuf.position(0); moteBuf.put(moteData); moteBuf.position(0)
        glEnable(GL_BLEND)
        glUseProgram(moteProg)
        glUniform2f(uMRes, w.toFloat(), h.toFloat())
        glUniform1f(uMB, beat)
        glUniform3f(uMCol, min(1f, glow[0] + .35f), min(1f, glow[1] + .35f), min(1f, glow[2] + .35f))
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 16, moteBuf.position(0))
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 1, GL_FLOAT, false, 16, moteBuf.position(3))
        glDrawArrays(GL_POINTS, 0, moteCount)
        glDisableVertexAttribArray(1)
        moteBuf.position(0)
    }

    private fun link(v: String, f: String): Int {
        val p = glCreateProgram()
        glAttachShader(p, compile(GL_VERTEX_SHADER, v))
        glAttachShader(p, compile(GL_FRAGMENT_SHADER, f))
        glBindAttribLocation(p, 0, "aP")
        glBindAttribLocation(p, 1, "aA")
        glLinkProgram(p)
        val ok = IntArray(1)
        glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("Afterglow", "link: " + glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = glCreateShader(type)
        glShaderSource(s, src)
        glCompileShader(s)
        val ok = IntArray(1)
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("Afterglow", "shader: " + glGetShaderInfoLog(s))
        return s
    }

    private companion object {
        const val VERT = "attribute vec2 aP; varying vec2 v; void main(){ v=aP*.5+.5; gl_Position=vec4(aP,0.,1.); }"
        const val FRAG = """
precision highp float;
varying vec2 v;
uniform vec2 uRes; uniform float uT,uE,uB,uD;
uniform vec2 uP[5]; uniform vec3 uC[5]; uniform vec2 uArt; uniform vec3 uGlow;
float hash(vec2 p){ vec3 q=fract(vec3(p.xyx)*.1031); q+=dot(q,q.yzx+33.33); return fract((q.x+q.y)*q.z); }
void main(){
  vec2 p=gl_FragCoord.xy/uRes.y;
  float t=uT;
  vec2 w=p+.10*vec2(sin(p.y*3.1+t*.47)+.6*sin(p.y*5.7-t*.31+p.x*2.2),
                    cos(p.x*2.6-t*.37)+.6*cos(p.x*4.9+t*.53+p.y*1.8));
  vec3 c=vec3(0.); float s=0.;
  for(int i=0;i<5;i++){ vec2 d=w-uP[i]; float g=1./(.018+dot(d,d)); g*=sqrt(g); c+=uC[i]*g; s+=g; }
  c/=s;
  c*=.80+uE*.28+uB*.10;
  vec2 a=p-uArt; float r=dot(a,a);
  c+=uGlow*(.13+.20*uB+.20*uE)*exp(-r*4.2);
  vec2 q=v-.5; q.x*=1.15;
  c*=1.-dot(q,q)*1.15;
  c*=uD;
  c+=(hash(gl_FragCoord.xy+fract(t*7.)*91.)-.5)*.018;
  gl_FragColor=vec4(c,1.);
}"""
        const val MOTE_V = """
attribute vec3 aP; attribute float aA;
uniform vec2 uRes; uniform float uB; varying float a;
void main(){ a=aA; gl_PointSize=(3.+aP.z*aP.z*11.)*(1.+uB*.35)*uRes.y/800.;
  gl_Position=vec4(aP.xy*2.-1.,0.,1.); }"""
        const val MOTE_F = """
precision mediump float; varying float a; uniform vec3 uCol;
void main(){ vec2 d=gl_PointCoord-.5; float f=max(0.,1.-dot(d,d)*4.); gl_FragColor=vec4(uCol,a*f*f); }"""
    }
}
