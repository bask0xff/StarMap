package com.bask0xff.starmap

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.text.font.FontWeight

data class Star(val x: Float, val y: Float, val z: Float, val size: Float)
data class NamedStar(val name: String, val x: Float, val y: Float, val z: Float)
data class ScreenLabel(val name: String, val screenX: Float, val screenY: Float)
data class ConstellationLine(val x1: Float, val y1: Float, val z1: Float, val x2: Float, val y2: Float, val z2: Float)

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager

    private var smoothedAccelerometer = FloatArray(3)
    private var smoothedMagnetometer = FloatArray(3)

    private var rotationMatrix = FloatArray(16)
    private var remappedRotationMatrix = FloatArray(16)
    private var invertedRotationMatrix = FloatArray(16)

    private val alpha = 0.28f

    private val starList = mutableListOf<Star>()
    private val namedStarList = mutableListOf<NamedStar>()
    private val constellationLines = mutableListOf<ConstellationLine>()

    val screenLabels = mutableStateListOf<ScreenLabel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        loadStarsFromAssets()
        defineMajorConstellations()
        setContent { StarMapScreen() }
    }

    private fun loadStarsFromAssets() {
        try {
            val inputStream = assets.open("stars_bright.csv")
            val reader = BufferedReader(InputStreamReader(inputStream))
            reader.readLine()

            starList.clear()
            namedStarList.clear()
            var count = 0

            reader.forEachLine { line ->
                val parts = line.split(",")
                if (parts.size < 11) return@forEachLine

                try {
                    val ra = parts[7].toFloatOrNull() ?: return@forEachLine
                    val dec = parts[8].toFloatOrNull() ?: return@forEachLine
                    val mag = parts[10].toFloatOrNull() ?: 6f

                    if (mag > 2.5f) return@forEachLine

                    val raRad = ra * 15f * (PI.toFloat() / 180f)
                    val decRad = dec * (PI.toFloat() / 180f)

                    val x = cos(decRad) * cos(raRad)
                    val y = cos(decRad) * sin(raRad)
                    val z = sin(decRad)

                    val distance = 9.8f
                    val size = if (mag < 1.0) 12f else if (mag < 2.0) 9f else 6.5f

                    starList.add(Star(x * distance, y * distance, z * distance, size))
                    count++
                } catch (_: Exception) {}
            }

            Log.d("StarMap", "Загружено $count ярких звёзд")
            addImportantNamedStars()

        } catch (e: Exception) {
            Log.e("StarMap", "Ошибка загрузки CSV", e)
            generateFallbackStars()
        }
    }

    private fun addImportantNamedStars() {
        val important = listOf(
            "Сириус" to Triple(6.7525f, -16.7161f, -1.46f),
            "Вега" to Triple(18.6167f, 38.7833f, 0.03f),
            "Арктур" to Triple(14.2610f, 19.1822f, -0.05f),
            "Капелла" to Triple(5.2783f, 45.9981f, 0.08f),
            "Ригель" to Triple(5.2422f, -8.2017f, 0.13f),
            "Бетельгейзе" to Triple(5.9194f, 7.4072f, 0.50f),
            "Альдебаран" to Triple(4.5986f, 16.5092f, 0.85f),
            "Антарес" to Triple(16.4903f, -26.4319f, 1.06f),
            "Спика" to Triple(13.4197f, -11.1614f, 0.98f),
            "Денеб" to Triple(20.6906f, 45.2803f, 1.25f),
            "Альтаир" to Triple(19.7933f, 8.8683f, 0.77f),
            "Процион" to Triple(7.6553f, 5.2250f, 0.34f)
        )

        important.forEach { (name, data) ->
            val (raH, decD, _) = data
            val raRad = raH * 15f * (PI.toFloat() / 180f)
            val decRad = decD * (PI.toFloat() / 180f)

            val x = cos(decRad) * cos(raRad)
            val y = cos(decRad) * sin(raRad)
            val z = sin(decRad)

            val distance = 9.8f
            val star = Star(x * distance, y * distance, z * distance, 13f)
            starList.add(star)
            namedStarList.add(NamedStar(name, x * distance, y * distance, z * distance))
        }
    }

    private fun generateFallbackStars() {
        repeat(800) {
            val theta = Math.random().toFloat() * 2f * PI.toFloat()
            val phi = acos((2 * Math.random().toFloat() - 1)).toFloat()
            val r = 9f
            starList.add(Star(
                r * sin(phi) * cos(theta),
                r * sin(phi) * sin(theta),
                r * cos(phi),
                5f
            ))
        }
    }

    private fun defineMajorConstellations() {
        constellationLines.clear()

        fun addLine(ra1: Float, dec1: Float, ra2: Float, dec2: Float) {
            val raRad1 = ra1 * 15f * (PI.toFloat() / 180f)
            val decRad1 = dec1 * (PI.toFloat() / 180f)
            val raRad2 = ra2 * 15f * (PI.toFloat() / 180f)
            val decRad2 = dec2 * (PI.toFloat() / 180f)

            val x1 = cos(decRad1) * cos(raRad1) * 9.8f
            val y1 = cos(decRad1) * sin(raRad1) * 9.8f
            val z1 = sin(decRad1) * 9.8f

            val x2 = cos(decRad2) * cos(raRad2) * 9.8f
            val y2 = cos(decRad2) * sin(raRad2) * 9.8f
            val z2 = sin(decRad2) * 9.8f

            constellationLines.add(ConstellationLine(x1, y1, z1, x2, y2, z2))
        }

        // ====================== БОЛЬШАЯ МЕДВЕДИЦА ======================
        addLine(11.03f, 61.75f, 11.03f, 56.38f)   // Dubhe - Merak
        addLine(11.03f, 56.38f, 12.26f, 57.03f)   // Merak - Phecda
        addLine(12.26f, 57.03f, 12.90f, 55.96f)   // Phecda - Megrez
        addLine(12.90f, 55.96f, 13.79f, 49.31f)   // Megrez - Alioth
        addLine(13.79f, 49.31f, 13.40f, 54.93f)   // Alioth - Mizar
        addLine(13.40f, 54.93f, 13.79f, 49.31f)   // Mizar - Alkaid (дуга)
        addLine(13.79f, 49.31f, 13.42f, 49.87f)   // продолжение

        // ====================== МАЛАЯ МЕДВЕДИЦА ======================
        addLine(2.53f, 89.26f, 14.85f, 74.16f)   // Polaris - Kochab
        addLine(14.85f, 74.16f, 16.76f, 77.79f)   // Kochab - Pherkad

        // ====================== КАССИОПЕЯ ======================
        addLine(0.675f, 56.54f, 1.977f, 63.67f)   // Schedar - Caph
        addLine(1.977f, 63.67f, 3.792f, 63.67f)   // Caph - Gamma Cas
        addLine(3.792f, 63.67f, 5.433f, 60.72f)   // Gamma - Ruchbah
        addLine(5.433f, 60.72f, 0.675f, 56.54f)   // Ruchbah - Schedar (W)

        // ====================== ЛЕБЕДЬ (Cygnus) ======================
        addLine(20.69f, 45.28f, 19.85f, 40.68f)   // Deneb - Gienah
        addLine(19.85f, 40.68f, 20.37f, 36.39f)   // Gienah - Delta Cyg
        addLine(20.69f, 45.28f, 19.51f, 27.96f)   // Deneb - Albireo

        // ====================== ОРИОН ======================
        addLine(5.92f, 7.41f, 5.24f, -8.20f)     // Betelgeuse - Rigel
        addLine(5.60f, -1.20f, 5.24f, -8.20f)     // Bellatrix - Rigel
        addLine(5.60f, -1.20f, 5.92f, 7.41f)      // Bellatrix - Betelgeuse
        addLine(5.42f, -0.30f, 5.60f, -1.20f)     // Mintaka - Bellatrix

        // ====================== ЛЕВ ======================
        addLine(10.14f, 11.97f, 11.30f, 20.19f)   // Regulus - Algieba

        // ====================== ЛИРА ======================
        addLine(18.62f, 38.78f, 18.62f, 38.78f)   // Vega (центр)

        Log.d("StarMap", "Созвездия загружены: ${constellationLines.size} линий")
    }

    @Composable
    fun StarMapScreen() {
        val glSurfaceView = remember { GLSurfaceView(this) }
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(StarRenderer())
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { glSurfaceView },
                modifier = Modifier.fillMaxSize()
            )

            LabelsOverlay()
        }
    }

    @Composable
    fun LabelsOverlay() {
        Box(modifier = Modifier.fillMaxSize()) {
            screenLabels.forEach { label ->
                Text(
                    text = "★ ${label.name}",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .offset(x = label.screenX.dp, y = label.screenY.dp)
                        .padding(4.dp)
                )
            }
        }
    }

    inner class StarRenderer : GLSurfaceView.Renderer {

        private lateinit var starPositionBuffer: FloatBuffer
        private lateinit var starSizeBuffer: FloatBuffer
        private lateinit var constellationBuffer: FloatBuffer

        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val mvpMatrixForProjection = FloatArray(16)

        private var starProgram: Int = 0
        private var axesProgram: Int = 0
        private var lineProgram: Int = 0

        private var positionHandle = 0
        private var sizeHandle = 0
        private var mvpHandle = 0

        private lateinit var axesBuffer: FloatBuffer
        private lateinit var axesColorBuffer: FloatBuffer
        private lateinit var sensorAxesBuffer: FloatBuffer
        private lateinit var sensorAxesColorBuffer: FloatBuffer

        private var width = 0
        private var height = 0
        private val mainHandler = Handler(Looper.getMainLooper())

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.005f, 0.005f, 0.025f, 1.0f)
            prepareStarBuffers()
            prepareConstellationBuffer()

            starProgram = createProgram(starVertexShaderCode, starFragmentShaderCode)
            axesProgram = createProgram(axesVertexShaderCode, axesFragmentShaderCode)
            lineProgram = createProgram(lineVertexShaderCode, lineFragmentShaderCode)

            positionHandle = GLES20.glGetAttribLocation(starProgram, "aPosition")
            sizeHandle = GLES20.glGetAttribLocation(starProgram, "aSize")
            mvpHandle = GLES20.glGetUniformLocation(starProgram, "uMVPMatrix")
        }

        private fun prepareStarBuffers() {
            val positions = FloatArray(starList.size * 3)
            val sizes = FloatArray(starList.size)

            starList.forEachIndexed { i, star ->
                positions[i * 3] = star.x
                positions[i * 3 + 1] = star.y
                positions[i * 3 + 2] = star.z
                sizes[i] = star.size
            }

            starPositionBuffer = createFloatBuffer(positions)
            starSizeBuffer = createFloatBuffer(sizes)

            axesBuffer = createFloatBuffer(floatArrayOf(0f,0f,0f,1.3f,0f,0f, 0f,0f,0f,0f,1.3f,0f, 0f,0f,0f,0f,0f,1.3f))
            axesColorBuffer = createFloatBuffer(floatArrayOf(1f,0f,0f,1f,1f,0f,0f,1f, 0f,1f,0f,1f,0f,1f,0f,1f, 0f,0f,1f,1f,0f,0f,1f,1f))
            sensorAxesBuffer = createFloatBuffer(floatArrayOf(0f,0f,0f,-1f,0f,0f, 0f,0f,0f,0f,-1f,0f, 0f,0f,0f,0f,0f,-1f))
            sensorAxesColorBuffer = createFloatBuffer(floatArrayOf(1f,0f,1f,1f,1f,0f,1f,1f, 0f,1f,1f,1f,0f,1f,1f,1f, 1f,1f,0f,1f,1f,1f,0f,1f))
        }

        private fun prepareConstellationBuffer() {
            val vertices = mutableListOf<Float>()
            constellationLines.forEach { line ->
                vertices.add(line.x1); vertices.add(line.y1); vertices.add(line.z1)
                vertices.add(line.x2); vertices.add(line.y2); vertices.add(line.z2)
            }
            constellationBuffer = createFloatBuffer(vertices.toFloatArray())
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            updateOrientation()

            Matrix.multiplyMM(mvpMatrixForProjection, 0, projectionMatrix, 0, viewMatrix, 0)
            Matrix.multiplyMM(mvpMatrixForProjection, 0, mvpMatrixForProjection, 0, invertedRotationMatrix, 0)

            drawStars(mvpMatrixForProjection)
            drawConstellations(mvpMatrixForProjection)
            drawAxes(mvpMatrixForProjection, axesBuffer, axesColorBuffer, 5f)

            val sensorMvp = FloatArray(16).apply {
                Matrix.multiplyMM(this, 0, projectionMatrix, 0, viewMatrix, 0)
                Matrix.multiplyMM(this, 0, this, 0, invertedRotationMatrix, 0)
            }
            drawAxes(sensorMvp, sensorAxesBuffer, sensorAxesColorBuffer, 3f)

            updateScreenLabels()
        }

        private fun drawConstellations(mvp: FloatArray) {
            if (constellationLines.isEmpty()) return

            GLES20.glUseProgram(lineProgram)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix"), 1, false, mvp, 0)

            val posHandle = GLES20.glGetAttribLocation(lineProgram, "aPosition")
            GLES20.glEnableVertexAttribArray(posHandle)
            constellationBuffer.position(0)
            GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, constellationBuffer)

            GLES20.glLineWidth(2.5f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, constellationLines.size * 2)

            GLES20.glDisableVertexAttribArray(posHandle)
        }

        private fun drawStars(mvpMatrix: FloatArray) {
            GLES20.glUseProgram(starProgram)
            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glEnableVertexAttribArray(sizeHandle)

            starPositionBuffer.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, starPositionBuffer)

            starSizeBuffer.position(0)
            GLES20.glVertexAttribPointer(sizeHandle, 1, GLES20.GL_FLOAT, false, 4, starSizeBuffer)

            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, starList.size)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(sizeHandle)
        }

        private fun drawAxes(mvp: FloatArray, vertexBuffer: FloatBuffer, colorBuffer: FloatBuffer, lineWidth: Float) {
            GLES20.glUseProgram(axesProgram)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(axesProgram, "uMVPMatrix"), 1, false, mvp, 0)

            val pos = GLES20.glGetAttribLocation(axesProgram, "aPosition")
            val col = GLES20.glGetAttribLocation(axesProgram, "aColor")

            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glEnableVertexAttribArray(col)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            colorBuffer.position(0)
            GLES20.glVertexAttribPointer(col, 4, GLES20.GL_FLOAT, false, 0, colorBuffer)

            GLES20.glLineWidth(lineWidth)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, 6)

            GLES20.glDisableVertexAttribArray(pos)
            GLES20.glDisableVertexAttribArray(col)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            this.width = width
            this.height = height
            GLES20.glViewport(0, 0, width, height)
            val ratio = width.toFloat() / height
            Matrix.perspectiveM(projectionMatrix, 0, 55f, ratio, 0.1f, 100f)
            Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, -9.5f, 0f, 0f, 0f, 0f, 1f, 0f)
        }

        private fun createProgram(vertexCode: String, fragmentCode: String): Int {
            val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
            val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vs)
                GLES20.glAttachShader(it, fs)
                GLES20.glLinkProgram(it)
            }
        }

        private fun loadShader(type: Int, code: String): Int {
            return GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, code)
                GLES20.glCompileShader(it)
            }
        }

        private fun updateScreenLabels() {
            val tempLabels = mutableListOf<ScreenLabel>()
            val viewport = intArrayOf(0, 0, width, height)

            namedStarList.forEach { star ->
                val winPos = FloatArray(4)
                val objPos = floatArrayOf(star.x, star.y, star.z, 1f)

                Matrix.multiplyMV(winPos, 0, mvpMatrixForProjection, 0, objPos, 0)

                if (winPos[3] > 0.1f) {
                    val ndcX = winPos[0] / winPos[3]
                    val ndcY = winPos[1] / winPos[3]

                    val screenX = (ndcX * 0.5f + 0.5f) * viewport[2]
                    val screenY = (1.0f - (ndcY * 0.5f + 0.5f)) * viewport[3]

                    if (screenX in 0f..viewport[2].toFloat() && screenY in 0f..viewport[3].toFloat()) {
                        tempLabels.add(ScreenLabel(star.name, screenX, screenY - 25f))
                    }
                }
            }

            mainHandler.post {
                screenLabels.clear()
                screenLabels.addAll(tempLabels)
            }
        }
    }

    private fun createFloatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(data); position(0) }

    private fun updateOrientation() {
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, smoothedAccelerometer, smoothedMagnetometer)) {
            Matrix.setIdentityM(invertedRotationMatrix, 0)
            return
        }

        val rot = windowManager.defaultDisplay.rotation
        when (rot) {
            Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Y, remappedRotationMatrix)
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedRotationMatrix)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remappedRotationMatrix)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remappedRotationMatrix)
        }

        Matrix.invertM(invertedRotationMatrix, 0, remappedRotationMatrix, 0)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val values = it.values.clone()
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    for (i in values.indices) {
                        smoothedAccelerometer[i] = alpha * values[i] + (1 - alpha) * smoothedAccelerometer[i]
                    }
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    for (i in values.indices) {
                        smoothedMagnetometer[i] = alpha * values[i] + (1 - alpha) * smoothedMagnetometer[i]
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    // ==================== ШЕЙДЕРЫ ====================
    private val starVertexShaderCode = """
        attribute vec4 aPosition;
        attribute float aSize;
        uniform mat4 uMVPMatrix;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            gl_PointSize = aSize;
        }
    """.trimIndent()

    private val starFragmentShaderCode = """
        precision mediump float;
        void main() {
            gl_FragColor = vec4(1.0, 1.0, 0.95, 1.0);
        }
    """.trimIndent()

    private val axesVertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec4 aColor;
        uniform mat4 uMVPMatrix;
        varying vec4 vColor;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vColor = aColor;
        }
    """.trimIndent()

    private val axesFragmentShaderCode = """
        precision mediump float;
        varying vec4 vColor;
        void main() {
            gl_FragColor = vColor;
        }
    """.trimIndent()

    private val lineVertexShaderCode = """
        attribute vec4 aPosition;
        uniform mat4 uMVPMatrix;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
        }
    """.trimIndent()

    private val lineFragmentShaderCode = """
        precision mediump float;
        void main() {
            gl_FragColor = vec4(0.4, 0.8, 1.0, 0.85);
        }
    """.trimIndent()
}