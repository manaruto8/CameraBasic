package com.ma.camerabasic.utils

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.ExifInterface
import android.media.Image
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.util.Size
import android.view.Surface
import android.widget.ImageView
import com.ma.camerabasic.ShowResultActivity
import java.io.File
import java.nio.ReadOnlyBufferException
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.experimental.inv
import kotlin.math.abs


class CameraUtils {


    companion object {


        private val TAG = javaClass.simpleName

        fun computeRelativeRotation(
            sensorOrientationDegrees: Int,
            surfaceRotation: Int,
            mirrored: Boolean
        ): Int {
            Log.e(TAG, "computeRelativeRotation sensorOrientationDegrees = $sensorOrientationDegrees   surfaceRotation = $surfaceRotation  mirrored = $mirrored" )
            val deviceOrientationDegrees = when (surfaceRotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }

            val sign = if (mirrored) 1 else -1
            val degress = (sensorOrientationDegrees - (deviceOrientationDegrees * sign) + 360) % 360

//            Log.e(TAG, "computeRelativeRotation sensorOrientationDegrees $sensorOrientationDegrees")
//            Log.e(TAG, "computeRelativeRotation deviceOrientationDegrees $deviceOrientationDegrees")
//            Log.e(TAG, "computeRelativeRotation degress $degress")

            return degress
        }


        fun computeExifOrientation(rotationDegrees: Int, mirrored: Boolean) = when {
            rotationDegrees == 0 && !mirrored -> ExifInterface.ORIENTATION_NORMAL
            rotationDegrees == 0 && mirrored -> ExifInterface.ORIENTATION_FLIP_HORIZONTAL
            rotationDegrees == 180 && !mirrored -> ExifInterface.ORIENTATION_ROTATE_180
            rotationDegrees == 180 && mirrored -> ExifInterface.ORIENTATION_FLIP_VERTICAL
            rotationDegrees == 90 && !mirrored -> ExifInterface.ORIENTATION_ROTATE_90
            rotationDegrees == 90 && mirrored -> ExifInterface.ORIENTATION_TRANSPOSE
            rotationDegrees == 270 && !mirrored -> ExifInterface.ORIENTATION_ROTATE_270
            rotationDegrees == 270 && mirrored -> ExifInterface.ORIENTATION_TRANSVERSE
            else -> ExifInterface.ORIENTATION_UNDEFINED
        }


        fun chooseSize(
            choices: Array<Size>,
            maxWidth: Int,
            maxHeight: Int,
            aspectRatio: Size,
            preview: Boolean
        ): Size {

            var size = aspectRatio
            var select = ArrayList<Size>()

            if (aspectRatio.width == 0 || aspectRatio.width == 0) {
                size = Size(maxWidth, maxHeight)
            }

            choices.forEach {
                if (preview) {
                    if (it.width <= maxWidth && it.height <= maxHeight ) {
                        select.add(it)
                    }
                } else {
                    select.add(it)
                }
            }

            select.sortBy { selectSize: Size ->
                abs(size.width.toFloat()/size.height - selectSize.width.toFloat()/selectSize.height)
            }

            select.forEach {
                Log.e(TAG, "choosePictureSize : ${size.width.toFloat()/size.height} --- ${it.width}*${it.height}--- ${it.width.toFloat()/it.height}" )
            }

            return select[0]
        }


        fun YUV_420_888toNV21(image: Image): ByteArray {
            val width = image.width
            val height = image.height
            val ySize = width * height
            val uvSize = width * height / 4

            val nv21 = ByteArray(ySize + uvSize * 2)

            val yBuffer = image.planes[0].buffer // Y
            val uBuffer = image.planes[1].buffer // U
            val vBuffer = image.planes[2].buffer // V

            var rowStride = image.planes[0].rowStride
            assert(image.planes[0].pixelStride == 1)

            var pos = 0

            if (rowStride == width) { // likely
                yBuffer[nv21, 0, ySize]
                pos += ySize
            } else {
                var yBufferPos = -rowStride.toLong() // not an actual position
                while (pos < ySize) {
                    yBufferPos += rowStride.toLong()
                    yBuffer.position(yBufferPos.toInt())
                    yBuffer[nv21, pos, width]
                    pos += width
                }
            }

            rowStride = image.planes[2].rowStride
            val pixelStride = image.planes[2].pixelStride

            assert(rowStride == image.planes[1].rowStride)
            assert(pixelStride == image.planes[1].pixelStride)

            if (pixelStride == 2 && rowStride == width && uBuffer[0] == vBuffer[1]) {
                // maybe V an U planes overlap as per NV21, which means vBuffer[1] is alias of uBuffer[0]
                val savePixel = vBuffer[1]
                try {
                    vBuffer.put(1, savePixel.inv() as Byte)
                    if (uBuffer[0] == savePixel.inv() as Byte) {
                        vBuffer.put(1, savePixel)
                        vBuffer.position(0)
                        uBuffer.position(0)
                        vBuffer[nv21, ySize, 1]
                        uBuffer[nv21, ySize + 1, uBuffer.remaining()]

                        return nv21 // shortcut
                    }
                } catch (ex: ReadOnlyBufferException) {
                    // unfortunately, we cannot check if vBuffer and uBuffer overlap
                }

                // unfortunately, the check failed. We must save U and V pixel by pixel
                vBuffer.put(1, savePixel)
            }

            // other optimizations could check if (pixelStride == 1) or (pixelStride == 2),
            // but performance gain would be less significant
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    val vuPos = col * pixelStride + row * rowStride
                    nv21[pos++] = vBuffer[vuPos]
                    nv21[pos++] = uBuffer[vuPos]
                }
            }

