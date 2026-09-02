package com.example.photorename

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private val PICK_FOLDER_REQUEST = 42
    private val PICK_FILES_REQUEST = 43
    private val WRITE_REQUEST_CODE = 44
    private val READ_MEDIA_PERMISSION_REQUEST = 45

    private val exifFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
    private val targetFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private var pendingRenames: MutableList<Pair<Uri, String>> = mutableListOf()

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
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), READ_MEDIA_PERMISSION_REQUEST)
            return
        }
        launchFilesPickerIntent()
    }

    private fun launchFilesPickerIntent() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, PICK_FILES_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == READ_MEDIA_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchFilesPickerIntent()
            } else {
                logView.text = "사진에 접근할 권한이 필요합니다. 다시 시도해주세요."
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            PICK_FOLDER_REQUEST -> {
                if (resultCode != Activity.RESULT_OK || data == null) return
                val treeUri: Uri = data.data ?: return
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val folder = DocumentFile.fromTreeUri(this, treeUri)
                if (folder != null) {
                    val files = folder.listFiles().filter { it.isFile && isImage(it.name ?: "") }
                    renameFilesInFolder(files, folder)
                }
            }
            PICK_FILES_REQUEST -> {
                if (resultCode != Activity.RESULT_OK || data == null) return
                val uris = mutableListOf<Uri>()
                val clipData = data.clipData
                if (clipData != null) {
                    for (i in 0 until clipData.itemCount) {
                        uris.add(clipData.getItemAt(i).uri)
                    }
                } else {
                    data.data?.let { uris.add(it) }
                }
                renameIndividualFilesViaMediaStore(uris)
            }
            WRITE_REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK) {
                    applyPendingRenames()
                } else {
                    logView.text = "권한 요청이 거부되어 변경하지 못했습니다. 다시 시도해주세요."
                    pendingRenames.clear()
                }
            }
        }
    }

    private fun renameFilesInFolder(files: List<DocumentFile>, folder: DocumentFile) {
        val log = StringBuilder()
        val usedNames = HashSet<String>()

        for (file in files) {
            try {
                val newName = computeNewName(file, usedNames) { name -> fileExistsInFolder(folder, name) }
                if (newName == null) {
                    log.append("SKIP (날짜 정보 없음): ${file.name}\n")
                    continue
                }

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
                log.append("오류(${file.name}): [${e.javaClass.simpleName}] ${e.message ?: e.toString()}\n")
            }
        }

        logView.text = if (log.isEmpty()) "변환할 이미지가 없습니다." else log.toString()
    }

    private fun renameIndividualFilesViaMediaStore(uris: List<Uri>) {
        val log = StringBuilder()
        val usedNames = HashSet<String>()
        pendingRenames.clear()

        for (uri in uris) {
            try {
                val docFile = DocumentFile.fromSingleUri(this, uri)
                val newName = computeNewName(docFile, usedNames) { false }
                if (newName == null) {
                    log.append("SKIP (날짜 정보 없음): ${docFile?.name}\n")
                    continue
                }

                if (docFile?.name == newName) {
                    log.append("동일함, 건너뜀: ${docFile?.name}\n")
                    continue
                }

                val mediaUri = resolveMediaStoreUri(uri)
                if (mediaUri == null) {
                    log.append("실패(지원 안되는 파일 형식): ${docFile?.name}\n")
                    continue
                }

                pendingRenames.add(mediaUri to newName)
            } catch (e: Exception) {
                log.append("오류: [${e.javaClass.simpleName}] ${e.message ?: e.toString()}\n")
            }
        }

        if (pendingRenames.isEmpty()) {
            logView.text = if (log.isEmpty()) "변환할 이미지가 없습니다." else log.toString()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createWriteRequest(
                    contentResolver,
                    pendingRenames.map { it.first }
                )
                startIntentSenderForResult(
                    pendingIntent.intentSender,
                    WRITE_REQUEST_CODE,
                    null, 0, 0, 0
                )
                logView.text = (log.toString() + "권한 확인 창이 뜹니다. 허용을 눌러주세요.").trim()
                return
            } catch (e: Exception) {
                log.append("권한 요청 실패: [${e.javaClass.simpleName}] ${e.message ?: e.toString()}\n")
            }
        }

        applyPendingRenames(log)
    }

    private fun applyPendingRenames(initialLog: StringBuilder = StringBuilder()) {
        val log = initialLog
        val toApply = pendingRenames.toList()
        pendingRenames.clear()

        for ((uri, newName) in toApply) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                }
                val rows = contentResolver.update(uri, values, null, null)
                if (rows > 0) {
                    log.append("변경 완료: -> $newName\n")
                } else {
                    log.append("실패(적용 안됨): $newName\n")
                }
            } catch (e: RecoverableSecurityException) {
                try {
                    val intentSender: IntentSender = e.userAction.actionIntent.intentSender
                    pendingRenames.add(uri to newName)
                    startIntentSenderForResult(intentSender, WRITE_REQUEST_CODE, null, 0, 0, 0)
                    return
                } catch (ex: Exception) {
                    log.append("권한 요청 실패: ${ex.message}\n")
                }
            } catch (e: Exception) {
                log.append("오류: [${e.javaClass.simpleName}] ${e.message ?: e.toString()}\n")
            }
        }

        logView.text = if (log.isEmpty()) "변환할 이미지가 없습니다." else log.toString()
    }

    private fun resolveMediaStoreUri(uri: Uri): Uri? {
        return try {
            val docId = DocumentsContract.getDocumentId(uri)
            val mime = contentResolver.getType(uri) ?: ""
            val isVideo = mime.startsWith("video")

            val idPart = docId.substringAfterLast(':')
            val numericId = idPart.toLongOrNull()
            if (numericId != null && (docId.startsWith("image") || docId.startsWith("video"))) {
                return if (isVideo) {
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, numericId)
                } else {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, numericId)
                }
            }

            if (docId.contains(':')) {
                val volume = docId.substringBefore(':')
                val relativePath = docId.substringAfter(':')
                val basePath = if (volume == "primary") {
                    android.os.Environment.getExternalStorageDirectory().absolutePath
                } else {
                    "/storage/$volume"
                }
                val fullPath = "$basePath/$relativePath"

                val collection = MediaStore.Files.getContentUri("external")
                val projection = arrayOf(MediaStore.Files.FileColumns._ID)
                contentResolver.query(
                    collection, projection,
                    "${MediaStore.Files.FileColumns.DATA} = ?",
                    arrayOf(fullPath), null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                        return if (isVideo) {
                            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        } else {
                            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun computeNewName(
        file: DocumentFile?,
        usedNames: MutableSet<String>,
        existsCheck: (String) -> Boolean
    ): String? {
        if (file == null) return null
        val dateTaken = readExifDateTaken(file) ?: readLastModifiedAsDate(file) ?: return null

        val ext = getExtension(file.name ?: "jpg")
        val baseName = targetFormat.format(dateTaken)
        var newName = "$baseName.$ext"

        var suffix = 1
        while (usedNames.contains(newName) || existsCheck(newName)) {
            newName = "${baseName}_$suffix.$ext"
            suffix++
        }
        usedNames.add(newName)
        return newName
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
