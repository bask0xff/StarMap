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
    private var quaternion = FloatArray(4)

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
        private val starCount = 1000
        private val starPositions = FloatArray(starCount * 3)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.0f, 0.0f, 0.1f, 1.0f) // Тёмно-синий фон для отладки
            checkGLError("ClearColor")

            // Генерация звёзд
            generateStars()
            starBuffer = ByteBuffer.allocateDirect(starPositions.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(starPositions)
                    position(0)
                }

            // Загрузка шейдеров
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
            Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, modelMatrix, 0)

            val mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
            checkGLError("SetMatrix")

            // Отрисовка звёзд
            val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            GLES20.glEnableVertexAttribArray(positionHandle)
            checkGLError("EnableVertexAttrib")
            starBuffer.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, starBuffer)
            checkGLError("VertexAttribPointer")
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, starCount)
            checkGLError("DrawArrays")
            GLES20.glDisableVertexAttribArray(positionHandle)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            checkGLError("Viewport")
            val ratio = width.toFloat() / height
            Matrix.perspectiveM(projectionMatrix, 0, 60f, ratio, 0.1f, 100f)
            Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, -2f, 0f, 0f, 0f, 0f, 1f, 0f)
        }

        private fun generateStars() {
            for (i in 0 until starCount) {
                val theta = Random.nextFloat() * 2 * Math.PI.toFloat()
                val phi = Math.acos((2 * Random.nextFloat() - 1).toDouble()).toFloat()
                val radius = 10f // Увеличенный радиус для видимости
                starPositions[i * 3] = radius * sin(phi) * cos(theta)
                starPositions[i * 3 + 1] = radius * sin(phi) * sin(theta)
                starPositions[i * 3 + 2] = radius * cos(phi)
            }
        }
    }

    private fun updateOrientation() {
        val success = SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)
        if (success) {
            val q = quaternionFromRotationMatrix(rotationMatrix)
            quaternion[0] = q[0]
            quaternion[1] = q[1]
            quaternion[2] = q[2]
            quaternion[3] = q[3]
            Matrix.setIdentityM(rotationMatrix, 0)
            Matrix.rotateM(rotationMatrix, 0, quaternion[0], quaternion[1], quaternion[2], quaternion[3])
        } else {
            Log.w("StarMap", "Failed to get rotation matrix")
        }
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
                Log.d("StarMap", "Accelerometer: ${accelerometerReading.joinToString()}")
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magnetometerReading = event.values.clone()
                Log.d("StarMap", "Magnetometer: ${magnetometerReading.joinToString()}")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d("StarMap", "Sensor accuracy changed: ${sensor?.name}, accuracy: $accuracy")
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also {
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
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                Log.e("StarMap", "Shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
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
            gl_PointSize = 5.0; // Увеличенный размер точек для видимости
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        void main() {
            gl_FragColor = vec4(1.0, 1.0, 1.0, 1.0); // Белый цвет звёзд
        }
    """.trimIndent()
}