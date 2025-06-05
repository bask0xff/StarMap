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
    private var finalRotationMatrix = FloatArray(16)
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
        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private var program: Int = 0
        private val starCount = 100
        private val starPositions = FloatArray(starCount * 3)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.0f, 0.0f, 0.2f, 1.0f)
            checkGLError("ClearColor")

            generateStars()
            starBuffer = ByteBuffer.allocateDirect(starPositions.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(starPositions)
                    position(0)
                }

            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            checkGLError("VertexShaderCompile")
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
            checkGLError("FragmentShaderCompile")

            program = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vertexShader)
                GLES20.glAttachShader(it, fragmentShader)
                GLES20.glLinkProgram(it)
            }
            checkGLError("ProgramLink")
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            checkGLError("Clear")

            GLES20.glUseProgram(program)
            checkGLError("UseProgram")

            updateOrientation()
            val modelMatrix = FloatArray(16)
            Matrix.setIdentityM(modelMatrix, 0)
            val mvpMatrix = FloatArray(16)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, finalRotationMatrix, 0)

            val mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
            checkGLError("SetMatrix")

            val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            if (positionHandle == -1) {
                return
            }
            GLES20.glEnableVertexAttribArray(positionHandle)
            checkGLError("EnableVertexAttrib")
            starBuffer.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, starBuffer)
            checkGLError("VertexAttribPointer")
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, starCount)
            checkGLError("DrawArrays")
            GLES20.glDisableVertexAttribArray(positionHandle)
            checkGLError("DisableVertexAttrib")
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

    private fun updateOrientation() {
        val success = SensorManager.getRotationMatrix(rotationMatrix, null, smoothedAccelerometer, smoothedMagnetometer)
        if (success) {
            // Ремаппинг: X -> X, Y -> Z, Z -> -Y
            SensorManager.remapCoordinateSystem(
                rotationMatrix,
                SensorManager.AXIS_X, SensorManager.AXIS_Z,
                remappedRotationMatrix
            )
            // Компенсация портретной ориентации (поворот на 90° вокруг Z)
            Matrix.setIdentityM(finalRotationMatrix, 0)
            Matrix.rotateM(finalRotationMatrix, 0, 90f, 0f, 0f, 1f)
            Matrix.multiplyMM(finalRotationMatrix, 0, finalRotationMatrix, 0, remappedRotationMatrix, 0)

            val angles = FloatArray(3)
            SensorManager.getOrientation(finalRotationMatrix, angles)
            val yaw = angles[0] * 180f / Math.PI.toFloat()
            val pitch = angles[1] * 180f / Math.PI.toFloat()
            val roll = angles[2] * 180f / Math.PI.toFloat()
            if (isAnglesChanged(angles, lastAngles, 2.0f)) {
                lastAngles = angles.copyOf()
                Log.d("StarMap", "Orientation: Yaw=$yaw, Pitch=$pitch, Roll=$roll")
            }
        } else {
            Matrix.setIdentityM(finalRotationMatrix, 0)
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

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        uniform mat4 uMVPMatrix;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            gl_PointSize = 10.0;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        void main() {
            gl_FragColor = vec4(1.0, 1.0, 1.0, 1.0);
        }
    """.trimIndent()
}