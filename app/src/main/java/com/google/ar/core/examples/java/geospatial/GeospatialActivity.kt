/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.examples.java.geospatial

import android.content.SharedPreferences
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.GuardedBy
import androidx.appcompat.app.AppCompatActivity
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.fragment.app.DialogFragment
import androidx.navigation.fragment.NavHostFragment
import androidx.room.Room
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.ar.core.*
import com.google.ar.core.Anchor.TerrainAnchorState
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.examples.java.common.helpers.*
import com.google.ar.core.examples.java.common.samplerender.*
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.examples.java.common.samplerender.arcore.PlaneRenderer
import com.google.ar.core.examples.java.database.Node
import com.google.ar.core.examples.java.database.NodeDatabase
import com.google.ar.core.exceptions.*
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Main activity for the Geospatial API example.
 *
 *
 * This example shows how to use the Geospatial APIs. Once the device is localized, anchors can
 * be created at the device's geospatial location. Anchor locations are persisted across sessions
 * and will be recreated once localized.
 */
class GeospatialActivity : AppCompatActivity(), SampleRender.Renderer,
    VpsAvailabilityNoticeDialogFragment.NoticeDialogListener,
    PrivacyNoticeDialogFragment.NoticeDialogListener {
    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private var surfaceView: GLSurfaceView? = null
    private var installRequested = false
    private var clearedAnchorsAmount: Int? = null

    /** Timer to keep track of how much time has passed since localizing has started.  */
    private var localizingStartTimestamp: Long = 0

    /** Deadline for showing resolving terrain anchors no result yet message.  */
    private var deadlineForMessageMillis: Long = 0
    private val anchorParameterSet: Set<String>? = null

    internal enum class State {
        /** The Geospatial API has not yet been initialized.  */
        UNINITIALIZED,

        /** The Geospatial API is not supported.  */
        UNSUPPORTED,

        /** The Geospatial API has encountered an unrecoverable error.  */
        EARTH_STATE_ERROR,

        /** The Session has started, but [Earth] isn't [TrackingState.TRACKING] yet.  */
        PRETRACKING,

        /**
         * [Earth] is [TrackingState.TRACKING], but the desired positioning confidence
         * hasn't been reached yet.
         */
        LOCALIZING,

        /** The desired positioning confidence wasn't reached in time.  */
        LOCALIZING_FAILED,

        /**
         * [Earth] is [TrackingState.TRACKING] and the desired positioning confidence has
         * been reached.
         */
        LOCALIZED
    }

    private var state = State.UNINITIALIZED
    private var session: Session? = null
    private val messageSnackbarHelper = SnackbarHelper()
    private var displayRotationHelper: DisplayRotationHelper? = null
    private val trackingStateHelper = TrackingStateHelper(this)
    private var render: SampleRender? = null
    private var sharedPreferences: SharedPreferences? = null
    private val pendingTerrainAnchors = HashMap<Anchor, TerrainAnchorResolveListener>()
    private var lastStatusText: String? = null
    private var view: View? = null
    private var textView: TextView? = null
    private var navHostView: View? = null
    private var planeRenderer: PlaneRenderer? = null
    private var backgroundRenderer: BackgroundRenderer? = null
    private var virtualSceneFramebuffer: Framebuffer? = null
    private var hasSetTextureNames = false

    // Set WGS84 anchor or Terrain anchor.
    private var isTerrainAnchorMode = false

    // Virtual object (ARCore geospatial)
    private var virtualObjectMesh: Mesh? = null
    private var geospatialAnchorVirtualObjectShader: Shader? = null

    // Virtual object (ARCore geospatial terrain)
    private var terrainAnchorVirtualObjectShader: Shader? = null
    private val anchors: MutableList<Anchor> = ArrayList()

    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val modelViewMatrix = FloatArray(16) // view x model
    private val modelViewProjectionMatrix = FloatArray(16) // projection x view x model

    // Locks needed for synchronization
    private val singleTapLock = Any()

    @GuardedBy("singleTapLock")
    private var queuedSingleTap: MotionEvent? = null

    // Tap handling and UI.
    private var gestureDetector: GestureDetector? = null

    // Point Cloud
    private var pointCloudVertexBuffer: VertexBuffer? = null
    private var pointCloudMesh: Mesh? = null
    private var pointCloudShader: Shader? = null

    // Keep track of the last point cloud rendered to avoid updating the VBO if point cloud
    // was not changed.  Do this using the timestamp since we can't compare PointCloud objects.
    private var lastPointCloudTimestamp: Long = 0

    // Provides device location.
    private var fusedLocationClient: FusedLocationProviderClient? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPreferences = getPreferences(MODE_PRIVATE)

        //Extracting UI elements By ID
        setContentView(R.layout.activity_main)
        surfaceView = findViewById(R.id.surfaceview)
        view = findViewById(R.id.view)
        textView = findViewById(R.id.textView)
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragmentContainerView) as NavHostFragment?
        navHostView = navHostFragment!!.view
        println(navHostView)
        displayRotationHelper = DisplayRotationHelper( /*activity=*/this)

        // Set up renderer.
        render = SampleRender(surfaceView, this, assets)
        installRequested = false
        clearedAnchorsAmount = null

        // Set up touch listener.
        gestureDetector = GestureDetector(
            this,
            object : SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    synchronized(singleTapLock) { queuedSingleTap = e }
                    return true
                }

                override fun onDown(e: MotionEvent): Boolean {
                    return true
                }
            })
        surfaceView!!.setOnTouchListener(OnTouchListener { v: View?, event: MotionEvent? ->
            gestureDetector!!.onTouchEvent(
                event!!
            )
        })
        fusedLocationClient = LocationServices.getFusedLocationProviderClient( /*context=*/this)
    }

    override fun onDestroy() {
        if (session != null) {
            // Explicitly close ARCore Session to release native resources.
            // Review the API reference for important considerations before calling close() in apps with
            // more complicated lifecycle requirements:
            // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
            session!!.close()
            session = null
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (sharedPreferences!!.getBoolean(ALLOW_GEOSPATIAL_ACCESS_KEY,  /*defValue=*/false)) {
            createSession()
        } else {
            showPrivacyNoticeDialog()
        }
        surfaceView!!.onResume()
        displayRotationHelper!!.onResume()
    }

    private fun showPrivacyNoticeDialog() {
        val dialog: DialogFragment = PrivacyNoticeDialogFragment.createDialog()
        dialog.show(supportFragmentManager, PrivacyNoticeDialogFragment::class.java.name)
    }

    private fun createSession() {
        var exception: Exception? = null
        var message: String? = null
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    InstallStatus.INSTALLED -> {}
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this)
                    return
                }
                if (!LocationPermissionHelper.hasFineLocationPermission(this)) {
                    LocationPermissionHelper.requestFineLocationPermission(this)
                    return
                }

                // Create the session.
                // Plane finding mode is default on, which will help the dynamic alignment of terrain
                // anchors on ground.
                session = Session( /* context= */this)
            } catch (e: UnavailableArcoreNotInstalledException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableUserDeclinedInstallationException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                message = "Please update ARCore"
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                message = "Please update this app"
                exception = e
            } catch (e: UnavailableDeviceNotCompatibleException) {
                message = "This device does not support AR"
                exception = e
            } catch (e: Exception) {
                message = "Failed to create AR session"
                exception = e
            }
            if (message != null) {
                messageSnackbarHelper.showError(this, message)
                Log.e(TAG, "Exception creating session", exception)
                return
            }
        }
        // Check VPS availability before configure and resume session.
        if (session != null) {
            lastLocation
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            configureSession()
            // To record a live camera session for later playback, call
            // `session.startRecording(recordingConfig)` at anytime. To playback a previously recorded AR
            // session instead of using the live camera feed, call
            // `session.setPlaybackDatasetUri(Uri)` before calling `session.resume()`. To
            // learn more about recording and playback, see:
            // https://developers.google.com/ar/develop/java/recording-and-playback
            session!!.resume()
        } catch (e: CameraNotAvailableException) {
            message = "Camera not available. Try restarting the app."
            exception = e
        } catch (e: GooglePlayServicesLocationLibraryNotLinkedException) {
            message =
                "Google Play Services location library not linked or obfuscated with Proguard."
            exception = e
        } catch (e: FineLocationPermissionNotGrantedException) {
            message = "The Android permission ACCESS_FINE_LOCATION was not granted."
            exception = e
        } catch (e: UnsupportedConfigurationException) {
            message = "This device does not support GeospatialMode.ENABLED."
            exception = e
        } catch (e: SecurityException) {
            message = "Camera failure or the internet permission has not been granted."
            exception = e
        }
        if (message != null) {
            session = null
            messageSnackbarHelper.showError(this, message)
            Log.e(TAG, "Exception configuring and resuming the session", exception)
            return
        }
    }

    private val lastLocation: Unit
        private get() {
            try {
                fusedLocationClient!!
                    .getLastLocation()
                    .addOnSuccessListener { location ->
                        var latitude = 0.0
                        var longitude = 0.0
                        if (location != null) {
                            latitude = location.latitude
                            longitude = location.longitude
                        } else {
                            Log.e(TAG, "Error location is null")
                        }
                        checkVpsAvailability(latitude, longitude)
                    }
            } catch (e: SecurityException) {
                Log.e(TAG, "No location permissions granted by User!")
            }
        }

    private fun checkVpsAvailability(latitude: Double, longitude: Double) {
        val availabilityFuture = checkVpsAvailabilityFuture(latitude, longitude)
        Futures.addCallback(
            availabilityFuture,
            object : FutureCallback<VpsAvailability> {
                override fun onSuccess(result: VpsAvailability) {
                    if (result != VpsAvailability.AVAILABLE) {
                        showVpsNotAvailabilityNoticeDialog()
                    }
                }

                override fun onFailure(t: Throwable) {
                    Log.e(TAG, "Error checking VPS availability", t)
                }
            },
            mainExecutor
        )
    }

    private fun showVpsNotAvailabilityNoticeDialog() {
        val dialog: DialogFragment = VpsAvailabilityNoticeDialogFragment.createDialog()
        dialog.show(supportFragmentManager, VpsAvailabilityNoticeDialogFragment::class.java.name)
    }

    public override fun onPause() {
        super.onPause()
        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper!!.onPause()
            surfaceView!!.onPause()
            session!!.pause()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            // Use toast instead of snackbar here since the activity will exit.
            Toast.makeText(
                this,
                "Camera permission is needed to run this application",
                Toast.LENGTH_LONG
            )
                .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
        // Check if this result pertains to the location permission.
        if (LocationPermissionHelper.hasFineLocationPermissionsResponseInResult(permissions)
            && !LocationPermissionHelper.hasFineLocationPermission(this)
        ) {
            // Use toast instead of snackbar here since the activity will exit.
            Toast.makeText(
                this,
                "Precise location permission is needed to run this application",
                Toast.LENGTH_LONG
            )
                .show()
            if (!LocationPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                LocationPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }

    override fun onSurfaceCreated(render: SampleRender) {
        // Prepare the rendering objects. This involves reading shaders and 3D model files, so may throw
        // an IOException.
        try {
            planeRenderer = PlaneRenderer(render)
            backgroundRenderer = BackgroundRenderer(render)
            virtualSceneFramebuffer = Framebuffer(render,  /*width=*/1,  /*height=*/1)

            // Virtual object to render (ARCore geospatial)
            val virtualObjectTexture = Texture.createFromAsset(
                render,
                "models/spatial_marker_baked.png",
                Texture.WrapMode.CLAMP_TO_EDGE,
                Texture.ColorFormat.SRGB
            )
            virtualObjectMesh = Mesh.createFromAsset(render, "models/geospatial_marker.obj")
            geospatialAnchorVirtualObjectShader = Shader.createFromAssets(
                render,
                "shaders/ar_unlit_object.vert",
                "shaders/ar_unlit_object.frag",  /*defines=*/
                null
            )
                .setTexture("u_Texture", virtualObjectTexture)

            // Virtual object to render (Terrain anchor marker)
            val terrainAnchorVirtualObjectTexture = Texture.createFromAsset(
                render,
                "models/spatial_marker_yellow.png",
                Texture.WrapMode.CLAMP_TO_EDGE,
                Texture.ColorFormat.SRGB
            )
            terrainAnchorVirtualObjectShader = Shader.createFromAssets(
                render,
                "shaders/ar_unlit_object.vert",
                "shaders/ar_unlit_object.frag",  /*defines=*/
                null
            )
                .setTexture("u_Texture", terrainAnchorVirtualObjectTexture)
            backgroundRenderer!!.setUseDepthVisualization(render, false)
            backgroundRenderer!!.setUseOcclusion(render, false)

            // Point cloud
            pointCloudShader = Shader.createFromAssets(
                render, "shaders/point_cloud.vert", "shaders/point_cloud.frag",  /*defines=*/null
            )
                .setVec4(
                    "u_Color", floatArrayOf(31.0f / 255.0f, 188.0f / 255.0f, 210.0f / 255.0f, 1.0f)
                )
                .setFloat("u_PointSize", 5.0f)
            // four entries per vertex: X, Y, Z, confidence
            pointCloudVertexBuffer =
                VertexBuffer(render,  /*numberOfEntriesPerVertex=*/4,  /*entries=*/null)
            val pointCloudVertexBuffers = arrayOf(
                pointCloudVertexBuffer!!
            )
            pointCloudMesh = Mesh(
                render, Mesh.PrimitiveMode.POINTS,  /*indexBuffer=*/null, pointCloudVertexBuffers
            )
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read a required asset file", e)
            messageSnackbarHelper.showError(this, "Failed to read a required asset file: $e")
        }
    }

    override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
        displayRotationHelper!!.onSurfaceChanged(width, height)
        virtualSceneFramebuffer!!.resize(width, height)
    }

    override fun onDrawFrame(render: SampleRender) {
        if (session == null) {
            return
        }

        // Texture names should only be set once on a GL thread unless they change. This is done during
        // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
        // initialized during the execution of onSurfaceCreated.
        if (!hasSetTextureNames) {
            session!!.setCameraTextureNames(intArrayOf(backgroundRenderer!!.cameraColorTexture.textureId))
            hasSetTextureNames = true
        }

        // -- Update per-frame state

        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper!!.updateSessionIfNeeded(session)

        // Obtain the current frame from ARSession. When the configuration is set to
        // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
        // camera framerate.
        val frame: Frame
        frame = try {
            session!!.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available during onDrawFrame", e)
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.")
            return
        }
        val camera = frame.camera

        // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
        // used to draw the background camera image.
        backgroundRenderer!!.updateDisplayGeometry(frame)

        // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
        trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)
        val earth = session!!.earth
        earth?.let { updateGeospatialState(it) }

        // Show a message based on whether tracking has failed, if planes are detected, and if the user
        // has placed any objects.
        var message: String? = null
        when (state) {
            State.UNINITIALIZED -> {}
            State.UNSUPPORTED -> message = resources.getString(R.string.status_unsupported)
            State.PRETRACKING -> message = resources.getString(R.string.status_pretracking)
            State.EARTH_STATE_ERROR -> message =
                resources.getString(R.string.status_earth_state_error)
            State.LOCALIZING -> message = resources.getString(R.string.status_localize_hint)
            State.LOCALIZING_FAILED -> message =
                resources.getString(R.string.status_localize_timeout)
            State.LOCALIZED -> if (pendingTerrainAnchors.size > 0) {
                // If there is a terrain anchor, show terrain anchor state.
                for ((anchor, listener) in pendingTerrainAnchors) {
                    if (anchor.terrainAnchorState != TerrainAnchorState.TASK_IN_PROGRESS) {
                        listener.onTaskComplete(anchor)
                        pendingTerrainAnchors.remove(anchor)
                    }
                    if (deadlineForMessageMillis > 0
                        && SystemClock.uptimeMillis() > deadlineForMessageMillis
                    ) {
                        listener.onDeadlineExceeded()
                        deadlineForMessageMillis = 0
                        pendingTerrainAnchors.remove(anchor)
                    }
                }
            } else if (lastStatusText == resources.getString(R.string.status_localize_hint)) {
                message = resources.getString(R.string.status_localize_complete)
            }
        }
        if (message != null && lastStatusText !== message) {
            lastStatusText = message
            runOnUiThread {}
        }

        // Handle user input.
        handleTap(frame, camera.trackingState)

        // -- Draw background
        if (frame.timestamp != 0L) {
            // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
            // drawing possible leftover data from previous sessions if the texture is reused.
            backgroundRenderer!!.drawBackground(render)
        }

        // If not tracking, don't draw 3D objects.
        // if (camera.getTrackingState() != TrackingState.TRACKING || state != State.LOCALIZED) {
        //  return;
        // }

        // -- Draw virtual objects

        // Get projection matrix.
        camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

        // Get camera matrix and draw.
        camera.getViewMatrix(viewMatrix, 0)
        frame.acquirePointCloud().use { pointCloud ->
            if (pointCloud.timestamp > lastPointCloudTimestamp) {
                pointCloudVertexBuffer!!.set(pointCloud.points)
                lastPointCloudTimestamp = pointCloud.timestamp
            }
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            pointCloudShader!!.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)
            render.draw(pointCloudMesh, pointCloudShader)
        }

        // Visualize planes.
        planeRenderer!!.drawPlanes(
            render,
            session!!.getAllTrackables(Plane::class.java),
            camera.displayOrientedPose,
            projectionMatrix
        )

        // Visualize anchors created by touch.
        render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
        for (anchor in anchors) {
            // Get the current pose of an Anchor in world space. The Anchor pose is updated
            // during calls to session.update() as ARCore refines its estimate of the world.

            // Only render resolved Terrain anchors and Geospatial anchors.
            if (anchor.terrainAnchorState != TerrainAnchorState.SUCCESS
                && anchor.terrainAnchorState != TerrainAnchorState.NONE
            ) {
                continue
            }
            anchor.pose.toMatrix(modelMatrix, 0)

            // Rotate the virtual object 180 degrees around the Y axis to make the object face the GL
            // camera -Z axis, since camera Z axis faces toward users.
            val rotationMatrix = FloatArray(16)
            Matrix.setRotateM(rotationMatrix, 0, 180f, 0.0f, 1.0f, 0.0f)
            val rotationModelMatrix = FloatArray(16)
            Matrix.multiplyMM(rotationModelMatrix, 0, modelMatrix, 0, rotationMatrix, 0)
            // Calculate model/view/projection matrices
            Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, rotationModelMatrix, 0)
            Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
            // Update shader properties and draw
            if (anchor.terrainAnchorState == TerrainAnchorState.SUCCESS) {
                terrainAnchorVirtualObjectShader!!.setMat4(
                    "u_ModelViewProjection", modelViewProjectionMatrix
                )
                render.draw(
                    virtualObjectMesh,
                    terrainAnchorVirtualObjectShader,
                    virtualSceneFramebuffer
                )
            } else {
                geospatialAnchorVirtualObjectShader!!.setMat4(
                    "u_ModelViewProjection", modelViewProjectionMatrix
                )
                render.draw(
                    virtualObjectMesh, geospatialAnchorVirtualObjectShader, virtualSceneFramebuffer
                )
            }
        }

        // Compose the virtual scene with the background.
        backgroundRenderer!!.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
    }

    /** Configures the session with feature settings.  */
    private fun configureSession() {
        // Earth mode may not be supported on this device due to insufficient sensor quality.
        if (!session!!.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
            state = State.UNSUPPORTED
            return
        }
        val config = session!!.config
        config.geospatialMode = Config.GeospatialMode.ENABLED
        session!!.configure(config)
        state = State.PRETRACKING
        localizingStartTimestamp = System.currentTimeMillis()
    }

    /** Change behavior depending on the current [State] of the application.  */
    private fun updateGeospatialState(earth: Earth) {
        if (earth.earthState != Earth.EarthState.ENABLED) {
            state = State.EARTH_STATE_ERROR
            return
        }
        if (earth.trackingState != TrackingState.TRACKING) {
            state = State.PRETRACKING
            return
        }
        if (state == State.PRETRACKING) {
            updatePretrackingState(earth)
        } else if (state == State.LOCALIZING) {
            updateLocalizingState(earth)
        } else if (state == State.LOCALIZED) {
            updateLocalizedState(earth)
        }
    }

    /**
     * Handles the updating for [State.PRETRACKING]. In this state, wait for [Earth] to
     * have [TrackingState.TRACKING]. If it hasn't been enabled by now, then we've encountered
     * an unrecoverable [State.EARTH_STATE_ERROR].
     */
    private fun updatePretrackingState(earth: Earth) {
        if (earth.trackingState == TrackingState.TRACKING) {
            state = State.LOCALIZING
            return
        }
    }

    /**
     * Handles the updating for [State.LOCALIZING]. In this state, wait for the horizontal and
     * orientation threshold to improve until it reaches your threshold.
     *
     *
     * If it takes too long for the threshold to be reached, this could mean that GPS data isn't
     * accurate enough, or that the user is in an area that can't be localized with StreetView.
     */
    private fun updateLocalizingState(earth: Earth) {
        val geospatialPose = earth.cameraGeospatialPose
        if (geospatialPose.horizontalAccuracy <= LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS
            && geospatialPose.orientationYawAccuracy
            <= LOCALIZING_ORIENTATION_YAW_ACCURACY_THRESHOLD_DEGREES
        ) {
            state = State.LOCALIZED
            if (anchors.isEmpty()) {
                createAnchorFromSharedPreferences(earth)
            }
            if (anchors.size < MAXIMUM_ANCHORS) {
                runOnUiThread {
                    if (anchors.size > 0) {
                    }
                }
            }
            return
        }
        if (TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - localizingStartTimestamp)
            > LOCALIZING_TIMEOUT_SECONDS
        ) {
            state = State.LOCALIZING_FAILED
            return
        }
        updateGeospatialPoseText(geospatialPose)
    }

    /**
     * Handles the updating for [State.LOCALIZED]. In this state, check the accuracy for
     * degradation and return to [State.LOCALIZING] if the position accuracies have dropped too
     * low.
     */
    private fun updateLocalizedState(earth: Earth) {
        val geospatialPose = earth.cameraGeospatialPose
        // Check if either accuracy has degraded to the point we should enter back into the LOCALIZING
        // state.
        if (geospatialPose.horizontalAccuracy
            > LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS
            + LOCALIZED_HORIZONTAL_ACCURACY_HYSTERESIS_METERS
            || geospatialPose.orientationYawAccuracy
            > LOCALIZING_ORIENTATION_YAW_ACCURACY_THRESHOLD_DEGREES
            + LOCALIZED_ORIENTATION_YAW_ACCURACY_HYSTERESIS_DEGREES
        ) {
            // Accuracies have degenerated, return to the localizing state.
            state = State.LOCALIZING
            localizingStartTimestamp = System.currentTimeMillis()
            runOnUiThread {}
            return
        }
        updateGeospatialPoseText(geospatialPose)
    }

    private fun updateGeospatialPoseText(geospatialPose: GeospatialPose) {
        val quaternion = geospatialPose.eastUpSouthQuaternion
        val poseText = resources
            .getString(
                R.string.geospatial_pose,
                geospatialPose.latitude,
                geospatialPose.longitude,
                geospatialPose.horizontalAccuracy,
                geospatialPose.altitude,
                geospatialPose.verticalAccuracy,
                quaternion[0],
                quaternion[1],
                quaternion[2],
                quaternion[3],
                geospatialPose.orientationYawAccuracy
            )
    }

    /**
     * Handles the button that creates an anchor.
     *
     *
     * Ensure Earth is in the proper state, then create the anchor. Persist the parameters used to
     * create the anchors so that the anchors will be loaded next time the app is launched.
     */
    private fun handleSetAnchorButton() {
        val earth = session!!.earth
        if (earth == null || earth.trackingState != TrackingState.TRACKING) {
            return
        }
        val geospatialPose = earth.cameraGeospatialPose
        createAnchorWithGeospatialPose(earth, geospatialPose)
    }

    /** Creates anchor with the provided GeospatialPose, either from camera or HitResult.  */
    private fun createAnchorWithGeospatialPose(earth: Earth, geospatialPose: GeospatialPose) {
        val latitude = geospatialPose.latitude
        val longitude = geospatialPose.longitude
        val altitude = geospatialPose.altitude
        val quaternion = geospatialPose.eastUpSouthQuaternion
        if (isTerrainAnchorMode) {
            createTerrainAnchor(earth, latitude, longitude, quaternion)
            storeAnchorParameters(latitude, longitude, 0.0, quaternion)
        } else {
            createAnchor(earth, latitude, longitude, altitude, quaternion)
            //TODO : place below fct in saveWalkable and saveDestination
            saveNodeparams(
                0,
                latitude.toFloat(),
                longitude.toFloat(),
                altitude.toFloat(),
                "name",
                null
            )
            storeAnchorParameters(latitude, longitude, altitude, quaternion)
            val message = resources
                .getQuantityString(R.plurals.status_anchors_set, anchors.size, anchors.size)
            runOnUiThread {}
        }
        runOnUiThread {
            textView!!.visibility = View.INVISIBLE
            view!!.visibility = View.INVISIBLE
            if (navHostView != null) {
                navHostView!!.visibility = View.VISIBLE
            } else {
                Log.e("NavHostFragment", "getView returned null")
            }
        }
        if (clearedAnchorsAmount != null) {
            clearedAnchorsAmount = null
        }
    }

    private fun handleClearAnchorsButton() {
        clearedAnchorsAmount = anchors.size
        val message = resources
            .getQuantityString(
                R.plurals.status_anchors_cleared, clearedAnchorsAmount!!, clearedAnchorsAmount
            )
        for (anchor in anchors) {
            anchor.detach()
        }
        anchors.clear()
        clearAnchorsFromSharedPreferences()
        // clearAnchorsButton.setVisibility(View.INVISIBLE);
        runOnUiThread {}
    }

    /** Create an anchor at a specific geodetic location using a EUS quaternion.  */
    private fun createAnchor(
        earth: Earth, latitude: Double, longitude: Double, altitude: Double, quaternion: FloatArray
    ) {
        val anchor = earth.createAnchor(
            latitude,
            longitude,
            altitude,
            quaternion[0],
            quaternion[1],
            quaternion[2],
            quaternion[3]
        )
        anchors.add(anchor)
        if (anchors.size >= MAXIMUM_ANCHORS) {
            runOnUiThread {}
        }
    }

    /** Create a terrain anchor at a specific geodetic location using a EUS quaternion.  */
    private fun createTerrainAnchor(
        earth: Earth, latitude: Double, longitude: Double, quaternion: FloatArray
    ) {
        var anchor: Anchor? = null
        try {
            anchor = earth.resolveAnchorOnTerrain(
                latitude,
                longitude,  /* altitudeAboveTerrain= */
                0.0,
                quaternion[0],
                quaternion[1],
                quaternion[2],
                quaternion[3]
            )
            anchors.add(anchor)
            if (anchors.size >= MAXIMUM_ANCHORS) {
                runOnUiThread {}
            }
        } catch (e: ResourceExhaustedException) {
            messageSnackbarHelper.showMessageWithDismiss(
                this, resources.getString(R.string.terrain_anchor_resource_exhausted)
            )
            Log.d(TAG, "Exception creating terrain anchor", e)
        }
        deadlineForMessageMillis =
            SystemClock.uptimeMillis() + DURATION_FOR_NO_TERRAIN_ANCHOR_RESULT_MS
        if (anchor != null) {
            pendingTerrainAnchors[anchor] = TerrainAnchorResolveListener()
        }
    }

    //////////////////// saving node params in the database
    private fun saveNodeparams(
        id: Int,
        latitude: Float,
        longitude: Float,
        altitude: Float,
        name: String,
        adjs: MutableSet<Node>?
    ) {

        // create a new Node object with the anchor parameters
        val node = Node(id, latitude, longitude, altitude, name, adjs)

        // instantiate the database and insert the new Node object into the database
        val nodeDao =
            Room.databaseBuilder(applicationContext, NodeDatabase::class.java, "node-database")
                .build().nodeDao()
        nodeDao.insert(node)
    }
    /////////////////////////
    /**
     * Helper function to store the parameters used in anchor creation in [SharedPreferences].
     */
    private fun storeAnchorParameters(
        latitude: Double, longitude: Double, altitude: Double, quaternion: FloatArray
    ) {
        val anchorParameterSet = sharedPreferences!!.getStringSet(
            SHARED_PREFERENCES_SAVED_ANCHORS, HashSet()
        )
        val newAnchorParameterSet = HashSet(anchorParameterSet)
        val editor = sharedPreferences!!.edit()
        val terrain = if (isTerrainAnchorMode) "Terrain" else ""
        newAnchorParameterSet.add(
            String.format(
                "$terrain%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f",
                latitude,
                longitude,
                altitude,
                quaternion[0],
                quaternion[1],
                quaternion[2],
                quaternion[3]
            )
        )
        println(newAnchorParameterSet.toString())
        editor.putStringSet(SHARED_PREFERENCES_SAVED_ANCHORS, newAnchorParameterSet)
        editor.commit()
    }

    private fun clearAnchorsFromSharedPreferences() {
        val editor = sharedPreferences!!.edit()
        editor.putStringSet(SHARED_PREFERENCES_SAVED_ANCHORS, null)
        editor.commit()
    }

    /** Creates all anchors that were stored in the [SharedPreferences].  */
    private fun createAnchorFromSharedPreferences(earth: Earth) {
        val anchorParameterSet = sharedPreferences!!.getStringSet(
            SHARED_PREFERENCES_SAVED_ANCHORS, null
        )
            ?: return
        for (anchorParameters in anchorParameterSet) {
            val isTerrain = anchorParameters.contains("Terrain")
            var modifiedParameters = anchorParameters // declare a new variable

            if (isTerrain) {

                // anchorParameters = anchorParameters.replace("Terrain", "")
                modifiedParameters = anchorParameters.replace("Terrain", "")

            }
            val parameters =
                modifiedParameters.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (parameters.size != 7) {
                Log.d(
                    TAG,
                    "Invalid number of anchor parameters. Expected four, found " + parameters.size
                )
                continue
            }
            val latitude = parameters[0].toDouble()
            val longitude = parameters[1].toDouble()
            val altitude = parameters[2].toDouble()
            val quaternion = floatArrayOf(
                parameters[3].toFloat(),
                parameters[4].toFloat(),
                parameters[5].toFloat(),
                parameters[6].toFloat()
            )
            if (isTerrain) {
                createTerrainAnchor(earth, latitude, longitude, quaternion)
            } else {
                createAnchor(earth, latitude, longitude, altitude, quaternion)
            }
        }

        // runOnUiThread(() -> clearAnchorsButton.setVisibility(View.VISIBLE));
    }

    override fun onDialogPositiveClick(dialog: DialogFragment?) {
        if (!sharedPreferences!!.edit().putBoolean(ALLOW_GEOSPATIAL_ACCESS_KEY, true).commit()) {
            throw AssertionError("Could not save the user preference to SharedPreferences!")
        }
        createSession()
    }

    override fun onDialogContinueClick(dialog: DialogFragment?) {
        dialog!!.dismiss()
    }

    private fun onTerrainAnchorModeChanged(button: CompoundButton, isChecked: Boolean) {
        if (session == null) {
            return
        }
        isTerrainAnchorMode = isChecked
    }

    /**
     * Handles the most recent user tap.
     *
     *
     * We only ever handle one tap at a time, since this app only allows for a single anchor.
     *
     * @param frame the current AR frame
     * @param cameraTrackingState the current camera tracking state
     */
    private fun handleTap(frame: Frame, cameraTrackingState: TrackingState) {
        // Handle taps. Handling only one tap per frame, as taps are usually low frequency
        // compared to frame rate.
        synchronized(singleTapLock) {
            if (queuedSingleTap == null || anchors.size >= MAXIMUM_ANCHORS || cameraTrackingState != TrackingState.TRACKING) {
                queuedSingleTap = null
                return
            }
            val earth = session!!.earth
            if (earth == null || earth.trackingState != TrackingState.TRACKING) {
                queuedSingleTap = null
                return
            }
            for (hit in frame.hitTest(queuedSingleTap)) {
                if (shouldCreateAnchorWithHit(hit)) {
                    val hitPose = hit.hitPose
                    val geospatialPose = earth.getGeospatialPose(hitPose)
                    createAnchorWithGeospatialPose(earth, geospatialPose)
                    break // Only handle the first valid hit.
                }
            }
            queuedSingleTap = null
        }
    }

    /** Listener for the results of a resolving terrain anchor operation.  */
    private inner class TerrainAnchorResolveListener {
        fun onTaskComplete(anchor: Anchor) {
            val state = anchor.terrainAnchorState
            runOnUiThread {}
        }

        fun onDeadlineExceeded() {
            runOnUiThread {}
            deadlineForMessageMillis = 0
        }
    }

    // Wrapper for checkVpsAvailability. Do not block on this future on the Main thread; deadlock will
    // result.
    private fun checkVpsAvailabilityFuture(
        latitude: Double, longitude: Double
    ): ListenableFuture<VpsAvailability> {
        return CallbackToFutureAdapter.getFuture { completer: CallbackToFutureAdapter.Completer<VpsAvailability> ->
            val future = session!!.checkVpsAvailabilityAsync(
                latitude, longitude
            ) { availability: VpsAvailability -> completer.set(availability) }
            completer.addCancellationListener(
                { val cancel = future.cancel() }) { obj: Runnable -> obj.run() }
            "checkVpsAvailabilityFuture"
        }
    }

    companion object {
        private val TAG = GeospatialActivity::class.java.simpleName
        private const val SHARED_PREFERENCES_SAVED_ANCHORS = "SHARED_PREFERENCES_SAVED_ANCHORS"
        private const val ALLOW_GEOSPATIAL_ACCESS_KEY = "ALLOW_GEOSPATIAL_ACCESS"
        private const val Z_NEAR = 0.1f
        private const val Z_FAR = 1000f

        // The thresholds that are required for horizontal and orientation accuracies before entering into
        // the LOCALIZED state. Once the accuracies are equal or less than these values, the app will
        // allow the user to place anchors.
        private const val LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS = 3.0
        private const val LOCALIZING_ORIENTATION_YAW_ACCURACY_THRESHOLD_DEGREES = 5.0

        // Once in the LOCALIZED state, if either accuracies degrade beyond these amounts, the app will
        // revert back to the LOCALIZING state.
        private const val LOCALIZED_HORIZONTAL_ACCURACY_HYSTERESIS_METERS = 10.0
        private const val LOCALIZED_ORIENTATION_YAW_ACCURACY_HYSTERESIS_DEGREES = 10.0
        private const val LOCALIZING_TIMEOUT_SECONDS = 180
        private const val MAXIMUM_ANCHORS = 1
        private const val DURATION_FOR_NO_TERRAIN_ANCHOR_RESULT_MS: Long = 10000

        /** Returns `true` if and only if the hit can be used to create an Anchor reliably.  */
        private fun shouldCreateAnchorWithHit(hit: HitResult): Boolean {
            val trackable = hit.trackable
            if (trackable is Plane) {
                // Check if the hit was within the plane's polygon.
                return trackable.isPoseInPolygon(hit.hitPose)
            } else if (trackable is Point) {
                // Check if the hit was against an oriented point.
                return trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
            }
            return false
        }
    }
}