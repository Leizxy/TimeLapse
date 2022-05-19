package com.example.timelapse

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import android.view.Surface
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

/**
 * author: 80342867
 * created on: 2022/5/13 013 10:23
 * description: camera preview
 */
class CameraHelper(
    private val mContext: AppCompatActivity,
    private val mPreviewView: PreviewView,
    private var mWidth: Int = 640,
    private var mHeight: Int = 480,
    private var mLensFacing: Int = CameraSelector.LENS_FACING_BACK,
    private var callback: ((Boolean) -> Unit)? = null,
) : ImageAnalysis.Analyzer {
    private val TAG: String = "CameraHelper"
    private val mCameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var mCameraProvider: ProcessCameraProvider? = null
    private var mbRecording: Boolean = false
    private val lock: ReentrantLock = ReentrantLock()
    private lateinit var y: ByteArray
    private lateinit var u: ByteArray
    private lateinit var v: ByteArray
    private var mStartTime: Long = -1
    private val STEP: Long = 1000
    private var mVideoCodec: MediaCodec? = null
    private var mMediaMuxer: MediaMuxer? = null
    private var mVideoTrack: Int = -1

    init {
        ProcessCameraProvider.getInstance(mContext).apply {
            addListener({
                mCameraProvider = get()
                mLensFacing = when {
                    hasBackCamera() -> CameraSelector.LENS_FACING_BACK
                    hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
                    else -> throw IllegalStateException("Back and front camera are unavailable")
                }
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(mContext))

        }
    }

    private fun bindCameraUseCases() {
        val cameraSelector = CameraSelector.Builder().requireLensFacing(mLensFacing).build()

        val preview =
            Preview.Builder().build().also { it.setSurfaceProvider(mPreviewView.surfaceProvider) }

        val imageAnalysis =
            ImageAnalysis.Builder().setTargetRotation(Surface.ROTATION_90).build()
                .also { it.setAnalyzer(mCameraExecutor, this) }

        try {
            mCameraProvider?.run {
                unbindAll()
                bindToLifecycle(mContext, cameraSelector, preview, imageAnalysis)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hasFrontCamera(): Boolean {
        return mCameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }

    private fun hasBackCamera(): Boolean {
        return mCameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    fun startRecording() {
        Log.i(TAG, "startRecording: ")
        mbRecording = true

        prepareMuxer()
        mVideoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
            it.configure(getVideoFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }

        callback?.invoke(mbRecording)
    }

    private fun prepareMuxer() {
        if (null == mMediaMuxer) {
            try {
                mMediaMuxer = MediaMuxer(getFilePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } catch (e: Exception) {
                Log.e(TAG, "prepareMuxer: ", e)
            }
        }
    }

    private fun getFilePath(): String {
        val path = "${mContext.getExternalFilesDir(null)}/test.mp4"
        Log.i(TAG, "getFilePath: $path")
        val file = File(path)
        if (!file.exists()) {
            file.createNewFile()
        }
        return path
    }

    fun stopRecording() {
        Log.i(TAG, "stopRecording: ")
        mStartTime = -1L
        frameCount = 0
        mbRecording = false
        stopMuxer()
        callback?.invoke(mbRecording)
    }

    private fun stopMuxer() {
        try {
            mVideoTrack = -1
            mMediaMuxer?.run {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopMuxer: ", e)
        } finally {
            mMediaMuxer = null
        }
    }

    override fun analyze(image: ImageProxy) {
//        Log.i(TAG, "analyze: ${image.width} x ${image.height}")
        mWidth = image.width
        mHeight = image.height

        if (mbRecording) {
            var mbInputFrame = false

            if (mStartTime == -1L) {
                mStartTime = System.currentTimeMillis()
                mbInputFrame = true
            } else {
                val curTime = System.currentTimeMillis()
                if ((curTime - mStartTime) > STEP) {
                    mbInputFrame = true
                    mStartTime = curTime
                }
            }

            if (mbInputFrame) {
                val planes = image.planes
                if (!this::y.isInitialized) {
                    y = ByteArray(planes[0].buffer.limit() - planes[0].buffer.position())
                    u = ByteArray(planes[1].buffer.limit() - planes[1].buffer.position())
                    v = ByteArray(planes[2].buffer.limit() - planes[2].buffer.position())
                }

                if (image.planes[0].buffer.remaining() == y.size) {
                    planes[0].buffer.get(y)
                    planes[1].buffer.get(u)
                    planes[2].buffer.get(v)

                    // question
                    val nv21 = ByteArray(mHeight * mWidth * 3 / 2)
                    yuvToNv21(y, u, v, nv21, mWidth, mHeight)

                    encodeNv21(nv21)
                }
            }
        }

        image.close()
    }

    private fun encodeNv21(nv21: ByteArray) {
        mVideoCodec?.let {
            val inputBufferIndex = it.dequeueInputBuffer(0)
            val length = nv21.size
            var inputBuffer: ByteBuffer? = null
            if (inputBufferIndex >= 0) {
                inputBuffer = it.getInputBuffer(inputBufferIndex)?.also { buffer ->
                    buffer.clear()
                    buffer.put(nv21)
                }

                it.queueInputBuffer(inputBufferIndex, 0, length, System.nanoTime(), 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outBufferIndex = it.dequeueOutputBuffer(bufferInfo, 0)

            Log.i(TAG, "encodeNv21: $outBufferIndex")

            if (MediaCodec.INFO_OUTPUT_FORMAT_CHANGED == outBufferIndex) {
                if (mVideoTrack < 0) {
                    mMediaMuxer?.run {
                        mVideoTrack = addTrack(mVideoCodec!!.outputFormat)
                        start()
                    }
                }
            }

            while (outBufferIndex >= 0) {
                onOutputBufferAvailable(mVideoCodec!!, outBufferIndex, bufferInfo)
                outBufferIndex = it.dequeueOutputBuffer(bufferInfo, 0)
            }

            inputBuffer?.clear()
        }
    }

    private var frameCount: Int = 0
    private fun onOutputBufferAvailable(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo
    ) {
        var outputBuffer: ByteBuffer? = null

        try {
            outputBuffer = codec.getOutputBuffer(index)
        } catch (e: Exception) {
            Log.e(TAG, "onOutputBufferAvailable: ", e)
            return
        }

        // pts
        info.presentationTimeUs = (1000000 * frameCount / 30).toLong()
        frameCount++
        
        mMediaMuxer?.writeSampleData(mVideoTrack, outputBuffer!!, info)
        codec.releaseOutputBuffer(index, false)
    }

    private fun getVideoFormat(): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, mWidth, mHeight).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 400_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

    private fun yuvToNv21(
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
        nv21: ByteArray,
        stride: Int,
        height: Int,
    ) {
        System.arraycopy(y, 0, nv21, 0, y.size)
        val length = y.size + u.size / 2 + v.size / 2
        var uIndex = 0
        var vIndex = 0
        for (i in stride * height until length step 2) {
            nv21[i] = u[uIndex]
            nv21[i + 1] = v[vIndex]
            vIndex += 2
            uIndex += 2
        }
    }
}