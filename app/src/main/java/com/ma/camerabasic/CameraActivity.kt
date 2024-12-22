package com.ma.camerabasic


import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager

import android.graphics.Rect
import android.hardware.Camera
import android.hardware.camera2.CameraCharacteristics
import android.media.ExifInterface
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener

import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener
import com.ma.camerabasic.databinding.ActivityCameraBinding
import com.ma.camerabasic.utils.CameraConfig
import com.ma.camerabasic.utils.CameraUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList


class CameraActivity : BaseActivity<ActivityCameraBinding>(),Camera.PreviewCallback,Camera.PictureCallback,Camera.AutoFocusCallback {

    private var lensFacing: Int = Camera.CameraInfo.CAMERA_FACING_BACK
    private var flashMode: String =  Camera.Parameters.FLASH_MODE_OFF
    private var mSurfaceHolder: SurfaceHolder?=null
    private var mCamera: Camera?=null
    private var mParameters: Camera.Parameters?=null
    private var mediaRecorder:MediaRecorder?=null
    private var videoStatus = CameraConfig.VideoState.PREVIEW
    private var mode = CameraConfig.CameraMode.PHOTO
    private var ratio = CameraConfig.CameraRatio.RECTANGLE_4_3
    private var relative:Int=0
    private var file: File? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
        initView()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun initView() {
        mBinding.ivCamera.setOnClickListener {
            if(mode == CameraConfig.CameraMode.PHOTO) {
                mCamera?.takePicture(null,null,this)
            } else if (mode == CameraConfig.CameraMode.VIDEO){
                if(videoStatus == CameraConfig.VideoState.PREVIEW) {
                    startRecording()
                } else {
                    stopRecording()
                }
            }
        }
        mBinding.ivAlbum.setOnClickListener {
            if (file != null) {
                CameraUtils.showResult(this, file)
            } else {

            }
        }
        mBinding.ivSwitch.setOnClickListener {
            lensFacing = if(lensFacing==Camera.CameraInfo.CAMERA_FACING_BACK){
                Camera.CameraInfo.CAMERA_FACING_FRONT
            }else{
                Camera.CameraInfo.CAMERA_FACING_BACK
            }
            resetCamera()
        }
        mBinding.tvFlash.setOnClickListener {
            if (mParameters?.supportedFlashModes?.contains(Camera.Parameters.FLASH_MODE_AUTO) != true) {
                Toast.makeText(this@CameraActivity, "不支持闪光灯", Toast.LENGTH_SHORT ).show()
                return@setOnClickListener
            }
            flashMode = if(flashMode == Camera.Parameters.FLASH_MODE_OFF){
                if (mode == CameraConfig.CameraMode.PHOTO) {
                    mBinding.tvFlash.text = "自动"
                    setFlashDrawable(R.drawable.ic_flash_auto)
                    Camera.Parameters.FLASH_MODE_AUTO
                } else {
                    mBinding.tvFlash.text = "常亮"
                    setFlashDrawable(R.drawable.ic_flash_light)
                    Camera.Parameters.FLASH_MODE_TORCH
                }

            } else if (flashMode == Camera.Parameters.FLASH_MODE_AUTO) {
                mBinding.tvFlash.text = "开启"
                setFlashDrawable(R.drawable.ic_flash_on)
                Camera.Parameters.FLASH_MODE_ON

            } else if (flashMode == Camera.Parameters.FLASH_MODE_ON) {
                mBinding.tvFlash.text = "常亮"
                setFlashDrawable(R.drawable.ic_flash_light)
                Camera.Parameters.FLASH_MODE_TORCH

            } else {
                mBinding.tvFlash.text = "关闭"
                setFlashDrawable(R.drawable.ic_flash_off)
                Camera.Parameters.FLASH_MODE_OFF
            }

            mParameters?.flashMode=flashMode
            mCamera?.parameters=mParameters
        }
        mBinding.tvRatio.setOnClickListener {
            ratio = if (ratio == CameraConfig.CameraRatio.RECTANGLE_4_3) {
                mBinding.tvRatio.text = "16:9"
                CameraConfig.CameraRatio.RECTANGLE_16_9
            } else if (ratio == CameraConfig.CameraRatio.RECTANGLE_16_9) {
                mBinding.tvRatio.text = "全屏"
                CameraConfig.CameraRatio.FUll
            } else if (ratio == CameraConfig.CameraRatio.FUll) {
                mBinding.tvRatio.text = "1:1"
                CameraConfig.CameraRatio.SQUARE
            } else if (ratio == CameraConfig.CameraRatio.SQUARE) {
                CameraConfig.CameraRatio.RECTANGLE_4_3
                mBinding.tvRatio.text = "4:3"
                CameraConfig.CameraRatio.RECTANGLE_4_3
            } else {
                mBinding.tvRatio.text = "4:3"
                CameraConfig.CameraRatio.RECTANGLE_4_3
            }
            resetCamera()
        }
        mBinding.tlMode.addOnTabSelectedListener(object: OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val position = tab?.position
                if (position == 0) {
                    mode = CameraConfig.CameraMode.PHOTO
                    mBinding.ivCamera.setImageResource(R.drawable.ic_camera)

                } else {
                    mode = CameraConfig.CameraMode.VIDEO
                    mBinding.ivCamera.setImageResource(R.drawable.ic_video)

                }

                mBinding.tvFlash.text = "关闭"
                setFlashDrawable(R.drawable.ic_flash_off)
                flashMode = Camera.Parameters.FLASH_MODE_OFF

                mParameters?.flashMode=flashMode
                mCamera?.parameters=mParameters
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {

            }

            override fun onTabReselected(tab: TabLayout.Tab?) {

            }

        } )

