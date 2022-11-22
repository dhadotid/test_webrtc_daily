package com.example.testdaily

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Space
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import co.daily.CallClient
import co.daily.CallClientListener
import co.daily.exception.UnknownCallClientError
import co.daily.model.AvailableDevices
import co.daily.model.CallState
import co.daily.model.Participant
import co.daily.model.ParticipantId
import co.daily.settings.*
import co.daily.settings.subscription.*
import co.daily.view.VideoView
import com.example.testdaily.Helper.isMediaAvailable
import kotlinx.coroutines.launch

class WebRTCActivity: AppCompatActivity() {

    companion object {
        fun launch(
            context: Context
        ) = Intent(context, WebRTCActivity::class.java)
    }

    private lateinit var localVideoView: VideoView
    private lateinit var remoteVideoView: VideoView
    private lateinit var space: Space
    private lateinit var callClient: CallClient

    private val profileActiveCamera = SubscriptionProfile("activeCamera")
    private val profileActiveScreenShare = SubscriptionProfile("activeScreenShare")
    private val roomUrl = "https://testingdailygenflix.daily.co/testing"

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { !it }) {
                checkPermissions()
            } else {
                // permission is granted, we can initialize
                initCallClient()
            }
        }

    private val callClientListener = object : CallClientListener {
        override fun onError(message: String) {
        }

        override fun onCallStateUpdated(
            state: CallState
        ) {
            when (state) {
                CallState.joining -> {}
                CallState.joined -> {
                    choosePreferredRemoteParticipant()
                }
                CallState.leaving -> {}
                CallState.left -> {
//                    resetAppState()
                }
                CallState.new -> {}
            }
        }

        override fun onInputsUpdated(inputSettings: InputSettings) {
//            updateLocalVideoState()
        }

        override fun onPublishingUpdated(publishingSettings: PublishingSettings) {
        }

        override fun onParticipantJoined(participant: Participant) {
            updateParticipantVideoView(participant)
        }

        override fun onSubscriptionsUpdated(subscriptions: Map<ParticipantId, SubscriptionSettings>) {
        }

        override fun onSubscriptionProfilesUpdated(subscriptionProfiles: Map<SubscriptionProfile, SubscriptionProfileSettings>) {
        }

        override fun onParticipantUpdated(participant: Participant) {
            updateParticipantVideoView(participant)
        }

        override fun onActiveSpeakerChanged(activeSpeaker: Participant?) {
            choosePreferredRemoteParticipant()
        }

        override fun onParticipantLeft(participant: Participant) {
            choosePreferredRemoteParticipant()
        }

        override fun onAvailableDevicesUpdated(availableDevices: AvailableDevices) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webrtc)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        localVideoView = findViewById(R.id.local_video_view)
        remoteVideoView = findViewById(R.id.remote_video_view)
        space = findViewById(R.id.space)

