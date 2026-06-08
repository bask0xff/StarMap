package com.bask0xff.starmap

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null

    // Матрицы
    private val rawRotationMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val viewAdjustmentMatrix = FloatArray(16).apply {
        Matrix.setIdentityM(this, 0)
        // Базовый разворот: переводим устройство из плоскости стола в вертикальное положение AR
        Matrix.rotateM(this, 0, 90f, 1f, 0f, 0f)
    }

    // Финальная матрица для вашего рендерера
    val glRotationMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    private var lastLogTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    override fun onResume() {
        super.onResume()
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rawRotationMatrix, event.values)
            updateOrientation()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateOrientation() {
        // Безопасное получение ориентации экрана для ComponentActivity
        val rot = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

        // 1. Объединяем матрицу датчика с базовой трансформацией осей
        val baseGlMatrix = FloatArray(16)
        Matrix.multiplyMM(baseGlMatrix, 0, viewAdjustmentMatrix, 0, rawRotationMatrix, 0)

        // 2. Корректируем поворот под текущий режим удержания устройства
        val orientedMatrix = FloatArray(16)
        Matrix.setIdentityM(orientedMatrix, 0)

        when (rot) {
            Surface.ROTATION_0 -> {
                System.arraycopy(baseGlMatrix, 0, orientedMatrix, 0, 16)
            }
            Surface.ROTATION_90 -> {
                Matrix.rotateM(orientedMatrix, 0, baseGlMatrix, 0, 90f, 0f, 0f, 1f)
            }
            Surface.ROTATION_180 -> {
                Matrix.rotateM(orientedMatrix, 0, baseGlMatrix, 0, 180f, 0f, 0f, 1f)
            }
            Surface.ROTATION_270 -> {
                Matrix.rotateM(orientedMatrix, 0, baseGlMatrix, 0, -90f, 0f, 0f, 1f)
            }
        }

        // 3. Сохраняем в целевую матрицу
        System.arraycopy(orientedMatrix, 0, glRotationMatrix, 0, 16)

        // 4. Логирование векторов раз в 500 мс
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastLogTime > 500) {
            lastLogTime = currentTime
            logCameraDirection(glRotationMatrix, rot)
        }
    }

    private fun logCameraDirection(matrix: FloatArray, rotationMode: Int) {
        // Вычисляем мировые координаты вектора взгляда (ось -Z в пространстве камеры)
        val viewDirX = -matrix[8]
        val viewDirY = -matrix[9]
        val viewDirZ = -matrix[10]

        // Вычисляем верх экрана (ось +Y в пространстве камеры)
        val upDirX = matrix[4]
        val upDirY = matrix[5]
        val upDirZ = matrix[6]

        val modeString = when(rotationMode) {
            Surface.ROTATION_0 -> "PORTRAIT"
            Surface.ROTATION_90 -> "LANDSCAPE_90"
            Surface.ROTATION_180 -> "REVERSE_PORTRAIT"
            Surface.ROTATION_270 -> "LANDSCAPE_270"
            else -> "UNKNOWN"
        }

        Log.d("StarMap_Debug", "=== СРЕЗ ТРАНСФОРМАЦИИ ===")
        Log.d("StarMap_Debug", "Режим экрана: $modeString")
        Log.d("StarMap_Debug", String.format("Вектор ВЗГЛЯДА: X=%.2f, Y=%.2f, Z=%.2f", viewDirX, viewDirY, viewDirZ))
        Log.d("StarMap_Debug", String.format("Вектор ВВЕРХА:  X=%.2f, Y=%.2f, Z=%.2f", upDirX, upDirY, upDirZ))
    }
}