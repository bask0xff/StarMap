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
    private var rotationMatrix = FloatArray(16)
    private var lastQuaternion = FloatArray(4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        Log.d("StarMap", "Accelerometer available: ${accelerometer != null}")
        Log.d("StarMap", "Magnetometer available: ${magnetometer != null}")
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
            Log.d("StarMap", "Surface created")
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
            Log.d("StarMap", "Stars generated: ${starPositions.size / 3} stars")

            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            checkGLError("VertexShaderCompile")
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
            checkGLError("FragmentShaderCompile")

            program = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vertexShader)
                GLES20.glAttachShader(it, fragmentShader)
                GLES20.glLinkProgram(it)
                val linkStatus = IntArray(1)
                GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, linkStatus, 0)
                if (linkStatus[0] == 0) {
                    Log.e("StarMap", "Link error: ${GLES20.glGetProgramInfoLog(it)}")
                } else {
                    Log.d("StarMap", "Program linked successfully")
                }
            }
            checkGLError("ProgramLink")
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            checkGLError("Clear")

            GLES20.glUseProgram(program)
            checkGLError("UseProgram")

            // Обновление ориентации
            updateOrientation()
            val modelMatrix = FloatArray(16)
            Matrix.setIdentityM(modelMatrix, 0)
            val mvpMatrix = FloatArray(16)
            Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, rotationMatrix, 0) // Применяем ориентацию

            val mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
            checkGLError("SetMatrix")

            val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            if (positionHandle == -1) {
                Log.e("StarMap", "Could not get attribute location for aPosition")
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
            Log.d("StarMap", "Surface changed: $width x $height")
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
            Log.d("StarMap", "First star: (${starPositions[0]}, ${starPositions[1]}, ${starPositions[2]})")
        }
    }

    private fun updateOrientation() {
        val success = SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)
        if (success) {
            // Преобразование матрицы в кватернион
            val q = quaternionFromRotationMatrix(rotationMatrix)
            // Проверка изменения ориентации
            if (isQuaternionChanged(q, lastQuaternion, 0.01f)) {
                lastQuaternion = q.copyOf()
                Matrix.setIdentityM(rotationMatrix, 0)
                // Применяем кватернион как вращение (переводим в градусы для OpenGL)
                val angle = 2 * Math.acos(q[0].toDouble()).toFloat() * 180f / Math.PI.toFloat()
                val axisX = q[1]
                val axisY = q[2]
                val axisZ = q[3]
                Matrix.rotateM(rotationMatrix, 0, angle, axisX, axisY, axisZ)
                Log.d("StarMap", "Orientation updated: Angle=$angle, Axis=($axisX, $axisY, $axisZ)")
            }
        } else {
            Log.w("StarMap", "Failed to get rotation matrix")
            Matrix.setIdentityM(rotationMatrix, 0)
        }
    }

    private fun isQuaternionChanged(q1: FloatArray, q2: FloatArray, threshold: Float): Boolean {
        val dot = q1[0] * q2[0] + q1[1] * q2[1] + q1[2] * q2[2] + q1[3] * q2[3]
        return dot < 1 - threshold
    }

    private fun quaternionFromRotationMatrix(matrix: FloatArray): FloatArray {
        val trace = matrix[0] + matrix[5] + matrix[10]
        val q = FloatArray(4)
        if (trace > 0) {
            val s = 0.5f / kotlin.math.sqrt(trace + 1.0f)
            q[0] = 0.25f / s
            q[1] = (matrix[6] - matrix[9]) * s
            q[2] = (matrix[8] - matrix[2]) * s
            q[3] = (matrix[1] - matrix[4]) * s
        } else {
            if (matrix[0] > matrix[5] && matrix[0] > matrix[10]) {
                val s = 2.0f * kotlin.math.sqrt(1.0f + matrix[0] - matrix[5] - matrix[10])
                q[0] = (matrix[6] - matrix[9]) / s
                q[1] = 0.25f * s
                q[2] = (matrix[1] + matrix[4]) / s
                q[3] = (matrix[2] + matrix[8]) / s
            } else if (matrix[5] > matrix[10]) {
                val s = 2.0f * kotlin.math.sqrt(1.0f + matrix[5] - matrix[0] - matrix[10])
                q[0] = (matrix[8] - matrix[2]) / s
                q[1] = (matrix[1] + matrix[4]) / s
                q[2] = 0.25f * s
                q[3] = (matrix[6] + matrix[9]) / s
            } else {
                val s = 2.0f * kotlin.math.sqrt(1.0f + matrix[10] - matrix[0] - matrix[5])
                q[0] = (matrix[1] - matrix[4]) / s
                q[1] = (matrix[2] + matrix[8]) / s
                q[2] = (matrix[6] + matrix[9]) / s
                q[3] = 0.25f * s
            }
        }
        return q
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelerometerReading = event.values.clone()
                // Логируем только при значительных изменениях
                if (isSensorChanged(accelerometerReading, event.values, 0.1f)) {
                    Log.d("StarMap", "Accelerometer: ${accelerometerReading.joinToString()}")
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magnetometerReading = event.values.clone()
                if (isSensorChanged(magnetometerReading, event.values, 0.5f)) {
                    Log.d("StarMap", "Magnetometer: ${magnetometerReading.joinToString()}")
                }
            }
        }
    }

    private fun isSensorChanged(oldValues: FloatArray, newValues: FloatArray, threshold: Float): Boolean {
        return (0 until oldValues.size).any { i -> Math.abs(oldValues[i] - newValues[i]) > threshold }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d("StarMap", "Sensor accuracy changed: ${sensor?.name}, accuracy: $accuracy")
    }

    override fun onResume() {
        super.onResume()
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
            Log.d("StarMap", "Accelerometer registered")
        } else {
            Log.e("StarMap", "Accelerometer not available")
        }
        if (magnetometer != null) {
            sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME)
            Log.d("StarMap", "Magnetometer registered")
        } else {
            Log.e("StarMap", "Magnetometer not available")
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        Log.d("StarMap", "Sensors unregistered")
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                Log.e("StarMap", "Shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
            } else {
                Log.d("StarMap", "Shader compiled successfully: $type")
            }
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