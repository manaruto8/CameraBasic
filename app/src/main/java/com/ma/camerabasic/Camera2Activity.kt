package com.ma.camerabasic

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Camera
import android.hardware.camera2.*
import android.hardware.camera2.CameraCaptureSession.CaptureCallback
import android.hardware.camera2.params.MeteringRectangle
import android.media.*
import android.os.*
import android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener
import com.ma.camerabasic.databinding.ActivityCamera2Binding
import com.ma.camerabasic.utils.CameraConfig
import com.ma.camerabasic.utils.CameraUtils
import com.ma.camerabasic.utils.CameraUtils.Companion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import kotlin.collections.ArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


class Camera2Activity : BaseActivity<ActivityCamera2Binding>() {

    /** Maximum number of images that will be held in the reader's buffer */
    private val IMAGE_BUFFER_SIZE: Int = 8

    /** Maximum time allowed to wait for the result of an image capture */
    private val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

    /** Milliseconds used for UI animations */
    private val ANIMATION_FAST_MILLIS = 50L

    private val VIDEO_MIME_TYPE = "video/avc"
    private val AUDIO_MIME_TYPE = "audio/mp4a-latm"

    private val animationTask: Runnable by lazy {
        Runnable {
            mBinding.overlay.background = Color.argb(150, 255, 255, 255).toDrawable()
            mBinding.overlay.postDelayed({
                mBinding.overlay.background = null
            }, ANIMATION_FAST_MILLIS)
        }
    }

    private val FRONT_CAMERAID: String = "1"
    private val BACK_CAMERAID: String = "0"
    private val RECORDER_VIDEO_BITRATE: Int = 10_000_000


