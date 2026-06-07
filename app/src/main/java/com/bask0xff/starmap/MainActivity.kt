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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

// Данные звёзд: name, RA(hours), Dec(degrees), magnitude
private val brightStars = listOf(
    // Самые яркие звёзды (примерно топ-60)
    Star("Sirius", 6.7525f, -16.7161f, -1.46f),
    Star("Canopus", 6.3992f, -52.6956f, -0.74f),
    Star("Alpha Centauri", 14.6600f, -60.8333f, -0.27f),
    Star("Arcturus", 14.2610f, 19.1822f, -0.05f),
    Star("Vega", 18.6167f, 38.7833f, 0.03f),
    Star("Capella", 5.2783f, 45.9981f, 0.08f),
    Star("Rigel", 5.2422f, -8.2017f, 0.13f),
    Star("Procyon", 7.6553f, 5.2250f, 0.34f),
    Star("Achernar", 1.6283f, -57.2367f, 0.46f),
    Star("Betelgeuse", 5.9194f, 7.4072f, 0.50f),
    Star("Hadar", 14.0639f, -60.3722f, 0.61f),
    Star("Altair", 19.7933f, 8.8683f, 0.77f),
    Star("Aldebaran", 4.5986f, 16.5092f, 0.85f),
    Star("Spica", 13.4197f, -11.1614f, 0.98f),
    Star("Antares", 16.4903f, -26.4319f, 1.06f),
    Star("Pollux", 7.7553f, 28.0261f, 1.14f),
    Star("Fomalhaut", 22.9608f, -29.6217f, 1.16f),
    Star("Deneb", 20.6906f, 45.2803f, 1.25f),
    Star("Mimosa", 12.7950f, -59.6889f, 1.25f),
    Star("Regulus", 10.1394f, 11.9672f, 1.35f),
    // Добавь ещё звёзд по желанию (можно расширить список)
    Star("Adhara", 6.9778f, -28.9722f, 1.50f),
    Star("Castor", 7.5767f, 31.8886f, 1.58f),
    Star("Shaula", 17.5603f, -37.1042f, 1.62f),
    Star("Bellatrix", 5.4194f, 6.3497f, 1.64f),
    Star("Elnath", 5.4383f, 28.6061f, 1.65f),
    Star("Alnilam", 5.6039f, -1.2017f, 1.69f),
    Star("Alnitak", 5.6792f, -1.9428f, 1.77f),
    Star("Alphard", 9.4597f, -8.6583f, 1.98f),
    Star("Dubhe", 11.0619f, 61.7511f, 1.79f),
    Star("Mirzam", 6.3831f, -17.9558f, 1.98f),
    Star("Acrux", 12.4431f, -63.0989f, 0.77f), // Alpha Crucis
)

