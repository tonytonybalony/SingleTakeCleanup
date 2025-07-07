package io.github.tonytonybalony.cleaner // Make sure this matches your package name

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var cleanButton: Button
    private lateinit var dryRunSwitch: SwitchMaterial
    private lateinit var resultTextView: TextView


    private val TRASH_FOLDER_NAME = "SingleTake_Trash"

    // --- The NEW, MODERN way to handle permissions ---

    // 1. Define the contract: what are we asking for?
    // We are asking for a permission, and the result is a Boolean (true if granted).
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // For Android 10, permission was granted.
                processFiles()
            } else {
                // For Android 10, permission was denied.
                resultTextView.text = "儲存權限被拒絕。"
            }
        }

    // 2. Define the contract for the "All Files Access" settings page.
    private val manageStorageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // This callback is triggered when we return from the settings page.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    // User has just granted the permission.
                    processFiles()
                } else {
                    resultTextView.text = "「所有檔案存取」權限被拒絕。"
                }
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cleanButton = findViewById(R.id.cleanButton)
        dryRunSwitch = findViewById(R.id.dryRunSwitch)
        resultTextView = findViewById(R.id.resultTextView)
        val testMoveButton: Button = findViewById(R.id.testMoveButton)
        testMoveButton.setOnClickListener {
            moveSingleTestFileToTrash()
        }
        cleanButton.setOnClickListener {
            checkPermissionAndProcessFiles()
        }
    }

    private fun checkPermissionAndProcessFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // For Android 11, 12, 13, 14+
            if (Environment.isExternalStorageManager()) {
                // We have permission, GO!
                processFiles()
            } else {
                // We DON'T have permission, launch the system settings page for the user.
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                manageStorageLauncher.launch(intent) // Use the new launcher
            }
        } else { // For Android 10 (Legacy)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                // We have permission, GO!
                processFiles()
            } else {
                // Request the old permission.
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
    private fun moveSingleTestFileToTrash() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cameraDir = File(Environment.getExternalStorageDirectory(), "DCIM/Camera")
            val trashDir = File(cameraDir, TRASH_FOLDER_NAME)
            if (!trashDir.exists()) trashDir.mkdirs()

            // Pick the first matching file as a test
            val testFile = cameraDir.listFiles()?.firstOrNull { file ->
                file.name.matches(Regex("\\d{8}_\\d{6}_\\d{2}\\.(jpg|mp4|jpeg)")) &&
                        !file.name.endsWith("_99.mp4")
            }

            val resultMsg = if (testFile != null) {
                val destFile = File(trashDir, testFile.name)
                if (testFile.renameTo(destFile)) {
                    "Test: Moved ${testFile.name} to trash."
                } else {
                    "Test: Failed to move ${testFile.name}."
                }
            } else {
                "Test: No matching file found."
            }

            withContext(Dispatchers.Main) {
                resultTextView.text = resultMsg
            }
        }
    }

    private fun processFiles() {
        val isDryRun = dryRunSwitch.isChecked
        val buttonText = if(isDryRun) "正在模擬掃描..." else "正在移動檔案..."

        cleanButton.isEnabled = false
        cleanButton.text = buttonText
        resultTextView.text = "掃描中，請稍候..."

        lifecycleScope.launch(Dispatchers.IO) {
            val cameraDir = File(Environment.getExternalStorageDirectory(), "DCIM/Camera")
            val trashDir = File(cameraDir, TRASH_FOLDER_NAME)

            if (!trashDir.exists()) {
                trashDir.mkdirs()
            }

            val filesToProcess = mutableListOf<File>()
            val logBuilder = StringBuilder()
            var movedCount = 0
            var failedCount = 0

            if (cameraDir.exists() && cameraDir.isDirectory) {
                cameraDir.listFiles()?.forEach { file ->
                    // ==========================================================
                    // === THIS IS THE ONLY LINE THAT HAS CHANGED ===
                    // New, More Accurate Rule:
                    val isSingleTakeGenerated = file.name.matches(Regex("\\d{8}_\\d{6}_\\d{2}\\.(jpg|mp4|jpeg)"))
                    // ==========================================================

                    val isNotTheOriginalVideo = !file.name.endsWith("_99.mp4")

                    if (isSingleTakeGenerated && isNotTheOriginalVideo) {
                        filesToProcess.add(file)
                    }
                }

                if (isDryRun) {
                    logBuilder.append("【模擬模式】找到 ${filesToProcess.size} 個可移動的檔案：\n\n")
                    if (filesToProcess.isEmpty()) {
                        logBuilder.append("沒有找到符合條件的檔案。")
                    } else {
                        filesToProcess.forEach { file -> logBuilder.append(file.name).append("\n") }
                    }
                } else {
                    logBuilder.append("【移動模式】開始移動 ${filesToProcess.size} 個檔案到回收站...\n\n")
                    filesToProcess.forEach { file ->
                        val destFile = File(trashDir, file.name)
                        try {
                            if (file.renameTo(destFile)) {
                                movedCount++
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

            withContext(Dispatchers.Main) {
                resultTextView.text = logBuilder.toString()
                cleanButton.isEnabled = true
                cleanButton.text = "開始掃描"
            }
        }
    }
}