            return nv21
        }

        fun getOutputMediaFile(name: String, format: String): File{
            val mediaStorageDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "CameraBasic${File.separator}${name}"
            )
            mediaStorageDir.apply {
                if (!exists()) {
                    if (!mkdirs()) {
                        Log.e(TAG, "failed to create directory")
                        return mediaStorageDir
                    }
                }
            }

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
            return File(
                mediaStorageDir.path + File.separator +
                        "${name}_${format}_${timeStamp}.${format}"
            )
        }


        fun showThumbnail(activity: Activity, file: File?, view: ImageView) {
            if (file == null ) return
            Log.e(TAG, "showThumbnail: ${file.name}" )

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                activity.runOnUiThread {
                    view.setImageURI(Uri.fromFile(file))
                }
                return
            }

            val thumbnail: Bitmap
            if (file.name.contains("mp4")) {
                thumbnail = ThumbnailUtils.createVideoThumbnail(file, Size(640, 480), null)
            } else {
                thumbnail = ThumbnailUtils.createImageThumbnail(file, Size(640, 480), null)
            }
            activity.runOnUiThread {
                view.setImageBitmap(thumbnail)
            }

        }


        fun showThumbnail(activity: Activity, uri: Uri?, view: ImageView) {
            if (uri == null ) return
            Log.e(TAG, "showThumbnail: ${uri.path}" )

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                activity.runOnUiThread {
                    view.setImageURI(uri)
                }
                return
            }

            val thumbnail: Bitmap?
            val cr = activity.contentResolver
            val mimeType = cr.getType(uri)
            if (mimeType!! == "video/mp4") {
                val mediaMetadataRetriever = MediaMetadataRetriever()
                mediaMetadataRetriever.setDataSource(activity, uri)
                thumbnail = mediaMetadataRetriever.frameAtTime
            } else {
                thumbnail =
                    activity.contentResolver.loadThumbnail(uri, Size(640, 480), null)
            }
            activity.runOnUiThread {
                view.setImageBitmap(thumbnail)
            }

        }

        fun showResult(activity: Activity, file: File?) {
            if (file == null ) return
            Log.e(TAG, "showResult: ${file.name}" )
            val type: Int
            if (file.name.contains("mp4")) {
                type = 1
            } else {
                type = 0
            }
            showResult(activity, Uri.fromFile(file).path, type)

        }

        fun showResult(activity: Activity, uri: String?, type: Int) {
            if (uri == null ) return
            Log.e(TAG, "showResult: ${uri}" )
            val intent =  Intent(activity, ShowResultActivity::class.java)
            intent.putExtra("type",type)
            intent.putExtra("uri",uri)
            activity.startActivity(intent)

        }

    }


}