        mBinding.surfaceView.setOnTouchListener { _, motionEvent ->

            runBlocking {
                showFocus(motionEvent.x, motionEvent.y)
            }

            val areaX = (motionEvent.x / mBinding.surfaceView.width * 2000) - 1000
            val areaY = (motionEvent.y / mBinding.surfaceView.height * 2000) - 1000

            val focusArea = Rect()
            focusArea.left = (areaX.toInt() - 100).coerceAtLeast(-1000)
            focusArea.top = (areaY.toInt() - 100).coerceAtLeast(-1000)
            focusArea.right = (areaX.toInt() + 100).coerceAtMost(1000)
            focusArea.bottom = (areaY.toInt() + 100).coerceAtMost(1000)

            val cameraArea = Camera.Area(focusArea, 1000)
            val meteringAreas: MutableList<Camera.Area> = ArrayList()
            val focusAreas: MutableList<Camera.Area> = ArrayList()
            mParameters?.let {
                if (it.maxNumMeteringAreas > 0) {
                    meteringAreas.add(cameraArea)
                    focusAreas.add(cameraArea)
                }
                it.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
                it.focusAreas = focusAreas
                it.meteringAreas = meteringAreas
            }
            mCamera?.let {
                try {
                    it.cancelAutoFocus()
                    it.parameters = mParameters
                    it.autoFocus(this)
                } catch (e:Exception) {
                }
            }
            false
        }
        checkPremission()
    }


    private fun checkPremission() {
        if ( ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            premissionLuncher.launch(arrayOf( Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        } else {
            setHolder()
        }
    }

    private val premissionLuncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
        setHolder()
    }

    private fun setHolder(){
        mSurfaceHolder= mBinding.surfaceView.holder
        mSurfaceHolder?.addCallback(object :SurfaceHolder.Callback{
            override fun surfaceCreated(p0: SurfaceHolder) {
                Log.e(TAG, "surfaceCreated" )
                openCamera()
            }

            override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
                Log.e(TAG, "surfaceChanged $p2 - $p3" )
            }

            override fun surfaceDestroyed(p0: SurfaceHolder) {
                Log.e(TAG, "surfaceDestroyed" )
            }

        })
    }

    override fun onAutoFocus(p0: Boolean, p1: Camera?) {

    }


    override fun onPreviewFrame(p0: ByteArray?, p1: Camera?) {

    }


    @SuppressLint("ClickableViewAccessibility")
    private fun openCamera() {
        if (!hasCamera(lensFacing)) return
        try {
            Log.e(TAG, "initCamera:select id ${lensFacing}" )
            mCamera=Camera.open(lensFacing)
            mParameters= mCamera?.parameters

            mBinding.tvFlash.text = "关闭"
            setFlashDrawable(R.drawable.ic_flash_off)
            mParameters?.flashMode = Camera.Parameters.FLASH_MODE_OFF

            val previewSizes = ArrayList<Size> ()
            mParameters?.supportedPreviewSizes?.forEach{
                    previewSizes.add(Size(it.width, it.height))
                    Log.e(TAG, "startPreview preview: ${it.width} * ${it.height}" )
                }

            val pictureSizes = ArrayList<Size> ()
            mParameters?.supportedPictureSizes?.forEach{
                pictureSizes.add(Size(it.width, it.height))
                Log.e(TAG, "startPreview jpeg: ${it.width} * ${it.height}" )
            }

            val previewSize= CameraUtils.chooseSize(previewSizes.toTypedArray(),windowManager.defaultDisplay.height, windowManager.defaultDisplay.width, ratio.size, true)
            val pictureSize= CameraUtils.chooseSize(pictureSizes.toTypedArray(),windowManager.defaultDisplay.height, windowManager.defaultDisplay.width, ratio.size, false)
            mParameters?.setPictureSize(pictureSize.width,pictureSize.height)
            mParameters?.setPreviewSize(previewSize.width,previewSize.height)
            mBinding.surfaceView.setAspectRatio(previewSize.width,previewSize.height)

            Log.e(TAG, "startPreview bestPreviewSize: ${previewSize.width} * ${previewSize.height}" )
            Log.e(TAG, "startPreview bestPictureSize: ${pictureSize.width} * ${pictureSize.height}" )

            mParameters?.setRotation(getCameraDisplayOrientation())
            mCamera?.parameters=mParameters
            mCamera?.setPreviewCallback(this)
            mCamera?.setPreviewDisplay(mSurfaceHolder)
            mCamera?.setDisplayOrientation(getCameraDisplayOrientation())
            mCamera?.startPreview()

            val orientationEventListener = object : OrientationEventListener(applicationContext) {
                override fun onOrientationChanged(orientation: Int) {
                    val rotation = when {
                        orientation <= 45 -> Surface.ROTATION_0
                        orientation <= 135 -> Surface.ROTATION_90
                        orientation <= 225 -> Surface.ROTATION_180
                        orientation <= 315 -> Surface.ROTATION_270
                        else -> Surface.ROTATION_0
                    }
                    val info = Camera.CameraInfo()
                    Camera.getCameraInfo(lensFacing, info)
                    relative = CameraUtils.computeRelativeRotation(info.orientation, rotation,lensFacing == Camera.CameraInfo.CAMERA_FACING_FRONT)
                }
            }
            orientationEventListener.enable()
        } catch (e: Exception) {
            Log.e(TAG, "open fail ${e.message}" )
        }
    }


    private fun hasCamera(lensFacing: Int): Boolean {
        var info = Camera.CameraInfo()
        var cameraNum=0
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            Log.e(TAG, "initCamera:id $i" )
            Log.e(TAG, "initCamera:facing ${info.facing}" )
            if (info.facing ==lensFacing){
                cameraNum++
            }
        }
        return cameraNum>0
    }


    override fun onPictureTaken(p0: ByteArray?, p1: Camera?) {
        p1?.startPreview()

        file = CameraUtils.getOutputMediaFile("Camera1", "jpg")

        try {
            FileOutputStream(file).use {
                it.write(p0)
                it.close()
            }
            val mirrored = lensFacing == Camera.CameraInfo.CAMERA_FACING_FRONT
            Log.e(TAG, "savePicture relative = $relative   mirrored = $mirrored" )
            val exifOrientation = CameraUtils.computeExifOrientation(relative, mirrored)
            val exif = ExifInterface(file!!.absolutePath)
            exif.setAttribute(
                ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
            exif.saveAttributes()
        } catch (e: FileNotFoundException) {
            Log.d(TAG, "File not found: ${e.message}")
        } catch (e: IOException) {
            Log.d(TAG, "Error accessing file: ${e.message}")
        }

        CameraUtils.showThumbnail(this, file, mBinding.ivAlbum)
    }



    private fun startRecording() {

        mediaRecorder = MediaRecorder()
        mCamera?.let{
            it.unlock()
            mediaRecorder?.run {
                setCamera(mCamera)

                val videoSizes = ArrayList<Size> ()
                mParameters?.supportedVideoSizes?.forEach {
                    videoSizes.add(Size(it.width, it.height))
                    Log.e(TAG, "startRecording media: ${it.width} * ${it.height}" )
                }
                val videoSize = CameraUtils.chooseSize(videoSizes.toTypedArray(),windowManager.defaultDisplay.height, windowManager.defaultDisplay.width, ratio.size, false)
                Log.e(TAG, "startPreview bestVideoSize: ${videoSize.width} * ${videoSize.height}" )
                file = CameraUtils.getOutputMediaFile("Camera1", "mp4")

                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.CAMERA)
                setOutputFile(file?.path)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT)
                setVideoEncoder(MediaRecorder.VideoEncoder.DEFAULT)
                setOrientationHint(getCameraDisplayOrientation())
                setVideoSize(videoSize.width, videoSize.height)
                setPreviewDisplay(mBinding.surfaceView.holder.surface)
                try {
                    prepare()
                    start()

                    mBinding.tvTips.visibility= View.VISIBLE
                    mBinding.ivAlbum.visibility= View.GONE
                    mBinding.ivSwitch.visibility= View.GONE
                    mBinding.tlMode.visibility= View.GONE
                    mBinding.llTop.visibility= View.GONE
                    mBinding.ivCamera.setImageResource(R.drawable.ic_video_stop)

                    videoStatus=CameraConfig.VideoState.RECORDING

                } catch (e: IllegalStateException) {
                    Log.e(TAG, "IllegalStateException preparing MediaRecorder: " + e.message)
                    releaseMediaRecorder()
                    return
                } catch (e: IOException) {
                    Log.e(TAG, "IOException preparing MediaRecorder: " + e.message)
                    releaseMediaRecorder()
                    return
                }
            }
        }

    }

    private fun stopRecording() {
        mediaRecorder?.stop()
        releaseMediaRecorder()

        mBinding.tvTips.visibility= View.GONE
        mBinding.ivAlbum.visibility= View.VISIBLE
        mBinding.ivSwitch.visibility= View.VISIBLE
        mBinding.tlMode.visibility= View.VISIBLE
        mBinding.llTop.visibility= View.VISIBLE
        mBinding.ivCamera.setImageResource(R.drawable.ic_video)

        videoStatus=CameraConfig.VideoState.PREVIEW

        CameraUtils.showThumbnail(this, file, mBinding.ivAlbum)
    }

    private fun getCameraDisplayOrientation():Int{
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(lensFacing, info)
        val rotation = windowManager.defaultDisplay.rotation
        var degrees = 0
        when (rotation) {
            Surface.ROTATION_0 -> degrees = 0
            Surface.ROTATION_90 -> degrees = 90
            Surface.ROTATION_180 -> degrees = 180
            Surface.ROTATION_270 -> degrees = 270
        }
        var result: Int
        if (info .facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360
            result = (360 - result) % 360 // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360
        }
        Log.e(TAG, "setCameraDisplayOrientation: degrees：" + degrees + "---rotation：" + info.orientation + "---result：" + result)
        return result
    }

    val job = lifecycleScope.launch {
        delay(3000)
        mBinding.ivFocus.visibility = View.INVISIBLE
    }

    private suspend fun showFocus(x: Float, y: Float) {
        job.cancel()
        mBinding.ivFocus.visibility = View.VISIBLE
        mBinding.ivFocus.x = x - mBinding.ivFocus.width / 2
        mBinding.ivFocus.y = mBinding.surfaceView.top + y - mBinding.ivFocus.height / 2
        job.join()
    }



    private fun setFlashDrawable(@DrawableRes res: Int){
        val drawable = ContextCompat.getDrawable(this, res)
        drawable?.setBounds(0, 0, drawable.minimumWidth, drawable.minimumHeight)
        mBinding.tvFlash.setCompoundDrawables(null,drawable,null,null)
    }

    private fun resetCamera() {
        releaseCamera()
        openCamera()
    }

    private fun releaseCamera() {
        mCamera?.setPreviewCallback(null)
        mCamera?.stopPreview()
        mCamera?.release()
    }

    private fun releaseMediaRecorder() {
        mediaRecorder?.reset()
        mediaRecorder?.release()
        mCamera?.lock()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaRecorder()
        releaseCamera()
    }
}