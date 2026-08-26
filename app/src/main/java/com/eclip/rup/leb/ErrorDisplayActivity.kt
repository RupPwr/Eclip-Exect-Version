package com.eclip.rup.leb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.eclip.rup.leb.R

class ErrorDisplayActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_error_display)

        val errorCode = intent.getStringExtra("ERROR_CODE") ?: "LC-500"
        val errorLog = intent.getStringExtra("ERROR_LOG") ?: "No log available"
        val errorDesc = intent.getStringExtra("ERROR_DESCRIPTION") ?: "The application encountered an unexpected issue while processing your request."

        val appVersion = try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val vCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pInfo)
            "${pInfo.versionName} ($vCode)"
        } catch (e: Exception) {
            "1.0-beta"
        }

        val fullDiagnosticLog = """
            ==================================================
                          ECLIP CRASH REPORT                 
            ==================================================
            App Version : $appVersion
            Device      : ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})
            Error Code  : $errorCode
            
            --------------------------------------------------
            WHAT HAPPENED:
            --------------------------------------------------
            $errorDesc
            
            --------------------------------------------------
            STACK TRACE:
            --------------------------------------------------
            $errorLog
            ==================================================
        """.trimIndent()

        // Bind Views
        findViewById<TextView>(R.id.tvErrorCode).text = "Error Code: $errorCode"
        findViewById<TextView>(R.id.tvErrorDescription).text = errorDesc
        findViewById<TextView>(R.id.tvAppVersion).text = "App Version: $appVersion"
        findViewById<TextView>(R.id.tvDeviceModel).text = "Device: ${Build.MANUFACTURER} ${Build.MODEL}"
        findViewById<TextView>(R.id.tvAndroidVersion).text = "Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        findViewById<TextView>(R.id.tvErrorLog).text = errorLog

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        findViewById<ImageButton>(R.id.btnShare).setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, fullDiagnosticLog)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Error Log"))
        }

        findViewById<MaterialButton>(R.id.btnCopyError).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostic Log", fullDiagnosticLog))
            Toast.makeText(this, R.string.toast_log_copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btnRestartApp).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }
    }
}