    private lateinit var cameraManager: CameraManager
    private lateinit var characteristics: CameraCharacteristics
    private lateinit var imageReader: ImageReader
    private lateinit var yuvImageReader: ImageReader
    private lateinit var rawImageReader: ImageReader
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }
    private val imageReaderHandler = Handler(imageReaderThread.looper)
    private lateinit var mCamera: CameraDevice
    private lateinit var  mSession: CameraCaptureSession
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private lateinit var  mediaSurface:Surface
    private lateinit var mediaRecorder:MediaRecorder
    private var flashMode = CameraMetadata.FLASH_MODE_OFF
    private var flashAEMode = CameraMetadata.CONTROL_AE_MODE_ON
    private var flashAEPrecapture = CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
    private var cameraId=BACK_CAMERAID
    private lateinit var captureRequest:CaptureRequest.Builder
    private lateinit var previewSize:Size
    private lateinit var pictureSize:Size
    private lateinit var yuvSize:Size
    private lateinit var rawSize:Size
    private var videoStatus = CameraConfig.VideoState.PREVIEW
    private var mode = CameraConfig.CameraMode.PHOTO
    private var ratio = CameraConfig.CameraRatio.RECTANGLE_4_3
    private var relative: Int=0
    private var supportYUV: Boolean=false
    private var supportRAW: Boolean=false
    private var file: File? = null
    private var preCaptureReady = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityCamera2Binding.inflate(layoutInflater)
        setContentView(mBinding.root)
        initView()
    }


    @SuppressLint("ClickableViewAccessibility")
    override fun initView() {
        mBinding.ivCamera.setOnClickListener {
            if(mode == CameraConfig.CameraMode.PHOTO) {
                if (flashMode == CameraMetadata.FLASH_MODE_SINGLE) {
                    creatPreCapture()
                } else {
                    takePicture()
                }
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
            cameraId=if(cameraId==BACK_CAMERAID){
                FRONT_CAMERAID
            }else{
                BACK_CAMERAID
            }
            if (mCamera!=null){
                resetCamera()
            }
        }
        mBinding.tvFlash.setOnClickListener {
            if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) {
                Toast.makeText(this@Camera2Activity, "不支持闪光灯", Toast.LENGTH_SHORT ).show()
                return@setOnClickListener
            }
            if(flashMode==CameraMetadata.FLASH_MODE_OFF){
                if (mode == CameraConfig.CameraMode.PHOTO) {
                    mBinding.tvFlash.text = "自动"
                    setFlashDrawable(R.drawable.ic_flash_auto)
                    flashMode = CameraMetadata.FLASH_MODE_SINGLE
                    flashAEMode = CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                    flashAEPrecapture = CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START
                } else {
                    mBinding.tvFlash.text = "常亮"
                    setFlashDrawable(R.drawable.ic_flash_light)
                    flashMode = CameraMetadata.FLASH_MODE_TORCH
                    flashAEMode = CameraMetadata.CONTROL_AE_MODE_ON
                    flashAEPrecapture = CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
                    creatPreviewCapture()
                }

            }else if(flashMode==CameraMetadata.FLASH_MODE_SINGLE
                && flashAEMode == CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH){
                mBinding.tvFlash.text = "开启"
                setFlashDrawable(R.drawable.ic_flash_on)
                flashMode = CameraMetadata.FLASH_MODE_SINGLE
                flashAEMode = CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                flashAEPrecapture = CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START

            }else if(flashMode==CameraMetadata.FLASH_MODE_SINGLE
                && flashAEMode == CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH){
                mBinding.tvFlash.text = "常亮"
                setFlashDrawable(R.drawable.ic_flash_light)
                flashMode = CameraMetadata.FLASH_MODE_TORCH
                flashAEMode = CameraMetadata.CONTROL_AE_MODE_ON
                flashAEPrecapture = CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
                creatPreviewCapture()

            }else{
                mBinding.tvFlash.text = "关闭"
                setFlashDrawable(R.drawable.ic_flash_off)
                flashMode = CameraMetadata.FLASH_MODE_OFF
                flashAEMode = CameraMetadata.CONTROL_AE_MODE_ON
                flashAEPrecapture = CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
                creatPreviewCapture()
            }
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
                flashMode = CameraMetadata.FLASH_MODE_OFF
                flashAEMode = CameraMetadata.CONTROL_AE_MODE_ON
                flashAEPrecapture = CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE

                creatPreviewCapture()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {

            }

            override fun onTabReselected(tab: TabLayout.Tab?) {

            }

        } )
        mBinding.textureView.setOnTouchListener { _, motionEvent ->

            runBlocking {
                showFocus(motionEvent.x, motionEvent.y)
            }

            val screenW = windowManager.defaultDisplay.width
            val screenH = windowManager.defaultDisplay.height


            var realPreviewWidth = previewSize.height
            var realPreviewHeight = previewSize.width

            val focusX = realPreviewWidth.toFloat() / screenW * motionEvent.x
            val focusY = realPreviewHeight.toFloat() / screenH * motionEvent.y

            val totalPicSize = captureRequest.get(CaptureRequest.SCALER_CROP_REGION)

            val cutDx = (totalPicSize!!.height() - previewSize.height) / 2
            val width = 60
            val height = 60

            val rect = Rect(focusY.toInt(), focusX.toInt() + cutDx, (focusY + height).toInt(), (focusX + cutDx + width).toInt())

            creatFocusCapture(rect)
            false
        }
        checkPremission()
    }


    private fun checkPremission() {
        if(Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            val intent = Intent(ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            startActivity(intent)
        }
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

        mBinding.textureView.surfaceTextureListener= object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) {
                Log.e(TAG, "onSurfaceTextureAvailable $p1 - $p2 " )
                initCamera()
            }

            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {
                Log.e(TAG, "onSurfaceTextureSizeChanged $p1 - $p2 " )
            }

            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean {
                Log.e(TAG, "onSurfaceTextureDestroyed" )
                return false
            }

            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {
                //Log.e(TAG, "onSurfaceTextureUpdated" )
            }

        }

