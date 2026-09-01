package com.telesync.foldersync

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.work.*
import com.telesync.foldersync.databinding.ActivityMainBinding
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit().putString(KEY_FOLDER_URI, uri.toString()).apply()
            binding.tvFolderPath.text = uri.path ?: uri.toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ক্র্যাশ হ্যান্ডলার বসানো হচ্ছে, যাতে পরের বার এরর হলে সেটা স্ক্রিনে দেখা যায়
        val crashPrefs = getSharedPreferences("crash_prefs", MODE_PRIVATE)
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            crashPrefs.edit().putString("last_crash", sw.toString()).apply()
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        super.onCreate(savedInstanceState)

        // যদি আগের বার ক্র্যাশ হয়ে থাকে, সেটা দেখাও
        val lastCrash = crashPrefs.getString("last_crash", null)
        if (lastCrash != null) {
            AlertDialog.Builder(this)
                .setTitle("আগের ক্র্যাশের বিস্তারিত")
                .setMessage(lastCrash)
                .setPositiveButton("ঠিক আছে") { _, _ ->
                    crashPrefs.edit().remove("last_crash").apply()
                }
                .setCancelable(false)
                .show()
            return
        }

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            prefs = getSharedPreferences("sync_prefs", MODE_PRIVATE)

            prefs.getString(KEY_FOLDER_URI, null)?.let {
                binding.tvFolderPath.text = Uri.parse(it).path ?: it
            }
            binding.etBotToken.setText(prefs.getString(KEY_BOT_TOKEN, ""))
            binding.etChatId.setText(prefs.getString(KEY_CHAT_ID, ""))
            binding.etInterval.setText(prefs.getInt(KEY_INTERVAL, 15).toString())
            binding.switchAutoSync.isChecked = prefs.getBoolean(KEY_AUTO_ON, false)

            binding.btnSelectFolder.setOnClickListener {
                folderPicker.launch(null)
            }

            binding.btnSave.setOnClickListener {
                saveSettings()
                Toast.makeText(this, "সেটিংস সেভ হয়েছে", Toast.LENGTH_SHORT).show()
            }

            binding.switchAutoSync.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(KEY_AUTO_ON, isChecked).apply()
                if (isChecked) startAutoSync() else stopAutoSync()
            }

            binding.btnSyncNow.setOnClickListener {
                saveSettings()
                val request = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(this).enqueue(request)
                binding.tvStatus.text = "সিঙ্ক শুরু হয়েছে..."
            }
        } catch (e: Exception) {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            AlertDialog.Builder(this)
                .setTitle("এরর হয়েছে")
                .setMessage(sw.toString())
                .setPositiveButton("ঠিক আছে", null)
                .show()
        }
    }

    private fun saveSettings() {
        val interval = binding.etInterval.text.toString().toIntOrNull() ?: 15
        prefs.edit()
            .putString(KEY_BOT_TOKEN, binding.etBotToken.text.toString().trim())
            .putString(KEY_CHAT_ID, binding.etChatId.text.toString().trim())
            .putInt(KEY_INTERVAL, if (interval < 15) 15 else interval)
            .apply()
    }

    private fun startAutoSync() {
        val interval = (prefs.getInt(KEY_INTERVAL, 15)).coerceAtLeast(15)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(interval.toLong(), TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        binding.tvStatus.text = "অটো সিঙ্ক চালু (প্রতি $interval মিনিটে)"
    }

    private fun stopAutoSync() {
        WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME)
        binding.tvStatus.text = "অটো সিঙ্ক বন্ধ"
    }

    companion object {
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_BOT_TOKEN = "bot_token"
        const val KEY_CHAT_ID = "chat_id"
        const val KEY_INTERVAL = "interval_minutes"
        const val KEY_AUTO_ON = "auto_on"
        const val WORK_NAME = "telesync_periodic_work"
    }
}
