package com.example.photorename

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private val PICK_FOLDER_REQUEST = 42
    private val PICK_FILES_REQUEST = 43

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

        val scroll = ScrollView(this)
        val container = android.widget.LinearLayout(this)
        container.orientation = android.widget.LinearLayout.VERTICAL
        container.setPadding(32, 64, 32, 32)

        val pickFolderButton = Button(this).apply {
            text = "폴더 전체 변환"
            setOnClickListener { openFolderPicker() }
        }

        val pickFilesButton = Button(this).apply {
            text = "파일 여러 개 선택해서 변환"
            setOnClickListener { openFilesPicker() }
        }

        logView = TextView(this).apply {
            text = "폴더 또는 파일들을 선택하면 변환 로그가 여기에 표시됩니다."
            setPadding(0, 32, 0, 0)
        }

        container.addView(pickFolderButton)
        container.addView(pickFilesButton)
        container.addView(logView)
        scroll.addView(container)
        setContentView(scroll)
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, PICK_FOLDER_REQUEST)
    }

    private fun openFilesPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, PICK_FILES_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data == null) return

        when (requestCode) {
            PICK_FOLDER_REQUEST -> {
                val treeUri: Uri = data.data ?: return
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val folder = DocumentFile.fromTreeUri(this, treeUri)
                if (folder != null) {
                    val files = folder.listFiles().filter { it.isFile && isImage(it.name ?: "") }
                    renameFiles(files, folder)
                }
            }
            PICK_FILES_REQUEST -> {
                val uris = mutableListOf<Uri>()
                val clipData = data.clipData
                if (clipData != null) {
                    for (i in 0 until clipData.itemCount) {
                        uris.add(clipData.getItemAt(i).uri)
                    }
                } else {
                    data.data?.let { uris.add(it) }
                }

                val files = uris.mapNotNull { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                    DocumentFile.fromSingleUri(this, uri)
                }.filterNotNull()

                renameFiles(files, null)
            }
        }
    }

    private fun renameFiles(files: List<DocumentFile>, parentFolder: DocumentFile?) {
        val log = StringBuilder()
        val usedNames = HashSet<String>()

        for (file in files) {
            try {
                val dateTaken = readExifDateTaken(file) ?: readLastModifiedAsDate(file)

                if (dateTaken == null) {
                    log.append("SKIP (날짜 정보 없음): ${file.name}\n")
                    continue
                }

                val ext = getExtension(file.name ?: "jpg")
                val baseName = targetFormat.format(dateTaken)
                var newName = "$baseName.$ext"

                var suffix = 1
                while (usedNames.contains(newName) ||
                    (parentFolder != null && fileExistsInFolder(parentFolder, newName))
                ) {
                    newName = "${baseName}_$suffix.$ext"
                    suffix++
                }
                usedNames.add(newName)

                if (file.name == newName) {
                    log.append("동일함, 건너뜀: ${file.name}\n")
                    continue
                }

                // 폴더 선택 모드는 renameTo()가 지원되지만,
                // 개별 파일 선택 모드는 renameTo()가 UnsupportedOperationException을 던진다.
                // DocumentsContract.renameDocument()를 직접 호출해서 우회한다.
                val renamed = if (parentFolder != null) {
                    file.renameTo(newName)
                } else {
                    try {
                        DocumentsContract.renameDocument(contentResolver, file.uri, newName) != null
                    } catch (_: Exception) {
                        false
                    }
                }

                if (renamed) {
                    log.append("변경: ${file.name} -> $newName\n")
                } else {
                    log.append("실패: ${file.name}\n")
                }
            } catch (e: Exception) {
                log.append("오류(${file.name}): [${e.javaClass.simpleName}] ${e.message ?: e.toString()}\n")
            }
        }

        logView.text = if (log.isEmpty()) "변환할 이미지가 없습니다." else log.toString()
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