//        mBinding.surfaceView.holder.addCallback(object : SurfaceHolder.Callback{
//            override fun surfaceCreated(p0: SurfaceHolder) {
//                Log.e(TAG, "surfaceCreated" )
//                initCamera()
//            }
//
//            override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
//                Log.e(TAG, "surfaceChanged $p1 - $p2 - $p3" )
//            }
//
//            override fun surfaceDestroyed(p0: SurfaceHolder) {
//                Log.e(TAG, "surfaceDestroyed" )
//
//            }
//
//        })
    }


    @SuppressLint("InlinedApi")
    private fun initCamera() = lifecycleScope.launch(Dispatchers.Main){
        cameraManager=getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIds = cameraManager.cameraIdList.filter {
            val characteristics = cameraManager.getCameraCharacteristics(it)
            val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ?: false
        }

        Log.e(TAG, "initCamera:id num ${cameraIds.size}" )
        cameraIds.forEach { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            Log.e(TAG, "initCamera:id $id" )
            Log.e(TAG, "initCamera:facing ${characteristics.get(CameraCharacteristics.LENS_FACING)}" )
            Log.e(TAG, "initCamera:API ${characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)}" )
//            Log.e(TAG, "initCamera:zoom ${characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)}" )
//            Log.e(TAG, "initCamera:resolution ${characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)}" )
//            Log.e(TAG, "initCamera:flash ${characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)}" )
        }

        characteristics=cameraManager.getCameraCharacteristics(cameraId)
        Log.e(TAG, "initCamera:select id ${cameraId}" )

        mCamera=openCamera(cameraManager,cameraId,cameraHandler)
        val orientationEventListener = object : OrientationEventListener(applicationContext) {
            override fun onOrientationChanged(orientation: Int) {
                val rotation = when {
                    orientation <= 45 -> Surface.ROTATION_0
                    orientation <= 135 -> Surface.ROTATION_90
                    orientation <= 225 -> Surface.ROTATION_180
                    orientation <= 315 -> Surface.ROTATION_270
                    else -> Surface.ROTATION_0
                }
                relative = CameraUtils.computeRelativeRotation(characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!, rotation,cameraId == FRONT_CAMERAID)
            }
        }
        orientationEventListener.enable()

        startPreview()
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.e(TAG, "Camera $cameraId onDisconnected")
                finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId onError: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }


    @SuppressLint("MissingPermission")
    private fun startPreview()= lifecycleScope.launch(Dispatchers.Main){
        val cameraConfig = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

        cameraConfig.getOutputSizes(SurfaceTexture::class.java).forEach{
            Log.e(TAG, "startPreview preview: ${it.width} * ${it.height}" )
        }
        cameraConfig.getOutputSizes(ImageFormat.JPEG).forEach{
            Log.e(TAG, "startPreview jpeg: ${it.width} * ${it.height}" )
        }
        if (cameraConfig.isOutputSupportedFor(ImageFormat.YUV_420_888)) {
            supportYUV = true
            cameraConfig.getOutputSizes(ImageFormat.YUV_420_888).forEach {
                Log.e(TAG, "startPreview yuv: ${it.width} * ${it.height}")
            }
        }else {
            supportYUV = false
            Log.e(TAG, "startPreview nosupport yuv")
        }
        if (cameraConfig.isOutputSupportedFor(ImageFormat.RAW_SENSOR)) {
            supportRAW = true
            cameraConfig.getOutputSizes(ImageFormat.RAW_SENSOR).forEach {
                Log.e(TAG, "startPreview rawsensor: ${it.width} * ${it.height}")
            }
        }else {
            supportRAW = false
            Log.e(TAG, "startPreview nosupport rawsensor")
        }
        if (cameraConfig.isOutputSupportedFor(ImageFormat.RAW10)) {
            cameraConfig.getOutputSizes(ImageFormat.RAW10).forEach {
                Log.e(TAG, "startPreview raw10: ${it.width} * ${it.height}")
            }
        }else {
            Log.e(TAG, "startPreview nosupport raw10")
        }
        if (cameraConfig.isOutputSupportedFor(ImageFormat.RAW12)) {
            cameraConfig.getOutputSizes(ImageFormat.RAW12).forEach {
                Log.e(TAG, "startPreview raw12: ${it.width} * ${it.height}")
            }
        }else {
            Log.e(TAG, "startPreview nosupport raw12")
        }

        previewSize=CameraUtils.chooseSize(cameraConfig.getOutputSizes(SurfaceTexture::class.java),windowManager.defaultDisplay.height, windowManager.defaultDisplay.width, ratio.size, true)
        pictureSize=CameraUtils.chooseSize(cameraConfig.getOutputSizes(ImageFormat.JPEG),windowManager.defaultDisplay.height, windowManager.defaultDisplay.width, ratio.size, false)
        if (supportYUV) {
            yuvSize=cameraConfig.getOutputSizes(ImageFormat.YUV_420_888)[0]
        }
        if (supportRAW) {
            rawSize=cameraConfig.getOutputSizes(ImageFormat.RAW_SENSOR)[0]
        }

        mBinding.textureView.setAspectRatio(previewSize.height, previewSize.width)
        mBinding.textureView.surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
        //mBinding.surfaceView.setAspectRatio(previewSize.width,previewSize.height)

        Log.e(TAG, "startPreview bestPreviewSize: ${previewSize.width} * ${previewSize.height}" )
        Log.e(TAG, "startPreview bestPictureSize: ${pictureSize.width} * ${pictureSize.height}" )

        imageReader = ImageReader.newInstance(
            pictureSize.width,
            pictureSize.height,
            ImageFormat.JPEG,
            IMAGE_BUFFER_SIZE)

        if (supportYUV) {
            yuvImageReader = ImageReader.newInstance(
                yuvSize.width,
                yuvSize.height,
                ImageFormat.YUV_420_888,
                IMAGE_BUFFER_SIZE
            )
        }

        if (supportRAW) {
            rawImageReader = ImageReader.newInstance(
                rawSize.width,
                rawSize.height,
                ImageFormat.RAW_SENSOR,
                IMAGE_BUFFER_SIZE
            )
        }
        val surface = Surface(mBinding.textureView.surfaceTexture)
        //val surface = mBinding.surfaceView.holder.surface
        val targets = ArrayList<Surface>()
        targets.add(surface)
        targets.add(imageReader.surface)

        if (supportYUV) {
            targets.add(yuvImageReader.surface)
        }

        if (supportRAW) {
            targets.add(rawImageReader.surface)
        }


        mSession = createCaptureSession(mCamera, targets, cameraHandler)

        if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) {
            mBinding.tvFlash.text = "关闭"
            setFlashDrawable(R.drawable.ic_flash_off)
            flashMode = CameraMetadata.FLASH_MODE_OFF
            flashAEMode = CameraMetadata.CONTROL_AE_MODE_ON
            flashAEPrecapture = CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
        }

        creatPreviewCapture()
    }

    private fun creatPreviewCapture(){

        captureRequest = mCamera.createCaptureRequest(
            CameraDevice.TEMPLATE_PREVIEW
        ).apply {
            addTarget(Surface(mBinding.textureView.surfaceTexture))
            set(CaptureRequest.FLASH_MODE, flashMode)
            set(CaptureRequest.CONTROL_AE_MODE, flashAEMode)
        }
        mSession.setRepeatingRequest(captureRequest.build(), captureCallBack, cameraHandler)
    }

    private fun creatPreCapture(){
        val preCaptureRequest = mCamera.createCaptureRequest(
            CameraDevice.TEMPLATE_PREVIEW
        ).apply {
            addTarget(Surface(mBinding.textureView.surfaceTexture))
            set(CaptureRequest.FLASH_MODE, flashMode)
            set(CaptureRequest.CONTROL_AE_MODE, flashAEMode)
            set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, flashAEPrecapture)
        }
        preCaptureReady = true
        mSession.capture(preCaptureRequest.build(), captureCallBack, cameraHandler)
    }

    private fun creatFocusCapture(rect: Rect){
        val focusCaptureRequest = mCamera.createCaptureRequest(
            CameraDevice.TEMPLATE_PREVIEW
        ).apply {
            addTarget(Surface(mBinding.textureView.surfaceTexture))
            set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(rect, 1000)))
            set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(MeteringRectangle(rect, 1000)))
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        }
        preCaptureReady = true
        mSession.capture(focusCaptureRequest.build(), captureCallBack, cameraHandler)
    }

    private val captureCallBack = object :CaptureCallback(){

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
            val state = result.get(CaptureResult.CONTROL_AE_STATE)
            if (state == CaptureResult.CONTROL_AE_STATE_PRECAPTURE && preCaptureReady) {
                preCaptureReady = false
                takePicture()
            }
        }
    }

    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e(TAG, exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }


    private fun takePicture(){

        @Suppress("ControlFlowWithEmptyBody")
        while (imageReader.acquireNextImage() != null) { }
        val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.e(TAG, "Image available in queue: ${image.width} - ${image.height} - ${image.format} - ${image.timestamp} - ${image.planes.size} ")
            imageQueue.add(image)
        }, imageReaderHandler)

        rawImageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.e(TAG, "RAW available in queue: ${image.width} - ${image.height} - ${image.format} - ${image.timestamp} - ${image.planes.size} ")
            saveRAW(image)
            image.close()
        }, imageReaderHandler)

        yuvImageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.e(TAG, "YUV available in queue: ${image.width} - ${image.height} - ${image.format} - ${image.timestamp} - ${image.planes.size} ")
            saveYUV(image)
            image.close()
        }, imageReaderHandler)

        val capturePictureRequest = mSession.device.createCaptureRequest(
            CameraDevice.TEMPLATE_STILL_CAPTURE)
            .apply {
                addTarget(imageReader.surface)

                if (supportYUV ) {
                    addTarget(yuvImageReader.surface)
                }

                if (supportRAW) {
                    addTarget(rawImageReader.surface)
                }

            }
        capturePictureRequest.set(CaptureRequest.FLASH_MODE, flashMode)
        capturePictureRequest.set(CaptureRequest.CONTROL_AE_MODE, flashAEMode)
        mSession.capture(capturePictureRequest.build(), object : CameraCaptureSession.CaptureCallback() {

            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long) {
                super.onCaptureStarted(session, request, timestamp, frameNumber)
                //mBinding.textureView.post(animationTask)
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult) {
                super.onCaptureCompleted(session, request, result)

                val image = imageQueue.take()
                savePicture(image)
                image.close()

            }
        }, cameraHandler)
    }


    private fun savePicture(image: Image) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
        file =CameraUtils.getOutputMediaFile("Camera2", "jpg")
        FileOutputStream(file).use {
            it.write(bytes)
            it.close()
        }
        val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
        Log.e(TAG, "savePicture relative = $relative   mirrored = $mirrored" )
        val exifOrientation = CameraUtils.computeExifOrientation(relative, mirrored)
        val exif = ExifInterface(file!!.absolutePath)
        exif.setAttribute(
            ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
        exif.saveAttributes()

        CameraUtils.showThumbnail(this, file, mBinding.ivAlbum)

    }

    private fun saveRAW(image: Image){
        if(Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            Log.e(TAG, "no premission: ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION YUV cannot be saved ")
            return
        }
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
        FileOutputStream(CameraUtils.getOutputMediaFile("Camera2", "raw")).use { it.write(bytes) }

    }

    private fun saveYUV(image: Image) {
        if(Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            Log.e(TAG, "no premission: ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION Raw cannot be saved ")
            return
        }
        val bytes = CameraUtils.YUV_420_888toNV21(image)
        FileOutputStream(CameraUtils.getOutputMediaFile("Camera2", "yuv")).use { it.write(bytes) }
    }


    private fun startRecording()= lifecycleScope.launch(Dispatchers.Main) {
        Log.e(TAG, "startRecording" )
        val cameraConfig = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

        cameraConfig.getOutputSizes(MediaRecorder::class.java).forEach{
            val frame = cameraConfig.getOutputMinFrameDuration(MediaRecorder::class.java, it)
            Log.e(TAG, "startRecording media: ${it.width} * ${it.height}---  ${(1/(frame / 1_000_000_000.0)).toInt()}" )
        }

        val mediaSize = CameraUtils.chooseSize(cameraConfig.getOutputSizes(MediaRecorder::class.java),windowManager.defaultDisplay.height, windowManager.defaultDisplay.width, ratio.size, false)
        val secondsPerFrame = cameraConfig.getOutputMinFrameDuration(MediaRecorder::class.java, mediaSize) / 1_000_000_000.0
        val mediaFps= if (secondsPerFrame > 0) (1.0 / secondsPerFrame).toInt() else 0
        Log.e(TAG, "startRecording bestMediaSize: ${mediaSize.width} * ${mediaSize.height}， $mediaFps Fps" )

        mediaSurface= if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            MediaCodec.createPersistentInputSurface()
        } else {
            val mediaCodec=MediaCodec.createDecoderByType(VIDEO_MIME_TYPE)
            mediaCodec.createInputSurface()
        }

        mediaRecorder=createRecorder(mediaSurface,mediaFps,mediaSize)
        val surface = Surface(mBinding.textureView.surfaceTexture)
        val targets = listOf(surface, mediaSurface)
        mSession = createCaptureSession(mCamera, targets, cameraHandler)

        val captureRequest = mCamera.createCaptureRequest(
            CameraDevice.TEMPLATE_RECORD
        ).apply {
            addTarget(surface)
            addTarget(mediaSurface)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(mediaFps, mediaFps))
            set(CaptureRequest.FLASH_MODE, flashMode)
        }
        mSession.setRepeatingRequest(captureRequest.build(), null, cameraHandler)
        mediaRecorder.start()

        mBinding.tvTips.visibility= View.VISIBLE
        mBinding.ivAlbum.visibility= View.GONE
        mBinding.ivSwitch.visibility= View.GONE
        mBinding.tlMode.visibility= View.GONE
        mBinding.llTop.visibility= View.GONE
        mBinding.ivCamera.setImageResource(R.drawable.ic_video_stop)

        videoStatus=CameraConfig.VideoState.RECORDING
    }


    private fun stopRecording() {
        Log.e(TAG, "stopRecording" )
        mediaRecorder.stop()
        releaseMediaRecorder()

        mBinding.tvTips.visibility= View.GONE
        mBinding.ivAlbum.visibility= View.VISIBLE
        mBinding.ivSwitch.visibility= View.VISIBLE
        mBinding.tlMode.visibility= View.VISIBLE
        mBinding.llTop.visibility= View.VISIBLE
        mBinding.ivCamera.setImageResource(R.drawable.ic_video)
        videoStatus=CameraConfig.VideoState.PREVIEW

        CameraUtils.showThumbnail(this, file, mBinding.ivAlbum)
        startPreview()
    }


    private fun createRecorder(surface: Surface, mediaFps:Int, mediaSize:  Size) = MediaRecorder().apply {

        file = CameraUtils.getOutputMediaFile("Camera2", "mp4")

        setAudioSource(MediaRecorder.AudioSource.MIC)
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(file?.path)
        setVideoEncodingBitRate(RECORDER_VIDEO_BITRATE)
        if (mediaFps > 0) setVideoFrameRate(mediaFps)
        setVideoSize(mediaSize.width, mediaSize.height)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setOrientationHint(relative)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            setInputSurface(surface)
        }else{
            setPreviewDisplay(surface)
        }
        prepare()
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
        mCamera.close()
        initCamera()
    }

    private fun releaseCamera() {
        try {
            mCamera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
    }

    private fun releaseMediaRecorder() {
        mediaRecorder.reset()
        mediaRecorder.release()
        mediaSurface.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseCamera()
    }
}