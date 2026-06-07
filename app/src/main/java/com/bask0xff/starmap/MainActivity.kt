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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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

data class Constellation(
    val name: String,
    val lines: List<ConstellationLine>,
    val labelStar: NamedStar? = null
)

data class ConstellationLine(
    val x1: Float, val y1: Float, val z1: Float,
    val x2: Float, val y2: Float, val z2: Float
)

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager

    private var smoothedAccelerometer = FloatArray(3)
    private var smoothedMagnetometer = FloatArray(3)
    private var hasAccelerometer = false
    private var hasMagnetometer = false

    val debugInfo = mutableStateOf("")

    // Матрица вращения, обновляется из GL-потока через volatile-копию
    @Volatile
    private var currentRotationMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    // Текущий поворот экрана, обновляется из главного потока
    @Volatile
    private var currentDisplayRotation: Int = Surface.ROTATION_0

    private val alpha = 0.05f  // Уменьшен для более плавного движения

    private val starList = mutableListOf<Star>()
    private val namedStarList = mutableListOf<NamedStar>()
    private val constellations = mutableListOf<Constellation>()

    val screenLabels = mutableStateListOf<ScreenLabel>()

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        loadStarsFromAssets()
        defineConstellations()
        setContent { StarMapScreen() }
    }

    private fun loadStarsFromAssets() {
        try {
            val inputStream = assets.open("stars_bright.csv")
            val reader = BufferedReader(InputStreamReader(inputStream))
            reader.readLine()

            starList.clear()
            namedStarList.clear()

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
                } catch (_: Exception) {}
            }
            addImportantNamedStars()
        } catch (e: Exception) {
            Log.e("StarMap", "Ошибка загрузки CSV", e)
            generateFallbackStars()
        }
    }

    private fun addImportantNamedStars() {
        val important = listOf(
            "Сириус"     to Triple(6.7525f,  -16.7161f, -1.46f),
            "Вега"       to Triple(18.6167f,  38.7833f,  0.03f),
            "Арктур"     to Triple(14.2610f,  19.1822f, -0.05f),
            "Капелла"    to Triple(5.2783f,   45.9981f,  0.08f),
            "Ригель"     to Triple(5.2422f,   -8.2017f,  0.13f),
            "Бетельгейзе" to Triple(5.9194f,   7.4072f,  0.50f),
            "Альдебаран" to Triple(4.5986f,   16.5092f,  0.85f),
            "Антарес"    to Triple(16.4903f, -26.4319f,  1.06f),
            "Спика"      to Triple(13.4197f, -11.1614f,  0.98f),
            "Денеб"      to Triple(20.6906f,  45.2803f,  1.25f),
            "Альтаир"    to Triple(19.7933f,   8.8683f,  0.77f),
            "Процион"    to Triple(7.6553f,    5.2250f,  0.34f)
        )

        important.forEach { (name, data) ->
            val (raH, decD, _) = data
            val raRad = raH * 15f * (PI.toFloat() / 180f)
            val decRad = decD * (PI.toFloat() / 180f)
            val x = cos(decRad) * cos(raRad) * 9.8f
            val y = cos(decRad) * sin(raRad) * 9.8f
            val z = sin(decRad) * 9.8f
            val star = Star(x, y, z, 13f)
            starList.add(star)
            namedStarList.add(NamedStar(name, x, y, z))
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

    private fun defineConstellations() {
        constellations.clear()

        fun addConstellation(
            name: String,
            lines: List<Pair<Pair<Float, Float>, Pair<Float, Float>>>,
            labelRaDec: Pair<Float, Float>? = null
        ) {
            val constLines = mutableListOf<ConstellationLine>()
            lines.forEach { (p1, p2) ->
                val (ra1, dec1) = p1
                val (ra2, dec2) = p2

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

                constLines.add(ConstellationLine(x1, y1, z1, x2, y2, z2))
            }

            val labelStar = if (labelRaDec != null) {
                val (ra, dec) = labelRaDec
                val raRad = ra * 15f * (PI.toFloat() / 180f)
                val decRad = dec * (PI.toFloat() / 180f)
                NamedStar(
                    name,
                    cos(decRad) * cos(raRad) * 9.8f,
                    cos(decRad) * sin(raRad) * 9.8f,
                    sin(decRad) * 9.8f
                )
            } else null

            constellations.add(Constellation(name, constLines, labelStar))
        }

        // Большая Медведица
        addConstellation("Большая Медведица", listOf(
            11.03f to 61.75f to (11.03f to 56.38f),
            11.03f to 56.38f to (12.26f to 57.03f),
            12.26f to 57.03f to (12.90f to 55.96f),
            12.90f to 55.96f to (13.79f to 49.31f),
            13.79f to 49.31f to (13.40f to 54.93f),
            13.40f to 54.93f to (13.79f to 49.31f)
        ), 11.0f to 55.0f)

        // Малая Медведица
        addConstellation("Малая Медведица", listOf(
            2.53f to 89.26f to (14.85f to 74.16f),
            14.85f to 74.16f to (16.76f to 77.79f)
        ), 2.53f to 89.0f)

        // Кассиопея
        addConstellation("Кассиопея", listOf(
            0.675f to 56.54f to (1.977f to 63.67f),
            1.977f to 63.67f to (3.792f to 63.67f),
            3.792f to 63.67f to (5.433f to 60.72f),
            5.433f to 60.72f to (0.675f to 56.54f)
        ), 1.5f to 61.0f)

        // Лебедь
        addConstellation("Лебедь", listOf(
            20.69f to 45.28f to (19.85f to 40.68f),
            19.85f to 40.68f to (20.37f to 36.39f),
            20.69f to 45.28f to (19.51f to 27.96f)
        ), 20.69f to 45.0f)

        // Орион
        addConstellation("Орион", listOf(
            5.92f to 7.41f to (5.24f to -8.20f),
            5.60f to -1.20f to (5.24f to -8.20f),
            5.60f to -1.20f to (5.92f to 7.41f),
            5.42f to -0.30f to (5.60f to -1.20f)
        ), 5.6f to 0.0f)

        Log.d("StarMap", "Загружено ${constellations.size} созвездий")
    }

    @Composable
    fun StarMapScreen() {
        val glSurfaceView = remember { GLSurfaceView(this) }
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(StarRenderer())
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(factory = { glSurfaceView }, modifier = Modifier.fillMaxSize())
            LabelsOverlay()
        }
    }

    @Composable
    fun LabelsOverlay() {
        Box(modifier = Modifier.fillMaxSize()) {
            screenLabels.forEach { label ->
                Text(
                    text = label.name,
                    color = if (label.name.contains("Медведица") || label.name.contains("Кассиопея") ||
                        label.name.contains("Лебедь") || label.name.contains("Орион"))
                        Color.Yellow else Color.White,
                    fontSize = if (label.name.length > 10) 11.sp else 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .offset(x = label.screenX.dp, y = label.screenY.dp)
                        .padding(4.dp)
                )
            }
            // Отладочный оверлей
            Text(
                text = debugInfo.value,
                color = Color.Green,
                fontSize = 10.sp,
                modifier = Modifier.padding(4.dp)
            )
        }
    }

    inner class StarRenderer : GLSurfaceView.Renderer {
        private lateinit var starPositionBuffer: FloatBuffer
        private lateinit var starSizeBuffer: FloatBuffer
        private lateinit var constellationBuffer: FloatBuffer

        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)

        // Локальные буферы для работы только внутри GL-потока
        private val rotMatrix = FloatArray(16)
        private val remappedMatrix = FloatArray(16)
        private val invertedMatrix = FloatArray(16)

        private var starProgram: Int = 0
        private var lineProgram: Int = 0

        private var positionHandle = 0
        private var sizeHandle = 0
        private var mvpHandle = 0

        private var width = 0
        private var height = 0

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0.005f, 0.005f, 0.025f, 1.0f)
            prepareStarBuffers()
            prepareConstellationBuffer()

            starProgram = createProgram(starVertexShaderCode, starFragmentShaderCode)
            lineProgram = createProgram(lineVertexShaderCode, lineFragmentShaderCode)

            positionHandle = GLES20.glGetAttribLocation(starProgram, "aPosition")
            sizeHandle = GLES20.glGetAttribLocation(starProgram, "aSize")
            mvpHandle = GLES20.glGetUniformLocation(starProgram, "uMVPMatrix")

            // Камера смотрит из начала координат вперёд по +Z
            // Звёздная сфера вокруг наблюдателя
            // В onSurfaceCreated:
            Matrix.setLookAtM(viewMatrix, 0,
                0f, 0f, 0f,   // позиция
                0f, 1f, 0f,   // смотрим по +Y  ← было 0,0,1
                0f, 0f, 1f    // "вверх" = +Z   ← было 0,1,0
            )
        }

        private fun prepareStarBuffers() {
            val positions = FloatArray(starList.size * 3)
            val sizes = FloatArray(starList.size)

            starList.forEachIndexed { i, star ->
                positions[i * 3]     = star.x
                positions[i * 3 + 1] = star.y
                positions[i * 3 + 2] = star.z
                sizes[i] = star.size
            }

            starPositionBuffer = createFloatBuffer(positions)
            starSizeBuffer = createFloatBuffer(sizes)
        }

        private fun prepareConstellationBuffer() {
            val vertices = mutableListOf<Float>()
            constellations.forEach { const ->
                const.lines.forEach { line ->
                    vertices.add(line.x1); vertices.add(line.y1); vertices.add(line.z1)
                    vertices.add(line.x2); vertices.add(line.y2); vertices.add(line.z2)
                }
            }
            constellationBuffer = createFloatBuffer(vertices.toFloatArray())
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val displayRotation = currentDisplayRotation

            val accCopy: FloatArray
            val magCopy: FloatArray
            synchronized(this@MainActivity) {
                accCopy = smoothedAccelerometer.clone()
                magCopy = smoothedMagnetometer.clone()
            }

            val success = SensorManager.getRotationMatrix(rotMatrix, null, accCopy, magCopy)
            if (!success) {
                Matrix.setIdentityM(invertedMatrix, 0)
            } else {
                // SensorManager даёт матрицу, где строки = оси устройства в мировой (ENU) системе.
                // Нам нужно: направление "куда смотрит экран" → это -Z устройства в мировых координатах.
                //
                // Для SkyMap правильный remapping:
                //   PORTRAIT (ROTATION_0):
                //     Устройство держат вертикально. Нормаль экрана = -Z устройства.
                //     Хотим: новый X = X устройства (горизонталь экрана = восток)
                //             новый Y = Z устройства (вертикаль экрана = зенит)
                //     → AXIS_X, AXIS_Z
                //
                //   LANDSCAPE RIGHT (ROTATION_90):
                //     Устройство повёрнуто на 90° вправо.
                //     → AXIS_Y, AXIS_MINUS_X  (стандартный remapping для landscape)
                //     Но нормаль экрана всё ещё -Z, поэтому добавляем Z:
                //     → AXIS_MINUS_Y, AXIS_Z
                //
                //   PORTRAIT FLIPPED (ROTATION_180):
                //     → AXIS_MINUS_X, AXIS_Z
                //
                //   LANDSCAPE LEFT (ROTATION_270):
                //     → AXIS_Y, AXIS_Z

                val remapSuccess = when (displayRotation) {
                    Surface.ROTATION_0   -> SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_X,       SensorManager.AXIS_Z,  remappedMatrix)
                    Surface.ROTATION_90  -> SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_Z,  remappedMatrix)
                    Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_Z,  remappedMatrix)
                    Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_Y,       SensorManager.AXIS_Z,  remappedMatrix)
                    else                 -> SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_X,       SensorManager.AXIS_Z,  remappedMatrix)
                }

                if (!remapSuccess) {
                    Matrix.setIdentityM(invertedMatrix, 0)
                } else {
                    // Транспонируем (= инвертируем для матрицы вращения).
                    // remappedMatrix переводит вектор из системы устройства в мировую.
                    // Нам нужна обратная: из мировой в систему устройства — это и есть транспонирование.
                    // Транспонируем
                    invertedMatrix[ 0] = remappedMatrix[ 0]; invertedMatrix[ 1] = remappedMatrix[ 4]; invertedMatrix[ 2] = remappedMatrix[ 8]; invertedMatrix[ 3] = 0f
                    invertedMatrix[ 4] = remappedMatrix[ 1]; invertedMatrix[ 5] = remappedMatrix[ 5]; invertedMatrix[ 6] = remappedMatrix[ 9]; invertedMatrix[ 7] = 0f
                    invertedMatrix[ 8] = remappedMatrix[ 2]; invertedMatrix[ 9] = remappedMatrix[ 6]; invertedMatrix[10] = remappedMatrix[10]; invertedMatrix[11] = 0f
                    invertedMatrix[12] = 0f;                 invertedMatrix[13] = 0f;                 invertedMatrix[14] = 0f;                 invertedMatrix[15] = 1f

                    invertedMatrix[1] = -invertedMatrix[1]; invertedMatrix[5] = -invertedMatrix[5]; invertedMatrix[9] = -invertedMatrix[9]
                }
            }

            val vpMatrix = FloatArray(16)
            Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, invertedMatrix, 0)

            drawStars(mvpMatrix)
            drawConstellations(mvpMatrix)
            updateScreenLabels()
        }

        private fun drawStars(mvpMatrixArg: FloatArray) {
            GLES20.glUseProgram(starProgram)
            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrixArg, 0)

            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glEnableVertexAttribArray(sizeHandle)

            starPositionBuffer.position(0)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, starPositionBuffer)

            starSizeBuffer.position(0)
            GLES20.glVertexAttribPointer(sizeHandle, 1, GLES20.GL_FLOAT, false, 4, starSizeBuffer)

            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, starList.size)
            GLES20.glDisable(GLES20.GL_BLEND)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(sizeHandle)
        }

        private fun drawConstellations(mvpMatrixArg: FloatArray) {
            if (constellations.isEmpty()) return

            GLES20.glUseProgram(lineProgram)
            GLES20.glUniformMatrix4fv(
                GLES20.glGetUniformLocation(lineProgram, "uMVPMatrix"),
                1, false, mvpMatrixArg, 0
            )

            val posHandle = GLES20.glGetAttribLocation(lineProgram, "aPosition")
            GLES20.glEnableVertexAttribArray(posHandle)
            constellationBuffer.position(0)
            GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, constellationBuffer)

            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glLineWidth(2f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, constellations.sumOf { it.lines.size } * 2)
            GLES20.glDisable(GLES20.GL_BLEND)

            GLES20.glDisableVertexAttribArray(posHandle)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            this.width = width
            this.height = height
            GLES20.glViewport(0, 0, width, height)
            val ratio = width.toFloat() / height
            Matrix.perspectiveM(projectionMatrix, 0, 70f, ratio, 0.1f, 100f)
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
            val w = width.toFloat()
            val h = height.toFloat()

            fun project(px: Float, py: Float, pz: Float, name: String, offsetY: Float) {
                val objPos = floatArrayOf(px, py, pz, 1f)
                val clip = FloatArray(4)
                Matrix.multiplyMV(clip, 0, mvpMatrix, 0, objPos, 0)

                // Отсекаем то, что за камерой
                if (clip[3] <= 0f) return

                val ndcX = clip[0] / clip[3]
                val ndcY = clip[1] / clip[3]
                val ndcZ = clip[2] / clip[3]

                // Отсекаем то, что вне frustum
                if (ndcX < -1f || ndcX > 1f || ndcY < -1f || ndcY > 1f || ndcZ < -1f || ndcZ > 1f) return

                val screenX = (ndcX * 0.5f + 0.5f) * w
                val screenY = (1.0f - (ndcY * 0.5f + 0.5f)) * h

                // Переводим пиксели в dp для Compose offset
                val density = resources.displayMetrics.density
                val dpX = screenX / density
                val dpY = (screenY + offsetY) / density

                tempLabels.add(ScreenLabel(name, dpX, dpY))
            }

            namedStarList.forEach { star ->
                project(star.x, star.y, star.z, star.name, -20f)
            }

            constellations.forEach { const ->
                const.labelStar?.let { ls ->
                    project(ls.x, ls.y, ls.z, const.name, -35f)
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

    // Вызывается из главного потока, безопасно обновляет поворот экрана
    private fun updateDisplayRotation() {
        @Suppress("DEPRECATION")
        currentDisplayRotation = windowManager.defaultDisplay.rotation
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            val values = it.values.clone()
            synchronized(this) {
                when (it.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        if (!hasAccelerometer) {
                            smoothedAccelerometer = values.clone()
                            hasAccelerometer = true
                        } else {
                            for (i in values.indices)
                                smoothedAccelerometer[i] = alpha * values[i] + (1 - alpha) * smoothedAccelerometer[i]
                        }
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        if (!hasMagnetometer) {
                            smoothedMagnetometer = values.clone()
                            hasMagnetometer = true
                        } else {
                            for (i in values.indices)
                                smoothedMagnetometer[i] = alpha * values[i] + (1 - alpha) * smoothedMagnetometer[i]
                        }
                    }
                }
            }

            // Обновляем отладку прямо здесь
            val rot = FloatArray(16)
            val acc = smoothedAccelerometer
            val mag = smoothedMagnetometer
            if (SensorManager.getRotationMatrix(rot, null, acc, mag)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rot, orientation)
                val azimuth = Math.toDegrees(orientation[0].toDouble()).toInt()
                val pitch   = Math.toDegrees(orientation[1].toDouble()).toInt()
                val roll    = Math.toDegrees(orientation[2].toDouble()).toInt()

                mainHandler.post {
                    debugInfo.value = """
                    |ACC:  x=${acc[0].fmt()} y=${acc[1].fmt()} z=${acc[2].fmt()}
                    |MAG:  x=${mag[0].fmt()} y=${mag[1].fmt()} z=${mag[2].fmt()}
                    |Azimuth(yaw):  $azimuth°
                    |Pitch:         $pitch°
                    |Roll:          $roll°
                    |Display rot:   $currentDisplayRotation
                    |ROT[0..2]: ${rot[0].fmt()} ${rot[1].fmt()} ${rot[2].fmt()}
                    |ROT[4..6]: ${rot[4].fmt()} ${rot[5].fmt()} ${rot[6].fmt()}
                    |ROT[8..10]:${rot[8].fmt()} ${rot[9].fmt()} ${rot[10].fmt()}
                """.trimMargin()
                }
            }

            mainHandler.post { updateDisplayRotation() }
        }
    }

    fun Float.fmt() = "%.2f".format(this)

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onResume() {
        super.onResume()
        updateDisplayRotation()
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

    private val starVertexShaderCode = """
        attribute vec4 aPosition;
        attribute float aSize;
        uniform mat4 uMVPMatrix;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            gl_PointSize = aSize / gl_Position.w * 10.0;
        }
    """.trimIndent()

    private val starFragmentShaderCode = """
        precision mediump float;
        void main() {
            vec2 coord = gl_PointCoord - vec2(0.5);
            float r = dot(coord, coord);
            if (r > 0.25) discard;
            float alpha = 1.0 - smoothstep(0.15, 0.25, r);
            gl_FragColor = vec4(1.0, 1.0, 0.95, alpha);
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
            gl_FragColor = vec4(0.4, 0.6, 1.0, 0.6);
        }
    """.trimIndent()
}