//        initCallClient()

        checkPermissions()
    }

    private fun updateParticipantVideoView(participant: Participant) {
        if (participant.info.isLocal) {
            updateLocalVideoState(participant)
        } else {
            choosePreferredRemoteParticipant()
        }
    }

    private fun setupParticipantSubscriptionProfiles() {
        lifecycleScope.launch {
            val subscriptionProfilesResult = callClient.updateSubscriptionProfiles(
                mapOf(
                    profileActiveCamera to
                            SubscriptionProfileSettingsUpdate(
                                camera = VideoSubscriptionSettingsUpdate(
                                    subscriptionState = Subscribed(),
                                    receiveSettings = VideoReceiveSettingsUpdate(
                                        maxQuality = VideoMaxQualityUpdate.high
                                    )
                                ),
                                screenVideo = VideoSubscriptionSettingsUpdate(
                                    subscriptionState = Unsubscribed()
                                )
                            ),
                    profileActiveScreenShare to
                            SubscriptionProfileSettingsUpdate(
                                camera = VideoSubscriptionSettingsUpdate(
                                    subscriptionState = Unsubscribed()
                                ),
                                screenVideo = VideoSubscriptionSettingsUpdate(
                                    subscriptionState = Subscribed(),
                                    receiveSettings = VideoReceiveSettingsUpdate(
                                        maxQuality = VideoMaxQualityUpdate.high
                                    )
                                )
                            ),
                    SubscriptionProfile.base to
                            SubscriptionProfileSettingsUpdate(
                                camera = VideoSubscriptionSettingsUpdate(
                                    subscriptionState = Unsubscribed()
                                ),
                                screenVideo = VideoSubscriptionSettingsUpdate(
                                    subscriptionState = Unsubscribed()
                                )
                            )
                )
            )
        }
    }

    private fun initCallClient() {
        callClient = CallClient(appContext = applicationContext).apply {
            addListener(callClientListener)
        }

        lifecycleScope.launch {
            callClient.updateInputs(
                inputSettings = InputSettingsUpdate(
                    // TODO:: Enable this microphone
                    microphone = Disable(),
                    camera = Enable()
                )
            )
        }

        setupParticipantSubscriptionProfiles()

        lifecycleScope.launch {
            try {
                callClient.join(
                    url = roomUrl,
                    clientSettings = createClientSettingsIntent()
                )
                callClient.setUserName("Test")
            } catch (ex: UnknownCallClientError) {
                Log.e("WebRTCActivity", "Failed to join call $ex")
                callClient.leave()
                finish()
            }
        }
    }

    private fun createClientSettingsIntent(): ClientSettingsUpdate {
        val publishingSettingsIntent = PublishingSettingsUpdate(
            camera = CameraPublishingSettingsUpdate(
                sendSettings = VideoSendSettingsUpdate(
                    encodings = VideoEncodingsSettingsUpdate(
                        settings = mapOf(
                            VideoMaxQualityUpdate.low to
                                    VideoEncodingSettingsUpdate(
                                        maxBitrate = BitRate(80000),
                                        maxFramerate = FrameRate(10),
                                        scaleResolutionDownBy = Scale(4F)
                                    ),
                            VideoMaxQualityUpdate.medium to
                                    VideoEncodingSettingsUpdate(
                                        maxBitrate = BitRate(680000),
                                        maxFramerate = FrameRate(30),
                                        scaleResolutionDownBy = Scale(1F)
                                    )
                        )
                    )
                )
            )
        )
        return ClientSettingsUpdate(
            publishingSettings = publishingSettingsIntent
        )
    }

    private fun choosePreferredRemoteParticipant() {
        val allParticipants = callClient.participants().all

        val participantWhoIsSharingScreen =
            allParticipants.values.firstOrNull { isMediaAvailable(it.media?.screenVideo) }?.id

        val activeSpeaker = callClient.activeSpeaker()?.takeUnless { it.info.isLocal }?.id

        /*
            The preference is:
                - The participant who is sharing their screen
                - The active speaker
                - The last displayed remote participant
                - Any remote participant who has their video opened
        */
        val participantId = participantWhoIsSharingScreen
            ?: activeSpeaker
            ?: callClient.participants().all.values.firstOrNull {
                !it.info.isLocal && isMediaAvailable(it.media?.camera)
            }?.id

        // Get the latest information about the participant
        val activeParticipant = allParticipants[participantId]
        remoteVideoView.visibility = if (activeParticipant != null)
            View.VISIBLE
        else
            View.GONE
        space.visibility = if (activeParticipant != null)
            View.VISIBLE
        else
            View.GONE

        remoteVideoView.apply {
            track = if (isMediaAvailable(activeParticipant?.media?.screenVideo)) {
                videoScaleMode = VideoView.VideoScaleMode.FIT
                activeParticipant?.media?.screenVideo?.track
            } else if (isMediaAvailable(activeParticipant?.media?.camera)) {
                videoScaleMode = VideoView.VideoScaleMode.FILL
                activeParticipant?.media?.camera?.track
            } else null
        }
        changePreferredRemoteParticipantSubscription(activeParticipant)
    }

    private fun changePreferredRemoteParticipantSubscription(activeParticipant: Participant?) {
        lifecycleScope.launch {
            val subscriptionsResult = callClient.updateSubscriptions(
                // Improve the video quality of the remote participant that is currently displayed
                forParticipants = activeParticipant?.run {
                    mapOf(
                        id to SubscriptionSettingsUpdate(
                            profile = if (isMediaAvailable(activeParticipant.media?.screenVideo)) {
                                profileActiveScreenShare
                            } else if (isMediaAvailable(activeParticipant.media?.camera)) {
                                profileActiveCamera
                            } else SubscriptionProfile.base
                        )
                    )
                } ?: mapOf(),
                // Unsubscribe from remote participants not currently displayed
                forParticipantsWithProfiles = mapOf(
                    profileActiveCamera to SubscriptionSettingsUpdate(
                        profile = SubscriptionProfile.base
                    ),
                    profileActiveScreenShare to SubscriptionSettingsUpdate(
                        profile = SubscriptionProfile.base
                    )
                )
            )
        }
    }

    private fun updateLocalVideoState(participant: Participant) {
        participant.media?.camera?.track?.let { track ->
            localVideoView.track = track
        }
    }

    override fun onBackPressed() {
        callClient.leave()
        super.onBackPressed()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        callClient.release()
    }

    private fun checkPermissions() {
        val permissionList = applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions

        val notGrantedPermissions = permissionList.map {
            Pair(it, ContextCompat.checkSelfPermission(applicationContext, it))
        }.filter {
            it.second != PackageManager.PERMISSION_GRANTED
        }.map {
            it.first
        }.toTypedArray()

        if (notGrantedPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(notGrantedPermissions)
        } else {
            // permission is granted, we can initialize
            initCallClient()
        }
    }
}