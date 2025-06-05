package com.bask0xff.starmap

import android.content.Context
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
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
    private val alpha = 0.1f // Коэффициент сглаживания

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
            // OpenGL X-axis (red)
            0f, 0f, 0f, 1f, 0f, 0f,
            // OpenGL Y-axis (green)
            0f, 0f, 0f, 0f, 1f, 0f,
            // OpenGL Z-axis (blue)
            0f, 0f, 0f, 0f, 0f, 1f
        )
        private val axesColors = floatArrayOf(
            // OpenGL X-axis (red)
            1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f,
            // OpenGL Y-axis (green)
            0f, 1f, 0f, 1f, 0f, 1f, 0f, 1f,
            // OpenGL Z-axis (blue)
            0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f
        )
        private val sensorAxesVertices = floatArrayOf(
            // Sensor X-axis (pink)
            0f, 0f, 0f, -0.5f, 0f, 0f,
            // Sensor Y-axis (cyan)
            0f, 0f, 0f, 0f, -0.5f, 0f,
            // Sensor Z-axis (yellow)
            0f, 0f, 0f, 0f, 0f, -0.5f
        )
        private val sensorAxesColors = floatArrayOf(
            // Sensor X-axis (pink)
            1f, 0f, 1f, 1f, 1f, 0f, 1f, 1f,
            // Sensor Y-axis (cyan)
            0f, 1f, 1f, 1f, 0f, 1f, 1f, 1f,
            // Sensor Z-axis (yellow)
            1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f
        )

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.0f, 0.0f, 0.2f, 1.0f)
            checkGLError("ClearColor")

            // Stars
            generateStars()
            starBuffer = ByteBuffer.allocateDirect(starPositions.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(starPositions)
                    position(0)
                }

            // OpenGL axes
            axesBuffer = ByteBuffer.allocateDirect(axesVertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(axesVertices)
                    position(0)
                }
            axesColorBuffer = ByteBuffer.allocateDirect(axesColors.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(axesColors)
                    position(0)
                }

            // Sensor axes
            sensorAxesBuffer = ByteBuffer.allocateDirect(sensorAxesVertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(sensorAxesVertices)
                    position(0)
                }
            sensorAxesColorBuffer = ByteBuffer.allocateDirect(sensorAxesColors.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(sensorAxesColors)
                    position(0)
                }

            // Star program
            val starVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, starVertexShaderCode)
            val starFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, starFragmentShaderCode)
            starProgram = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, starVertexShader)
                GLES20.glAttachShader(it, starFragmentShader)
                GLES20.glLinkProgram(it)
            }
            checkGLError("StarProgramLink")

            // Axes program
            val axesVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, axesVertexShaderCode)
            val axesFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, axesFragmentShaderCode)
            axesProgram = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, axesVertexShader)
                GLES20.glAttachShader(it, axesFragmentShader)
                GLES20.glLinkProgram(it)
            }
            checkGLError("AxesProgramLink")
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            checkGLError("Clear")

            // Update orientation
            updateOrientation()
            val modelMatrix = FloatArray(16)
            Matrix.setIdentityM(modelMatrix, 0)
            val mvpMatrix = FloatArray(16)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, remappedRotationMatrix, 0)

            // Draw stars
            GLES20.glUseProgram(starProgram)
            checkGLError("UseStarProgram")
            val starMvpMatrixHandle = GLES20.glGetUniformLocation(starProgram, "uMVPMatrix")
            GLES20.glUniformMatrix4fv(starMvpMatrixHandle, 1, false, mvpMatrix, 0)
            checkGLError("StarSetMatrix")

            val starPositionHandle = GLES20.glGetAttribLocation(starProgram, "aPosition")
            GLES20.glEnableVertexAttribArray(starPositionHandle)
            checkGLError("StarEnableVertexAttrib")
            starBuffer.position(0)
            GLES20.glVertexAttribPointer(starPositionHandle, 3, GLES20.GL_FLOAT, false, 12, starBuffer)
            checkGLError("StarVertexAttribPointer")
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, starCount)
            checkGLError("StarDrawArrays")
            GLES20.glDisableVertexAttribArray(starPositionHandle)
            checkGLError("StarDisableVertexAttrib")

            // Draw OpenGL axes
            GLES20.glUseProgram(axesProgram)
            checkGLError("UseAxesProgram")
            val axesMvpMatrixHandle = GLES20.glGetUniformLocation(axesProgram, "uMVPMatrix")
            GLES20.glUniformMatrix4fv(axesMvpMatrixHandle, 1, false, mvpMatrix, 0)
            checkGLError("AxesSetMatrix")

            var axesPositionHandle = GLES20.glGetAttribLocation(axesProgram, "aPosition")
            var axesColorHandle = GLES20.glGetAttribLocation(axesProgram, "aColor")
            GLES20.glEnableVertexAttribArray(axesPositionHandle)
            GLES20.glEnableVertexAttribArray(axesColorHandle)
            checkGLError("AxesEnableVertexAttrib")

            axesBuffer.position(0)
            GLES20.glVertexAttribPointer(axesPositionHandle, 3, GLES20.GL_FLOAT, false, 0, axesBuffer)
            checkGLError("AxesVertexAttribPointer")
            axesColorBuffer.position(0)
            GLES20.glVertexAttribPointer(axesColorHandle, 4, GLES20.GL_FLOAT, false, 0, axesColorBuffer)
            checkGLError("AxesColorAttribPointer")

            GLES20.glLineWidth(5f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, 6)
            checkGLError("AxesDrawArrays")
            GLES20.glDisableVertexAttribArray(axesPositionHandle)
            GLES20.glDisableVertexAttribArray(axesColorHandle)
            checkGLError("AxesDisableVertexAttrib")

            // Draw sensor axes
            GLES20.glUseProgram(axesProgram)
            checkGLError("UseSensorAxesProgram")
            val sensorMvpMatrix = FloatArray(16)
            Matrix.multiplyMM(sensorMvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            Matrix.multiplyMM(sensorMvpMatrix, 0, sensorMvpMatrix, 0, rotationMatrix, 0)
            GLES20.glUniformMatrix4fv(axesMvpMatrixHandle, 1, false, sensorMvpMatrix, 0)
            checkGLError("SensorAxesSetMatrix")

            axesPositionHandle = GLES20.glGetAttribLocation(axesProgram, "aPosition")
            axesColorHandle = GLES20.glGetAttribLocation(axesProgram, "aColor")
            GLES20.glEnableVertexAttribArray(axesPositionHandle)
            GLES20.glEnableVertexAttribArray(axesColorHandle)
            checkGLError("SensorAxesEnableVertexAttrib")

            sensorAxesBuffer.position(0)
            GLES20.glVertexAttribPointer(axesPositionHandle, 3, GLES20.GL_FLOAT, false, 0, sensorAxesBuffer)
            checkGLError("SensorAxesVertexAttribPointer")
            sensorAxesColorBuffer.position(0)
            GLES20.glVertexAttribPointer(axesColorHandle, 4, GLES20.GL_FLOAT, false, 0, sensorAxesColorBuffer)
            checkGLError("AxesColorAttribPointer")

            GLES20.glLineWidth(3f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, 6)
            checkGLError("SensorAxesDrawArrays")
            GLES20.glDisableVertexAttribArray(axesPositionHandle)
            GLES20.glDisableVertexAttribArray(axesColorHandle)
            checkGLError("SensorAxesDisableVertexAttrib")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            checkGLError("Viewport")
            val ratio = width.toFloat() / height
            Matrix.perspectiveM(projectionMatrix, 0, 60f, ratio, 0.1f, 100f)
            Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, -5f, 0f, 0f, 0f, 0f, 1f, 0f)
        }

        private fun generateStars() {
            for (i in 0 until starCount) {
                val theta = Random.nextFloat() * 2 * Math.PI.toFloat()
                val phi = Math.acos((2 * Random.nextFloat() - 1).toDouble()).toFloat()
                val radius = 5f
                starPositions[i * 3] = radius * sin(phi) * cos(theta)
                starPositions[i * 3 + 1] = radius * sin(phi) * sin(theta)
                starPositions[i * 3 + 2] = radius * cos(phi)
            }
        }
    }

    // naklon
    private fun updateOrientation() {
        val success = SensorManager.getRotationMatrix(rotationMatrix, null, smoothedAccelerometer, smoothedMagnetometer)
        if (success) {
            Log.d("StarMap", "Raw Accel: x=${smoothedAccelerometer[0]}, y=${smoothedAccelerometer[1]}, z=${smoothedAccelerometer[2]}")
            Log.d("StarMap", "Raw Mag: x=${smoothedMagnetometer[0]}, y=${smoothedMagnetometer[1]}, z=${smoothedMagnetometer[2]}")
            // Проверка ориентации экрана
            val orientation = resources.configuration.orientation
            if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                // Portrait: X -> X, Y -> -Y
                SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_X, SensorManager.AXIS_MINUS_Y,
                    remappedRotationMatrix
                )
            } else {
                // Landscape: Y -> X, X -> Y (для левого поворота)
                SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_Y, SensorManager.AXIS_X,
                    remappedRotationMatrix
                )
            }
            val angles = FloatArray(3)
            SensorManager.getOrientation(remappedRotationMatrix, angles)
            val yaw = angles[0] * 180f / Math.PI.toFloat()
            val pitch = angles[1] * 180f / Math.PI.toFloat()
            val roll = angles[2] * 180f / Math.PI.toFloat()
            if (isAnglesChanged(angles, lastAngles, 2.0f)) {
                lastAngles = angles.copyOf()
                Log.d("StarMap", "Orientation: Yaw=$yaw, Pitch=$pitch, Roll=$roll, Screen=${if (orientation == Configuration.ORIENTATION_PORTRAIT) "Portrait" else "Landscape"}")
            }
        } else {
            Matrix.setIdentityM(remappedRotationMatrix, 0)
        }
    }

    private fun isAnglesChanged(a1: FloatArray, a2: FloatArray, threshold: Float): Boolean {
        return (0 until a1.size).any { i -> Math.abs(a1[i] - a2[i]) > threshold / 180f * Math.PI.toFloat() }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val newValues = event.values.clone()
                for (i in newValues.indices) {
                    smoothedAccelerometer[i] = alpha * newValues[i] + (1 - alpha) * smoothedAccelerometer[i]
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val newValues = event.values.clone()
                for (i in newValues.indices) {
                    smoothedMagnetometer[i] = alpha * newValues[i] + (1 - alpha) * smoothedMagnetometer[i]
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
            Log.w("StarMap", "Sensor accuracy low: ${sensor?.name}, accuracy: $accuracy")
        }
    }

    override fun onResume() {
        super.onResume()
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        }
        if (magnetometer != null) {
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME)
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
            checkGLError("ShaderCompile")
        }
    }

    private fun checkGLError(operation: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e("StarMap", "$operation: GL Error $error")
        }
    }

    private val starVertexShaderCode = """
        attribute vec4 aPosition;
        uniform mat4 uMVPMatrix;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            gl_PointSize = 10.0;
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