data class Star(val name: String, val raHours: Float, val decDeg: Float, val mag: Float)

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var smoothedAccelerometer = FloatArray(3)
    private var smoothedMagnetometer = FloatArray(3)

    private var rotationMatrix = FloatArray(16)
    private var remappedRotationMatrix = FloatArray(16)
    private var invertedRotationMatrix = FloatArray(16)

    private var lastAngles = FloatArray(3)
    private val alpha = 0.25f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        setContent { StarMapScreen() }
    }

    @Composable
    fun StarMapScreen() {
        val glSurfaceView = remember { GLSurfaceView(this) }
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(StarRenderer())
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        AndroidView(factory = { glSurfaceView }, modifier = Modifier.fillMaxSize())
    }

    inner class StarRenderer : GLSurfaceView.Renderer {
        private lateinit var starBuffer: FloatBuffer
        private lateinit var axesBuffer: FloatBuffer
        private lateinit var axesColorBuffer: FloatBuffer
        private lateinit var sensorAxesBuffer: FloatBuffer
        private lateinit var sensorAxesColorBuffer: FloatBuffer

        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)

        private var starProgram: Int = 0
        private var axesProgram: Int = 0

        private val starPositions = mutableListOf<Float>()
        private val starSizes = mutableListOf<Float>() // для разного размера точек

        private val axesVertices = floatArrayOf(
            0f, 0f, 0f, 1.2f, 0f, 0f,
            0f, 0f, 0f, 0f, 1.2f, 0f,
            0f, 0f, 0f, 0f, 0f, 1.2f
        )

        private val axesColors = floatArrayOf(
            1f,0f,0f,1f, 1f,0f,0f,1f,
            0f,1f,0f,1f, 0f,1f,0f,1f,
            0f,0f,1f,1f, 0f,0f,1f,1f
        )

        private val sensorAxesVertices = floatArrayOf(
            0f,0f,0f, -0.9f,0f,0f,
            0f,0f,0f, 0f,-0.9f,0f,
            0f,0f,0f, 0f,0f,-0.9f
        )

        private val sensorAxesColors = floatArrayOf(
            1f,0f,1f,1f, 1f,0f,1f,1f,
            0f,1f,1f,1f, 0f,1f,1f,1f,
            1f,1f,0f,1f, 1f,1f,0f,1f
        )

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.0f, 0.0f, 0.15f, 1.0f)
            generateRealStars()
            starBuffer = createFloatBuffer(starPositions.toFloatArray())

            axesBuffer = createFloatBuffer(axesVertices)
            axesColorBuffer = createFloatBuffer(axesColors)
            sensorAxesBuffer = createFloatBuffer(sensorAxesVertices)
            sensorAxesColorBuffer = createFloatBuffer(sensorAxesColors)

            starProgram = createProgram(starVertexShaderCode, starFragmentShaderCode)
            axesProgram = createProgram(axesVertexShaderCode, axesFragmentShaderCode)
        }

        private fun generateRealStars() {
            starPositions.clear()
            starSizes.clear()

            for (star in brightStars) {
                val raRad = star.raHours * 15f * (PI.toFloat() / 180f)
                val decRad = star.decDeg * (PI.toFloat() / 180f)

                val x = cos(decRad) * cos(raRad)
                val y = cos(decRad) * sin(raRad)
                val z = sin(decRad)

                val distance = 8f
                starPositions.add(x * distance)
                starPositions.add(y * distance)
                starPositions.add(z * distance)

                // Размер точки обратно пропорционален магнитуде (меньше mag = ярче)
                val size = (6f - star.mag * 1.8f).coerceIn(3f, 12f)
                starSizes.add(size)
            }
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

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val ratio = width.toFloat() / height
            Matrix.perspectiveM(projectionMatrix, 0, 50f, ratio, 0.1f, 100f)
            Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, -8f, 0f, 0f, 0f, 0f, 1f, 0f)
        }

        private fun drawStars(mvpMatrix: FloatArray) {
            GLES20.glUseProgram(starProgram)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(starProgram, "uMVPMatrix"), 1, false, mvpMatrix, 0)

            val posHandle = GLES20.glGetAttribLocation(starProgram, "aPosition")
            GLES20.glEnableVertexAttribArray(posHandle)
            starBuffer.position(0)
            GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 12, starBuffer)

            // Рисуем точки по одной, чтобы задать разный размер
            for (i in brightStars.indices) {
                GLES20.glPointSize(starSizes[i])
                GLES20.glDrawArrays(GLES20.GL_POINTS, i, 1)
            }
            GLES20.glDisableVertexAttribArray(posHandle)
        }

        private fun drawAxes(mvp: FloatArray, vertexBuffer: FloatBuffer, colorBuffer: FloatBuffer, lineWidth: Float) {
            GLES20.glUseProgram(axesProgram)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(axesProgram, "uMVPMatrix"), 1, false, mvp, 0)

            val posHandle = GLES20.glGetAttribLocation(axesProgram, "aPosition")
            val colorHandle = GLES20.glGetAttribLocation(axesProgram, "aColor")

            GLES20.glEnableVertexAttribArray(posHandle)
            GLES20.glEnableVertexAttribArray(colorHandle)

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            colorBuffer.position(0)
            GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 0, colorBuffer)

            GLES20.glLineWidth(lineWidth)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, 6)

            GLES20.glDisableVertexAttribArray(posHandle)
            GLES20.glDisableVertexAttribArray(colorHandle)
        }
    }

    private fun updateOrientation() {
        val success = SensorManager.getRotationMatrix(rotationMatrix, null, smoothedAccelerometer, smoothedMagnetometer)

        if (!success) {
            Matrix.setIdentityM(invertedRotationMatrix, 0)
            return
        }

        val rotation = windowManager.defaultDisplay.rotation

        when (rotation) {
            Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Y, remappedRotationMatrix)
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedRotationMatrix)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remappedRotationMatrix)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remappedRotationMatrix)
            else -> Matrix.setIdentityM(remappedRotationMatrix, 0)
        }

        // Инверсия — часто решает проблему переворота
        Matrix.invertM(invertedRotationMatrix, 0, remappedRotationMatrix, 0)

        val angles = FloatArray(3)
        SensorManager.getOrientation(invertedRotationMatrix, angles)

        val yaw = Math.toDegrees(angles[0].toDouble()).toFloat()
        val pitch = Math.toDegrees(angles[1].toDouble()).toFloat()
        val roll = Math.toDegrees(angles[2].toDouble()).toFloat()

        if (isAnglesChanged(angles, lastAngles, 4.0f)) {
            lastAngles = angles.copyOf()
            Log.d("StarMap", "Yaw=${yaw.toInt()}° Pitch=${pitch.toInt()}° Roll=${roll.toInt()}° | Rot=$rotation")
        }
    }

    private fun isAnglesChanged(a1: FloatArray, a2: FloatArray, thresholdDeg: Float): Boolean {
        val threshold = Math.toRadians(thresholdDeg.toDouble()).toFloat()
        return (0 until a1.size).any { Math.abs(a1[it] - a2[it]) > threshold }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val newValues = it.values.clone()
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    for (i in newValues.indices) smoothedAccelerometer[i] = alpha * newValues[i] + (1 - alpha) * smoothedAccelerometer[i]
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    for (i in newValues.indices) smoothedMagnetometer[i] = alpha * newValues[i] + (1 - alpha) * smoothedMagnetometer[i]
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onResume() { /* ... register listeners */ }
    override fun onPause() { sensorManager.unregisterListener(this) }

    // Остальные вспомогательные функции (createFloatBuffer, createProgram, loadShader, шейдеры) — оставь как в предыдущей версии
    private fun createFloatBuffer(array: FloatArray): FloatBuffer = /* ... */
        private fun createProgram(vertex: String, fragment: String): Int = /* ... */
        private fun loadShader(type: Int, code: String): Int = /* ... */

        private val starVertexShaderCode = """
        attribute vec4 aPosition;
        uniform mat4 uMVPMatrix;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            gl_PointSize = 8.0;  // будет переопределяться в drawStars
        }
    """.trimIndent()

    private val starFragmentShaderCode = """
        precision mediump float;
        void main() {
            gl_FragColor = vec4(1.0, 1.0, 0.9, 1.0);
        }
    """.trimIndent()

    private val axesVertexShaderCode = """ /* как раньше */ """.trimIndent()
    private val axesFragmentShaderCode = """ /* как раньше */ """.trimIndent()
}