package com.nishant.vurse

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaPlayer.OnPreparedListener
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.arthenica.mobileffmpeg.Config
import com.arthenica.mobileffmpeg.Config.RETURN_CODE_CANCEL
import com.arthenica.mobileffmpeg.Config.RETURN_CODE_SUCCESS
import com.arthenica.mobileffmpeg.FFmpeg
import com.arthenica.mobileffmpeg.StatisticsCallback
import com.google.android.material.snackbar.Snackbar
import com.nishant.vurse.databinding.ActivityMainBinding
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException


class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private val REQUEST_TAKE_GALLERY_VIDEO = 100
    private var r: Runnable? = null
    private var ffmpeg: FFmpeg? = null
    private var selectedVideoUri: Uri? = null
    private var stopPosition = 0
    private var filePath: String? = null
    private var duration = 0
    private var progressDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        binding = ActivityMainBinding.inflate(getLayoutInflater())
        setContentView(binding.root)
        progressDialog = createProgressDialog(this@MainActivity, "Please wait...");
        binding.btnUploadVideo.setOnClickListener(View.OnClickListener { if (Build.VERSION.SDK_INT >= 23) getPermission() else uploadVideo() })
        binding.btnCrop.setOnClickListener {
            if (selectedVideoUri != null) {
                executeTrimVideoCommand(
                    binding.rangeSeekBar.getSelectedMinValue().toInt().times(1000),
                    binding.rangeSeekBar.getSelectedMaxValue().toInt().times(1000)
                )
            } else Snackbar.make(binding.root, "Please upload a video", 4000).show()
        }
        binding.btnSpeed2x.setOnClickListener {
            if (selectedVideoUri != null) {
                executeFastMotionVideoCommand()
            } else Snackbar.make(binding.root, "Please upload a video", 4000).show()
        }

        binding.btnRemoveMusic.setOnClickListener {
            if (selectedVideoUri != null) {
                muteAudioVideo()
            } else Snackbar.make(binding.root, "Please upload a video", 4000).show()
        }

        binding.btnRotate.setOnClickListener {
            if (selectedVideoUri != null) {
                rotateVideo()
            } else Snackbar.make(binding.root, "Please upload a video", 4000).show()
        }

        if (!isExternalStorageAvailable() || isExternalStorageReadOnly()) {
            binding.btnSave.setEnabled(false);
        }

        binding.btnSave.setOnClickListener {
            try {
                val file =
                    filePath?.let { path -> File(path) }
                if (file != null) {
                    saveVideoToGallery(file)
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun isExternalStorageReadOnly(): Boolean {
        val extStorageState = Environment.getExternalStorageState()
        return if (Environment.MEDIA_MOUNTED_READ_ONLY == extStorageState) {
            true
        } else false
    }

    private fun isExternalStorageAvailable(): Boolean {
        val extStorageState = Environment.getExternalStorageState()
        return if (Environment.MEDIA_MOUNTED == extStorageState) {
            true
        } else false
    }

    private fun getPermission() {
        var params: Array<String>? = null
        val writeExternalStorage = Manifest.permission.WRITE_EXTERNAL_STORAGE
        val readExternalStorage = Manifest.permission.READ_EXTERNAL_STORAGE
        val hasWriteExternalStoragePermission: Int =
            ActivityCompat.checkSelfPermission(this, writeExternalStorage)
        val hasReadExternalStoragePermission: Int =
            ActivityCompat.checkSelfPermission(this, readExternalStorage)
        val permissions: MutableList<String> = ArrayList()
        if (hasWriteExternalStoragePermission != PackageManager.PERMISSION_GRANTED) permissions.add(
            writeExternalStorage
        )
        if (hasReadExternalStoragePermission != PackageManager.PERMISSION_GRANTED) permissions.add(
            readExternalStorage
        )
        if (!permissions.isEmpty()) {
            params = permissions.toTypedArray()
        }
        if (params != null && params.size > 0) {
            ActivityCompat.requestPermissions(
                this@MainActivity,
                params,
                100
            )
        } else uploadVideo()
    }

    /**
     * Opening gallery for uploading video
     */
    private fun uploadVideo() {
        try {
            val intent = Intent()
            intent.type = "video/*"
            intent.action = Intent.ACTION_GET_CONTENT
            resultLauncher.launch(intent)
        } catch (e: Exception) {
        }
    }

    var resultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // There are no request codes
                val data: Intent? = result.data
                if (data != null) {
                    selectedVideoUri = data.data
                }
                binding.videoview.setVideoURI(selectedVideoUri)
                binding.videoview.start()
                binding.videoview.setOnPreparedListener(OnPreparedListener { mp -> // TODO Auto-generated method stub
                    duration = mp.duration / 1000
                    binding.tvLeft.setText("00:00:00")
                    binding.tvRight.setText(getTime(mp.duration / 1000))
                    mp.isLooping = true
                    binding.rangeSeekBar.setRangeValues(0, duration)
                    binding.rangeSeekBar.setSelectedMinValue(0)
                    binding.rangeSeekBar.setSelectedMaxValue(duration)
                    binding.rangeSeekBar.setEnabled(true)
                    binding.rangeSeekBar.setOnRangeSeekBarChangeListener { bar, minValue, maxValue ->
                        binding.videoview.seekTo(minValue as Int * 1000)
                        binding.tvLeft.setText(getTime(bar.getSelectedMinValue() as Int))
                        binding.tvRight.setText(getTime(bar.getSelectedMaxValue() as Int))
                    }

                    val handler = Handler()
                    handler.postDelayed(Runnable {
                        if (binding.videoview.getCurrentPosition() >= binding.rangeSeekBar.getSelectedMaxValue()
                                ?.toInt()?.times(1000)!!
                        ) binding.rangeSeekBar.getSelectedMinValue()?.toInt()?.times(1000)
                            ?.let { binding.videoview.seekTo(it) }
                        r?.let { handler.postDelayed(it, 1000) }
                    }.also { r = it }, 1000)
                })
            }
        }

    private fun getTime(seconds: Int): String {
        val hr = seconds / 3600
        val rem = seconds % 3600
        val mn = rem / 60
        val sec = rem % 60
        return String.format("%02d", hr) + ":" + String.format(
            "%02d",
            mn
        ) + ":" + String.format("%02d", sec)
    }

    private fun showUnsupportedExceptionDialog() {
        AlertDialog.Builder(this@MainActivity)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle("Not Supported")
            .setMessage("Device Not Supported")
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok,
                DialogInterface.OnClickListener { dialog, which -> finish() })
            .create()
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (grantResults.size > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            uploadVideo()
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onPause() {
        super.onPause()
        stopPosition = binding.videoview.getCurrentPosition() //stopPosition is an int
        binding.videoview.pause()
    }

    override fun onResume() {
        super.onResume()
        binding.videoview.seekTo(stopPosition)
        binding.videoview.start()
    }

    /**
     * Command for cropping video
     */
    private fun executeTrimVideoCommand(startMs: Int, endMs: Int) {
        val moviesDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MOVIES
        )
        val filePrefix = "trim_video"
        val fileExtn = ".mp4"
        val yourRealPath: String? = selectedVideoUri?.let { getPath(this@MainActivity, it) }
        var dest = File(moviesDir, filePrefix + fileExtn)
        var fileNo = 0
        while (dest.exists()) {
            fileNo++
            dest = File(moviesDir, filePrefix + fileNo + fileExtn)
        }
        Log.d(TAG, "startTrim: src: $yourRealPath")
        Log.d(TAG, "startTrim: dest: " + dest.absolutePath)
        Log.d(TAG, "startTrim: startMs: $startMs")
        Log.d(TAG, "startTrim: endMs: $endMs")
        filePath = dest.absolutePath
        //String[] complexCommand = {"-i", yourRealPath, "-ss", "" + startMs / 1000, "-t", "" + endMs / 1000, dest.getAbsolutePath()};
        val complexCommand = arrayOf<String>(
            "-ss",
            "" + startMs / 1000,
            "-y",
            "-i",
            yourRealPath!!,
            "-t",
            "" + (endMs - startMs) / 1000,
            "-vcodec",
            "mpeg4",
            "-b:v",
            "2097152",
            "-b:a",
            "48000",
            "-ac",
            "2",
            "-ar",
            "22050",
            filePath!!
        )
        execFFmpegBinary(complexCommand)

    }

    /**
     * Command for creating fast motion video
     */
    private fun executeFastMotionVideoCommand() {
        val moviesDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MOVIES
        )
        val filePrefix = "speed_video"
        val fileExtn = ".mp4"
        val yourRealPath = getPath(this@MainActivity, selectedVideoUri!!)
        var dest = File(moviesDir, filePrefix + fileExtn)
        var fileNo = 0
        while (dest.exists()) {
            fileNo++
            dest = File(moviesDir, filePrefix + fileNo + fileExtn)
        }
        Log.d(TAG, "startTrim: src: $yourRealPath")
        Log.d(TAG, "startTrim: dest: " + dest.absolutePath)
        filePath = dest.absolutePath
        val complexCommand = arrayOf(
            "-y",
            "-i",
            yourRealPath!!,
            "-filter_complex",
            "[0:v]setpts=0.5*PTS[v];[0:a]atempo=2.0[a]",
            "-map",
            "[v]",
            "-map",
            "[a]",
            "-b:v",
            "2097k",
            "-r",
            "60",
            "-vcodec",
            "mpeg4",
            filePath!!
        )
        execFFmpegBinary(complexCommand)
    }

    /**
     * Command for mute audio from video
     */
    private fun muteAudioVideo() {
        val moviesDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MOVIES
        )
        val filePrefix = "without_audio"
        val fileExtn = ".mp4"
        val yourRealPath = getPath(this@MainActivity, selectedVideoUri!!)
        var dest = File(moviesDir, filePrefix + fileExtn)
        var fileNo = 0
        while (dest.exists()) {
            fileNo++
            dest = File(moviesDir, filePrefix + fileNo + fileExtn)
        }
        Log.d(TAG, "startTrim: src: $yourRealPath")
        Log.d(TAG, "startTrim: dest: " + dest.absolutePath)
        filePath = dest.absolutePath
        val complexCommand = arrayOf(
            "-i",
            yourRealPath!!,
            "-an",
            filePath!!
        )
        execFFmpegBinary(complexCommand)
    }

    /**
     * Command for rotate video
     */
    private fun rotateVideo() {
        val moviesDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MOVIES
        )
        val filePrefix = "rotate_video"
        val fileExtn = ".mp4"
        val yourRealPath = getPath(this@MainActivity, selectedVideoUri!!)
        var dest = File(moviesDir, filePrefix + fileExtn)
        var fileNo = 0
        while (dest.exists()) {
            fileNo++
            dest = File(moviesDir, filePrefix + fileNo + fileExtn)
        }
        Log.d(TAG, "startTrim: src: $yourRealPath")
        Log.d(TAG, "startTrim: dest: " + dest.absolutePath)
        filePath = dest.absolutePath
        /*val complexCommand = arrayOf(
            "-i",
            yourRealPath!!,
            "-map_metadata",
            "0",
            "-metadata:s:v",
            "rotate=\"90\"",
            "-codec",
            "copy",
            filePath!!
        )*/
        val complexCommand = arrayOf(
            "-i",
            yourRealPath!!,
            "-vf",
            "vflip",
            filePath!!
        )
        execFFmpegBinary(complexCommand)
    }

    /**
     * Executing ffmpeg binary
     */
    private fun execFFmpegBinary(command: Array<String>) {
        try {

            Config.enableStatisticsCallback(StatisticsCallback {
                runOnUiThread(Runnable {
                    showProgressDialog()
                })
            })

            FFmpeg.executeAsync(
                command
            ) { executionId, returnCode ->
                runOnUiThread(Runnable {
                    hideProgressDialog()
                })
                if (returnCode == RETURN_CODE_SUCCESS) {
                    Log.i(Config.TAG, "Async command execution completed successfully.")
                    binding.videoview.setVideoURI(Uri.parse(filePath))
                    binding.videoview.start()
                } else if (returnCode == RETURN_CODE_CANCEL) {
                    Log.i(Config.TAG, "Async command execution cancelled by user.")
                } else {
                    Log.i(
                        Config.TAG,
                        String.format(
                            "Async command execution failed with returnCode=%d.",
                            returnCode
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun deleteDir(dir: File): Boolean {
        if (dir.isDirectory) {
            val children = dir.list()
            if (children != null) {
                for (i in children.indices) {
                    val success = deleteDir(File(dir, children[i]))
                    if (!success) {
                        return false
                    }
                }
            }
        }
        return dir.delete()
    }

    fun saveVideoToGallery(videoFile: File){
        val uriSavedVideo: Uri?
        var createdvideo: File? = null
        val resolver = contentResolver
        val videoFileName = "video_" + System.currentTimeMillis() + ".mp4"
        val valuesvideos: ContentValues
        valuesvideos = ContentValues()

        if (Build.VERSION.SDK_INT >= 29) {
            valuesvideos.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/" + "Vurse")
            valuesvideos.put(MediaStore.Video.Media.TITLE, videoFileName)
            valuesvideos.put(MediaStore.Video.Media.DISPLAY_NAME, videoFileName)
            valuesvideos.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            valuesvideos.put(
                MediaStore.Video.Media.DATE_ADDED,
                System.currentTimeMillis() / 1000
            )
            val collection =
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            uriSavedVideo = resolver.insert(collection, valuesvideos)
            Snackbar.make(binding.root, "Video saved in Gallery.", 4000).show()
        } else {
            val directory = (Environment.getExternalStorageDirectory().absolutePath
                    + File.separator + Environment.DIRECTORY_MOVIES + "/" + "Vurse")
            createdvideo = File(directory, videoFileName)
            valuesvideos.put(MediaStore.Video.Media.TITLE, videoFileName)
            valuesvideos.put(MediaStore.Video.Media.DISPLAY_NAME, videoFileName)
            valuesvideos.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            valuesvideos.put(
                MediaStore.Video.Media.DATE_ADDED,
                System.currentTimeMillis() / 1000
            )
            valuesvideos.put(MediaStore.Video.Media.DATA, createdvideo.absolutePath)
            uriSavedVideo = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                valuesvideos
            )

            Snackbar.make(binding.root, "Video saved in Gallery.", 4000).show()
        }

        if (Build.VERSION.SDK_INT >= 29) {
            valuesvideos.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            valuesvideos.put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val pfd: ParcelFileDescriptor?
        try {
            pfd = contentResolver.openFileDescriptor(uriSavedVideo!!, "w")
            val out = FileOutputStream(pfd!!.fileDescriptor)
            // get the already saved video as fileinputstream
            // The Directory where your file is saved
            val storageDir = File(
                getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "Folder"
            )
            val `in` = FileInputStream(videoFile)
            val buf = ByteArray(8192)
            var len: Int
            while (`in`.read(buf).also { len = it } > 0) {
                out.write(buf, 0, len)
            }
            out.close()
            `in`.close()
            pfd!!.close()
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }

        if (Build.VERSION.SDK_INT >= 29) {
            valuesvideos.clear()
            valuesvideos.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uriSavedVideo!!, valuesvideos, null, null)
        }
    }

    /**
     * Get a file path from a Uri. This will get the the path for Storage Access
     * Framework Documents, as well as the _data field for the MediaStore and
     * other file-based ContentProviders.
     */
    private fun getPath(context: Context, uri: Uri): String? {
        val isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT

        // DocumentProvider
        if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":").toTypedArray()
                val type = split[0]
                if ("primary".equals(type, ignoreCase = true)) {
                    return Environment.getExternalStorageDirectory().toString() + "/" + split[1]
                }

                // TODO handle non-primary volumes
            } else if (isDownloadsDocument(uri)) {
                val id = DocumentsContract.getDocumentId(uri)
                val contentUri = ContentUris.withAppendedId(
                    Uri.parse("content://downloads/public_downloads"), java.lang.Long.valueOf(id)
                )
                return getDataColumn(context, contentUri, null, null)
            } else if (isMediaDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":").toTypedArray()
                val type = split[0]
                var contentUri: Uri? = null
                if ("image" == type) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                } else if ("video" == type) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else if ("audio" == type) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                val selection = "_id=?"
                val selectionArgs = arrayOf(
                    split[1]
                )
                return getDataColumn(context, contentUri, selection, selectionArgs)
            }
        } else if ("content".equals(uri.scheme, ignoreCase = true)) {
            return getDataColumn(context, uri, null, null)
        } else if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }
        return null
    }

    private fun getDataColumn(
        context: Context, uri: Uri?, selection: String?,
        selectionArgs: Array<String>?
    ): String? {
        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(
            column
        )
        try {
            cursor = context.contentResolver.query(
                uri!!, projection, selection, selectionArgs,
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val column_index = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(column_index)
            }
        } finally {
            cursor?.close()
        }
        return null
    }


    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    private fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }

    fun createProgressDialog(context: Context, text: String?): AlertDialog? {
        val builder: AlertDialog.Builder = AlertDialog.Builder(context)
        val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        if (inflater != null) {
            val dialogView: View = inflater.inflate(R.layout.progress_dialog_layout, null)
            builder.setView(dialogView)
            val textView: TextView = dialogView.findViewById(R.id.progressDialogText)
            if (textView != null) {
                textView.text = text
            }
        }
        builder.setCancelable(false)
        return builder.create()
    }

    fun showProgressDialog() {
        progressDialog?.show()
    }

    fun hideProgressDialog() {
        progressDialog?.dismiss()
    }
}