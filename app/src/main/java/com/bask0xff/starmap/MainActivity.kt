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
import kotlin.random.Random

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometerReading = FloatArray(3)
    private var magnetometerReading = FloatArray(3)
    private var smoothedAccelerometer = FloatArray(3)
    private var smoothedMagnetometer = FloatArray(3)

    private var rotationMatrix = FloatArray(16)
    private var remappedRotationMatrix = FloatArray(16)

    private var lastAngles = FloatArray(3)
    private val alpha = 0.25f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        setContent {
            StarMapScreen()
        }
    }

    @Composable
    fun StarMapScreen() {
        val glSurfaceView = remember { GLSurfaceView(this) }
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(StarRenderer())
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        AndroidView(
            factory = { glSurfaceView },
            modifier = Modifier.fillMaxSize()
        )
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

        private val starCount = 100
        private val starPositions = FloatArray(starCount * 3)

        private val axesVertices = floatArrayOf(
            0f, 0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 0f, 1f, 0f,
            0f, 0f, 0f, 0f, 0f, 1f
        )

        private val axesColors = floatArrayOf(
            1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f,
            0f, 1f, 0f, 1f, 0f, 1f, 0f, 1f,
            0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f
        )

        private val sensorAxesVertices = floatArrayOf(
            0f, 0f, 0f, -0.8f, 0f, 0f,
            0f, 0f, 0f, 0f, -0.8f, 0f,
            0f, 0f, 0f, 0f, 0f, -0.8f
        )

        private val sensorAxesColors = floatArrayOf(
            1f, 0f, 1f, 1f, 1f, 0f, 1f, 1f,
            0f, 1f, 1f, 1f, 0f, 1f, 1f, 1f,
            1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f
        )

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.0f, 0.0f, 0.2f, 1.0f)

            generateStars()
            starBuffer = createFloatBuffer(starPositions)
            axesBuffer = createFloatBuffer(axesVertices)
            axesColorBuffer = createFloatBuffer(axesColors)
            sensorAxesBuffer = createFloatBuffer(sensorAxesVertices)
            sensorAxesColorBuffer = createFloatBuffer(sensorAxesColors)

            starProgram = createProgram(starVertexShaderCode, starFragmentShaderCode)
            axesProgram = createProgram(axesVertexShaderCode, axesFragmentShaderCode)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            updateOrientation()

            val mvpMatrix = FloatArray(16)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, remappedRotationMatrix, 0)

            drawStars(mvpMatrix)
            drawAxes(mvpMatrix, axesBuffer, axesColorBuffer, 5f)

            val sensorMvp = FloatArray(16).apply {
                Matrix.multiplyMM(this, 0, projectionMatrix, 0, viewMatrix, 0)
                Matrix.multiplyMM(this, 0, this, 0, remappedRotationMatrix, 0)
            }
            drawAxes(sensorMvp, sensorAxesBuffer, sensorAxesColorBuffer, 3f)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            val ratio = width.toFloat() / height
            Matrix.perspectiveM(projectionMatrix, 0, 60f, ratio, 0.1f, 100f)
            Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, -5f, 0f, 0f, 0f, 0f, 1f, 0f)
        }

        private fun generateStars() {
            for (i in 0 until starCount) {
                val theta = Random.nextFloat() * 2f * PI.toFloat()   // ← исправлено
                val phi = acos((2 * Random.nextFloat() - 1).toDouble()).toFloat()
                val r = 5f

                starPositions[i * 3] = r * sin(phi) * cos(theta)
                starPositions[i * 3 + 1] = r * sin(phi) * sin(theta)
                starPositions[i * 3 + 2] = r * cos(phi)
            }
        }

        private fun createFloatBuffer(array: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(array.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(array)
                    position(0)
                }

        private fun createProgram(vertexCode: String, fragmentCode: String): Int {
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)

            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vertexShader)
                GLES20.glAttachShader(it, fragmentShader)
                GLES20.glLinkProgram(it)
            }
        }

        private fun drawStars(mvpMatrix: FloatArray) {
            GLES20.glUseProgram(starProgram)
            GLES20.glUniformMatrix4fv(
                GLES20.glGetUniformLocation(starProgram, "uMVPMatrix"),
                1, false, mvpMatrix, 0
            )

            val posHandle = GLES20.glGetAttribLocation(starProgram, "aPosition")
            GLES20.glEnableVertexAttribArray(posHandle)
            starBuffer.position(0)
            GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 12, starBuffer)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, starCount)
            GLES20.glDisableVertexAttribArray(posHandle)
        }

        private fun drawAxes(
            mvp: FloatArray,
            vertexBuffer: FloatBuffer,
            colorBuffer: FloatBuffer,
            lineWidth: Float
        ) {
            GLES20.glUseProgram(axesProgram)
            GLES20.glUniformMatrix4fv(
                GLES20.glGetUniformLocation(axesProgram, "uMVPMatrix"),
                1, false, mvp, 0
            )

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
        val success = SensorManager.getRotationMatrix(
            rotationMatrix, null,
            smoothedAccelerometer, smoothedMagnetometer
        )

        if (!success) {
            Matrix.setIdentityM(remappedRotationMatrix, 0)
            return
        }

        val rotation = windowManager.defaultDisplay.rotation

        when (rotation) {
            Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(
                rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Y, remappedRotationMatrix
            )
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedRotationMatrix
            )
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                rotationMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remappedRotationMatrix
            )
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remappedRotationMatrix
            )
        }

        val angles = FloatArray(3)
        SensorManager.getOrientation(remappedRotationMatrix, angles)

        val yaw = Math.toDegrees(angles[0].toDouble()).toFloat()
        val pitch = Math.toDegrees(angles[1].toDouble()).toFloat()
        val roll = Math.toDegrees(angles[2].toDouble()).toFloat()

        if (isAnglesChanged(angles, lastAngles, 3.0f)) {
            lastAngles = angles.copyOf()
            Log.d(
                "StarMap",
                "Orientation: Yaw=${yaw.toInt()}°, Pitch=${pitch.toInt()}°, Roll=${roll.toInt()}° | Rot=$rotation"
            )
        }
    }

    private fun isAnglesChanged(a1: FloatArray, a2: FloatArray, thresholdDeg: Float): Boolean {
        val thresholdRad = Math.toRadians(thresholdDeg.toDouble()).toFloat()
        return (0 until a1.size).any { i -> Math.abs(a1[i] - a2[i]) > thresholdRad }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val newValues = it.values.clone()
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    for (i in newValues.indices) {
                        smoothedAccelerometer[i] = alpha * newValues[i] + (1 - alpha) * smoothedAccelerometer[i]
                    }
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    for (i in newValues.indices) {
                        smoothedMagnetometer[i] = alpha * newValues[i] + (1 - alpha) * smoothedMagnetometer[i]
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

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }

    private val starVertexShaderCode = """
        attribute vec4 aPosition;
        uniform mat4 uMVPMatrix;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            gl_PointSize = 8.0;
        }
    """.trimIndent()

    private val starFragmentShaderCode = """
        precision mediump float;
        void main() {
            gl_FragColor = vec4(1.0, 1.0, 1.0, 1.0);
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