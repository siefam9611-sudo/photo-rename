package com.example.photorename

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private val PICK_FOLDER_REQUEST = 42

    private val exifFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
    private val targetFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashFile = File(filesDir, "last_crash.txt")
        if (crashFile.exists()) {
            val crashText = crashFile.readText()
            crashFile.delete()
            val scroll = ScrollView(this)
            val tv = TextView(this).apply {
                text = "지난 실행에서 오류가 발생했습니다:\n\n$crashText"
                setPadding(24, 24, 24, 24)
                setTextIsSelectable(true)
            }
            scroll.addView(tv)
            setContentView(scroll)
            return
        }

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                crashFile.writeText(sw.toString())
            } catch (_: Exception) {
            }
            val restartIntent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(restartIntent)
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(1)
        }

        val scroll = ScrollView(this)
        val container = android.widget.LinearLayout(this)
        container.orientation = android.widget.LinearLayout.VERTICAL
        container.setPadding(32, 64, 32, 32)

        val pickButton = Button(this).apply {
            text = "폴더 선택 후 변환 시작"
            setOnClickListener { openFolderPicker() }
        }

        logView = TextView(this).apply {
            text = "폴더를 선택하면 변환 로그가 여기에 표시됩니다."
            setPadding(0, 32, 0, 0)
        }

        container.addView(pickButton)
        container.addView(logView)
        scroll.addView(container)
        setContentView(scroll)
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, PICK_FOLDER_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FOLDER_REQUEST && resultCode == Activity.RESULT_OK) {
            val treeUri: Uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val folder = DocumentFile.fromTreeUri(this, treeUri)
            if (folder != null) {
                renameAllImages(folder)
            }
        }
    }

    private fun renameAllImages(folder: DocumentFile) {
        val log = StringBuilder()
        val usedNames = HashSet<String>()

        val files = folder.listFiles().filter { it.isFile && isImage(it.name ?: "") }

        for (file in files) {
            try {
                val dateTaken = readExifDateTaken(file) ?: readLastModifiedAsDate(file)

                if (dateTaken == null) {
                    log.append("SKIP (날짜 정보 없음): ${file.name}\n")
                    continue
                }

                val ext = getExtension(file.name ?: "jpg")
                var baseName = targetFormat.format(dateTaken)
                var newName = "$baseName.$ext"

                var suffix = 1
                while (usedNames.contains(newName) || fileExistsInFolder(folder, newName)) {
                    newName = "${baseName}_$suffix.$ext"
                    suffix++
                }
                usedNames.add(newName)

                if (file.name == newName) {
                    log.append("동일함, 건너뜀: ${file.name}\n")
                    continue
                }

                val renamed = file.renameTo(newName)
                if (renamed) {
                    log.append("변경: ${file.name} -> $newName\n")
                } else {
                    log.append("실패: ${file.name}\n")
                }
            } catch (e: Exception) {
                log.append("오류(${file.name}): ${e.message}\n")
            }
        }

        logView.text = log.toString()
    }

    private fun readExifDateTaken(file: DocumentFile): java.util.Date? {
        return contentResolver.openInputStream(file.uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            dateStr?.let { exifFormat.parse(it) }
        }
    }

    private fun readLastModifiedAsDate(file: DocumentFile): java.util.Date? {
        val lastModified = file.lastModified()
        return if (lastModified > 0) java.util.Date(lastModified) else null
    }

    private fun isImage(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
    }

    private fun getExtension(name: String): String {
        return name.substringAfterLast('.', "jpg")
    }

    private fun fileExistsInFolder(folder: DocumentFile, name: String): Boolean {
        return folder.findFile(name) != null
    }
}
