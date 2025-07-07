package io.github.tonytonybalony.cleaner
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var cleanButton: Button
    private lateinit var dryRunSwitch: SwitchMaterial
    private lateinit var resultTextView: TextView

    private val PERMISSION_REQUEST_CODE = 101
    private val TRASH_FOLDER_NAME = "SingleTake_Trash" // Our "Recycle Bin"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cleanButton = findViewById(R.id.cleanButton)
        dryRunSwitch = findViewById(R.id.dryRunSwitch)
        resultTextView = findViewById(R.id.resultTextView)

        cleanButton.setOnClickListener {
            checkPermissionAndProcessFiles()
        }
    }

    private fun checkPermissionAndProcessFiles() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted, request it.
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), PERMISSION_REQUEST_CODE)
        } else {
            // Permission has already been granted, process files.
            processFiles()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission was granted.
                processFiles()
            } else {
                // Permission was denied.
                resultTextView.text = "權限被拒絕，無法讀取相簿檔案。"
            }
        }
    }

    private fun processFiles() {
        val isDryRun = dryRunSwitch.isChecked
        val buttonText = if(isDryRun) "正在模擬掃描..." else "正在移動檔案..."

        // Disable button and show progress on main thread
        cleanButton.isEnabled = false
        cleanButton.text = buttonText
        resultTextView.text = "掃描中，請稍候..."

        // Use Coroutines to run heavy I/O operations on a background thread
        GlobalScope.launch(Dispatchers.IO) {
            val cameraDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).resolve("Camera")
            val trashDir = File(cameraDir, TRASH_FOLDER_NAME)

            // Create the trash directory if it doesn't exist
            if (!trashDir.exists()) {
                trashDir.mkdirs()
            }

            val filesToProcess = mutableListOf<File>()
            val logBuilder = StringBuilder()
            var movedCount = 0
            var failedCount = 0

            if (cameraDir.exists() && cameraDir.isDirectory) {
                cameraDir.listFiles()?.forEach { file ->
                    // Rule 1: Match the Single Take generated file pattern (e.g., IMG_..._01.jpg)
                    val isSingleTakeGenerated = file.name.matches(Regex("IMG_.*_\\d{2}\\.(jpg|mp4|jpeg)"))
                    // Rule 2: Exclude the original video file you want to keep
                    val isNotTheOriginalVideo = !file.name.endsWith("_99.mp4")

                    if (isSingleTakeGenerated && isNotTheOriginalVideo) {
                        filesToProcess.add(file)
                    }
                }

                if (isDryRun) {
                    // --- SIMULATION MODE ---
                    logBuilder.append("【模擬模式】找到 ${filesToProcess.size} 個可移動的檔案：\n\n")
                    if (filesToProcess.isEmpty()) {
                        logBuilder.append("沒有找到符合條件的檔案。")
                    } else {
                        filesToProcess.forEach { file ->
                            logBuilder.append(file.name).append("\n")
                        }
                    }
                } else {
                    // --- REAL MOVE MODE ---
                    logBuilder.append("【移動模式】開始移動 ${filesToProcess.size} 個檔案到回收站...\n\n")
                    filesToProcess.forEach { file ->
                        val destFile = File(trashDir, file.name)
                        try {
                            if (file.renameTo(destFile)) {
                                movedCount++
                                Log.d("FileMover", "Moved: ${file.name}")
                            } else {
                                failedCount++
                                Log.e("FileMover", "Failed to move: ${file.name}")
                            }
                        } catch (e: Exception) {
                            failedCount++
                            Log.e("FileMover", "Error moving ${file.name}", e)
                        }
                    }
                    logBuilder.append("處理完成！\n")
                    logBuilder.append("成功移動: $movedCount 個檔案\n")
                    logBuilder.append("移動失敗: $failedCount 個檔案\n")
                    logBuilder.append("檔案已移至: DCIM/Camera/$TRASH_FOLDER_NAME/")
                }
            } else {
                logBuilder.append("錯誤：找不到 DCIM/Camera 資料夾。")
            }

            // Switch back to the Main thread to update the UI
            withContext(Dispatchers.Main) {
                resultTextView.text = logBuilder.toString()
                cleanButton.isEnabled = true
                cleanButton.text = "開始掃描"
            }
        }
    }
}