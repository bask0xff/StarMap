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
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.sp

data class Star(val x: Float, val y: Float, val z: Float, val size: Float)

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager

    private var smoothedAccelerometer = FloatArray(3)
    private var smoothedMagnetometer = FloatArray(3)

    private var rotationMatrix = FloatArray(16)
    private var remappedRotationMatrix = FloatArray(16)
    private var invertedRotationMatrix = FloatArray(16)

    private var lastAngles = FloatArray(3)
    private val alpha = 0.28f

    private val starList = mutableListOf<Star>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        loadStarsFromAssets()
        setContent { StarMapScreen() }
    }

    /** https://grok.com/c/1d895243-475a-4bfe-bd83-d2bbd5854413?rid=be0c9988-f541-42b0-93e2-ab1c28b66aa1 - sergongamer
     * Рекомендуемые значения фильтра:
     * mag >Примерное количество звёзд
     * Рекомендация
     * 4.5f~300–400О чень чистое небо
     * 4.8f~500–600 Хорошо (рекомендую)
     * 5.2f~900–1100 Средне
     * 6.0f3000+ Слишком много
     * Начни с 4.8f — это хороший баланс.
     */
    // ==================== СПИСОК ИЗВЕСТНЫХ ЗВЁЗД ====================
    private val namedStars = mapOf(
        "Сириус" to Triple(6.7525f, -16.7161f, -1.46f),
        "Канопус" to Triple(6.3992f, -52.6956f, -0.74f),
        "Арктур" to Triple(14.2610f, 19.1822f, -0.05f),
        "Вега" to Triple(18.6167f, 38.7833f, 0.03f),
        "Капелла" to Triple(5.2783f, 45.9981f, 0.08f),
        "Ригель" to Triple(5.2422f, -8.2017f, 0.13f),
        "Процион" to Triple(7.6553f, 5.2250f, 0.34f),
        "Бетельгейзе" to Triple(5.9194f, 7.4072f, 0.50f),
        "Ахернар" to Triple(1.6283f, -57.2367f, 0.46f),
        "Альтаир" to Triple(19.7933f, 8.8683f, 0.77f),
        "Альдебаран" to Triple(4.5986f, 16.5092f, 0.85f),
        "Спика" to Triple(13.4197f, -11.1614f, 0.98f),
        "Антарес" to Triple(16.4903f, -26.4319f, 1.06f),
        "Денеб" to Triple(20.6906f, 45.2803f, 1.25f),
        "Регул" to Triple(10.1394f, 11.9672f, 1.35f),
    )

    private fun loadStarsFromAssets() {
        try {
            val inputStream = assets.open("stars_bright.csv")
            val reader = BufferedReader(InputStreamReader(inputStream))
            reader.readLine() // заголовок

            starList.clear()
            var count = 0

            reader.forEachLine { line ->
                val parts = line.split(",")
                if (parts.size < 11) return@forEachLine

                try {
                    val ra = parts[7].toFloatOrNull() ?: return@forEachLine
                    val dec = parts[8].toFloatOrNull() ?: return@forEachLine
                    val mag = parts[10].toFloatOrNull() ?: 6f

                    // === ЖЁСТКИЙ ФИЛЬТР — только самые яркие звёзды ===
                    if (mag > 2.8f) return@forEachLine   // ← уменьшил до 2.8

                    val raRad = ra * 15f * (PI.toFloat() / 180f)
                    val decRad = dec * (PI.toFloat() / 180f)

                    val x = cos(decRad) * cos(raRad)
                    val y = cos(decRad) * sin(raRad)
                    val z = sin(decRad)

                    val distance = 9.8f
                    val size = when {
                        mag < 0.5 -> 13.5f
                        mag < 1.5 -> 10f
                        else -> 7f
                    }

                    starList.add(Star(x * distance, y * distance, z * distance, size))
                    count++
                } catch (_: Exception) {}
            }

            Log.d("StarMap", "Загружено $count очень ярких звёзд (mag ≤ 2.8)")

            addNamedStars()

        } catch (e: Exception) {
            Log.e("StarMap", "Ошибка загрузки CSV", e)
            generateFallbackStars()
        }
    }

    private fun addNamedStars() {
        namedStars.forEach { (name, data) ->
            val (raHours, decDeg, mag) = data

            val raRad = raHours * 15f * (PI.toFloat() / 180f)
            val decRad = decDeg * (PI.toFloat() / 180f)

            val x = cos(decRad) * cos(raRad)
            val y = cos(decRad) * sin(raRad)
            val z = sin(decRad)

            val distance = 9.8f
            val size = if (mag < 1.0) 14f else 11f

            starList.add(Star(x * distance, y * distance, z * distance, size))
        }
        Log.d("StarMap", "Добавлено ${namedStars.size} именованных звёзд")
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

    @Composable
    fun StarMapScreen() {
        val glSurfaceView = remember { GLSurfaceView(this) }
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(StarRenderer())
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        Box(modifier = Modifier.fillMaxSize()) {
            // OpenGL фон
            AndroidView(
                factory = { glSurfaceView },
                modifier = Modifier.fillMaxSize()
            )

            // Названия самых ярких звёзд (пока статично)
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) {
                namedStars.keys.take(12).forEach { name ->
                    Text(
                        text = name,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    inner class StarRenderer : GLSurfaceView.Renderer {
        private lateinit var starPositionBuffer: FloatBuffer
        private lateinit var starSizeBuffer: FloatBuffer

        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)

        private var starProgram: Int = 0
        private var axesProgram: Int = 0

        private var positionHandle = 0
        private var sizeHandle = 0
        private var mvpHandle = 0

        private lateinit var axesBuffer: FloatBuffer
        private lateinit var axesColorBuffer: FloatBuffer
        private lateinit var sensorAxesBuffer: FloatBuffer
        private lateinit var sensorAxesColorBuffer: FloatBuffer

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.005f, 0.005f, 0.025f, 1.0f)
            prepareStarBuffers()

            starProgram = createProgram(starVertexShaderCode, starFragmentShaderCode)
            axesProgram = createProgram(axesVertexShaderCode, axesFragmentShaderCode)

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

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            updateOrientation()

            val mvpMatrix = FloatArray(16)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, invertedRotationMatrix, 0)

            drawStars(mvpMatrix)
            drawAxes(mvpMatrix, axesBuffer, axesColorBuffer, 5f)

            val sensorMvp = FloatArray(16).apply {
                Matrix.multiplyMM(this, 0, projectionMatrix, 0, viewMatrix, 0)
                Matrix.multiplyMM(this, 0, this, 0, invertedRotationMatrix, 0)
            }
            drawAxes(sensorMvp, sensorAxesBuffer, sensorAxesColorBuffer, 3f)
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

    // ←←← ИСПРАВЛЕНО ЗДЕСЬ
    override fun onResume() {
        super.onResume()                    // ← обязательно!
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()                     // ← обязательно!
        sensorManager.unregisterListener(this)
    }

    // ==================== Шейдеры ====================
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
}