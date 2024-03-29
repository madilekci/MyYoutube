package io.awesome.gagtube.player;

import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.text.CaptionStyleCompat;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.nostra13.universalimageloader.core.assist.FailReason;

import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;
import io.awesome.gagtube.BuildConfig;
import io.awesome.gagtube.R;
import io.awesome.gagtube.player.event.PlayerEventListener;
import io.awesome.gagtube.player.helper.PlayerHelper;
import io.awesome.gagtube.player.resolver.MediaSourceTag;
import io.awesome.gagtube.player.resolver.VideoPlaybackResolver;
import io.awesome.gagtube.util.AnimationUtils;
import io.awesome.gagtube.util.ListHelper;
import io.awesome.gagtube.util.NavigationHelper;
import io.awesome.gagtube.util.ThemeHelper;

public final class PopupVideoPlayer extends Service {
	
	private static final int NOTIFICATION_ID = 40028922;
	public static final String ACTION_CLOSE = "io.awesome.gagtube.player.PopupVideoPlayer.CLOSE";
	public static final String ACTION_PLAY_PAUSE = "io.awesome.gagtube.player.PopupVideoPlayer.PLAY_PAUSE";
	public static final String ACTION_REPEAT = "io.awesome.gagtube.player.PopupVideoPlayer.REPEAT";
	
	private static final String POPUP_SAVED_WIDTH = "popup_saved_width";
	private static final String POPUP_SAVED_X = "popup_saved_x";
	private static final String POPUP_SAVED_Y = "popup_saved_y";
	
	private static final int MINIMUM_SHOW_EXTRA_WIDTH_DP = 200;
	
	private static final int IDLE_WINDOW_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
	private static final int ONGOING_PLAYBACK_WINDOW_FLAGS = IDLE_WINDOW_FLAGS | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
	
	private WindowManager windowManager;
	private WindowManager.LayoutParams windowLayoutParams;
	private GestureDetector gestureDetector;
	
	private float screenWidth, screenHeight;
	private float popupWidth, popupHeight;
	
	private float minimumWidth, minimumHeight;
	private float maximumWidth, maximumHeight;
	
	private NotificationManager notificationManager;
	private NotificationCompat.Builder notBuilder;
	private RemoteViews notRemoteView;
	
	private VideoPlayerImpl playerImpl;
	
	private PlayerEventListener activityListener;
	private IBinder mBinder;
	private boolean shouldUpdateOnProgress;
	
	@Override
	protected void attachBaseContext(Context base) {
		
		super.attachBaseContext(AudioServiceLeak.preventLeakOf(base));
	}
	
	// Service LifeCycle
	@Override
	public void onCreate() {
		
		windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
		notificationManager = ((NotificationManager) getSystemService(NOTIFICATION_SERVICE));
		
		playerImpl = new VideoPlayerImpl(this);
		ThemeHelper.setTheme(this);
		
		mBinder = new PlayerServiceBinder(playerImpl);
		shouldUpdateOnProgress = true;
	}
	
	@Override
	public int onStartCommand(final Intent intent, int flags, int startId) {
		
		if (playerImpl.getPlayer() == null) initPopup();
		if (!playerImpl.isPlaying()) playerImpl.getPlayer().setPlayWhenReady(true);
		
		playerImpl.handleIntent(intent);
		
		return START_NOT_STICKY;
	}
	
	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		
		updateScreenSize();
		updatePopupSize(windowLayoutParams.width, -1);
		checkPositionBounds();
	}
	
	@Override
	public void onDestroy() {
		onClosePopup();
	}
	
	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}
	
	// Init
	@SuppressLint("RtlHardcoded")
	private void initPopup() {
		
		View rootView = View.inflate(this, R.layout.player_popup, null);
		playerImpl.setup(rootView);
		
		updateScreenSize();
		
		final boolean popupRememberSizeAndPos = PlayerHelper.isRememberingPopupDimensions(this);
		final float defaultSize = getResources().getDimension(R.dimen.popup_default_width);
		SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		popupWidth = popupRememberSizeAndPos ? sharedPreferences.getFloat(POPUP_SAVED_WIDTH, defaultSize) : defaultSize;
		
		final int layoutParamType = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_PHONE : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
		
		windowLayoutParams = new WindowManager.LayoutParams((int) popupWidth, (int) getMinimumVideoHeight(popupWidth), layoutParamType, IDLE_WINDOW_FLAGS, PixelFormat.TRANSLUCENT);
		windowLayoutParams.gravity = Gravity.LEFT | Gravity.TOP;
		windowLayoutParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
		
		int centerX = (int) (screenWidth / 2f - popupWidth / 2f);
		int centerY = (int) (screenHeight / 2f - popupHeight / 2f);
		windowLayoutParams.x = popupRememberSizeAndPos ? sharedPreferences.getInt(POPUP_SAVED_X, centerX) : centerX;
		windowLayoutParams.y = popupRememberSizeAndPos ? sharedPreferences.getInt(POPUP_SAVED_Y, centerY) : centerY;
		
		checkPositionBounds();
		
		PopupWindowOnGestureListener listener = new PopupWindowOnGestureListener();
		gestureDetector = new GestureDetector(this, listener);
		rootView.setOnTouchListener(listener);
		
		playerImpl.getLoadingPanel().setMinimumWidth(windowLayoutParams.width);
		playerImpl.getLoadingPanel().setMinimumHeight(windowLayoutParams.height);
		windowManager.addView(rootView, windowLayoutParams);
	}
	
	private void resetNotification() {
		
		// if playerImpl is null
		if (playerImpl == null) {
			
			// toast error
			Toast.makeText(this, R.string.player_stream_failure, Toast.LENGTH_SHORT).show();
			return;
		}
		// else create notification
		notBuilder = createNotification();
	}
	
	private NotificationCompat.Builder createNotification() {
		
		notRemoteView = new RemoteViews(BuildConfig.APPLICATION_ID, R.layout.player_popup_notification);
		
		notRemoteView.setTextViewText(R.id.notificationSongName, playerImpl.getVideoTitle());
		notRemoteView.setTextViewText(R.id.notificationArtist, playerImpl.getUploaderName());
		notRemoteView.setImageViewBitmap(R.id.notificationCover, playerImpl.getThumbnail());
		
		notRemoteView.setOnClickPendingIntent(R.id.notificationPlayPause, PendingIntent.getBroadcast(this, NOTIFICATION_ID, new Intent(ACTION_PLAY_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT));
		notRemoteView.setOnClickPendingIntent(R.id.notificationStop, PendingIntent.getBroadcast(this, NOTIFICATION_ID, new Intent(ACTION_CLOSE), PendingIntent.FLAG_UPDATE_CURRENT));
		notRemoteView.setOnClickPendingIntent(R.id.notificationRepeat, PendingIntent.getBroadcast(this, NOTIFICATION_ID, new Intent(ACTION_REPEAT), PendingIntent.FLAG_UPDATE_CURRENT));
		
		// Starts popup player activity -- attempts to unlock lockscreen
		final Intent intent = NavigationHelper.getPopupPlayerActivityIntent(this);
		notRemoteView.setOnClickPendingIntent(R.id.notificationContent, PendingIntent.getActivity(this, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT));
		
		setRepeatModeRemote(notRemoteView, playerImpl.getRepeatMode());
		
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
				.setOngoing(true)
				.setSmallIcon(R.drawable.ic_library_music_white_24dp)
				.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
				.setContent(notRemoteView);
		builder.setPriority(NotificationCompat.PRIORITY_MAX);
		return builder;
	}
	
	/**
	 * Updates the notification, and the play/pause button in it.
	 * Used for changes on the remoteView
	 *
	 * @param drawableId if != -1, sets the drawable with that id on the play/pause button
	 */
	private void updateNotification(int drawableId) {
		
		if (notBuilder == null || notRemoteView == null) return;
		if (drawableId != -1) notRemoteView.setImageViewResource(R.id.notificationPlayPause, drawableId);
		if (notificationManager != null) {
			notificationManager.notify(NOTIFICATION_ID, notBuilder.build());
		}
	}
	
	// Misc
	public void onClosePopup() {
		
		if (playerImpl != null) {
			playerImpl.savePlaybackState();
			if (playerImpl.getRootView() != null) {
				windowManager.removeView(playerImpl.getRootView());
			}
			playerImpl.setRootView(null);
			playerImpl.stopActivityBinding();
			playerImpl.destroy();
			playerImpl = null;
		}
		
		mBinder = null;
		if (notificationManager != null) notificationManager.cancel(NOTIFICATION_ID);
		
		stopForeground(true);
		stopSelf();
	}
	
	// Utils
	private void checkPositionBounds() {
		
		if (windowLayoutParams.x > screenWidth - windowLayoutParams.width)
			windowLayoutParams.x = (int) (screenWidth - windowLayoutParams.width);
		if (windowLayoutParams.x < 0) windowLayoutParams.x = 0;
		if (windowLayoutParams.y > screenHeight - windowLayoutParams.height)
			windowLayoutParams.y = (int) (screenHeight - windowLayoutParams.height);
		if (windowLayoutParams.y < 0) windowLayoutParams.y = 0;
	}
	
	private void savePositionAndSize() {
		
		SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(PopupVideoPlayer.this);
		sharedPreferences.edit().putInt(POPUP_SAVED_X, windowLayoutParams.x).apply();
		sharedPreferences.edit().putInt(POPUP_SAVED_Y, windowLayoutParams.y).apply();
		sharedPreferences.edit().putFloat(POPUP_SAVED_WIDTH, windowLayoutParams.width).apply();
	}
	
	private float getMinimumVideoHeight(float width) {
		
		// respect the 16:9 ratio that most videos have
		return width / (16.0f / 9.0f);
	}
	
	private void updateScreenSize() {
		
		DisplayMetrics metrics = new DisplayMetrics();
		windowManager.getDefaultDisplay().getMetrics(metrics);
		
		screenWidth = metrics.widthPixels;
		screenHeight = metrics.heightPixels;
		
		popupWidth = getResources().getDimension(R.dimen.popup_default_width);
		popupHeight = getMinimumVideoHeight(popupWidth);
		
		minimumWidth = getResources().getDimension(R.dimen.popup_minimum_width);
		minimumHeight = getMinimumVideoHeight(minimumWidth);
		
		maximumWidth = screenWidth;
		maximumHeight = screenHeight;
	}
	
	private void updatePopupSize(int width, int height) {
		
		if (playerImpl == null) return;
		
		width = (int) (width > maximumWidth ? maximumWidth : width < minimumWidth ? minimumWidth : width);
		
		if (height == -1) height = (int) getMinimumVideoHeight(width);
		else height = (int) (height > maximumHeight ? maximumHeight : height < minimumHeight ? minimumHeight : height);
		
		windowLayoutParams.width = width;
		windowLayoutParams.height = height;
		popupWidth = width;
		popupHeight = height;
		
		windowManager.updateViewLayout(playerImpl.getRootView(), windowLayoutParams);
	}
	
	protected void setRepeatModeRemote(final RemoteViews remoteViews, final int repeatMode) {
		
		final String methodName = "setImageResource";
		
		if (remoteViews == null) return;
		
		switch (repeatMode) {
			case Player.REPEAT_MODE_OFF:
				remoteViews.setInt(R.id.notificationRepeat, methodName, R.drawable.controls_repeat_off);
				break;
			case Player.REPEAT_MODE_ONE:
				remoteViews.setInt(R.id.notificationRepeat, methodName, R.drawable.controls_repeat_one);
				break;
			case Player.REPEAT_MODE_ALL:
				remoteViews.setInt(R.id.notificationRepeat, methodName, R.drawable.controls_repeat_all);
				break;
		}
	}
	
	private void updateWindowFlags(final int flags) {
		
		if (windowLayoutParams == null || windowManager == null || playerImpl == null) return;
		
		windowLayoutParams.flags = flags;
		windowManager.updateViewLayout(playerImpl.getRootView(), windowLayoutParams);
	}
	
	private void onScreenOnOff(boolean on) {
		
		shouldUpdateOnProgress = on;
		playerImpl.triggerProgressUpdate();
		if (on) {
			playerImpl.startProgressLoop();
		}
		else {
			playerImpl.stopProgressLoop();
		}
	}
	
	protected class VideoPlayerImpl extends VideoPlayer implements View.OnLayoutChangeListener {
		
		private TextView resizingIndicator;
		private ImageButton fullScreenButton;
		private ImageView videoPlayPause;
		private ImageButton btnClosePopup;
		
		private View extraOptionsView;
		
		@Override
		public void handleIntent(Intent intent) {
			
			super.handleIntent(intent);
			
			resetNotification();
			if (notRemoteView != null) notRemoteView.setProgressBar(R.id.progress_bar_notification, 100, 0, false);
			startForeground(NOTIFICATION_ID, notBuilder.build());
		}
		
		VideoPlayerImpl(final Context context) {
			super("VideoPlayerImpl", context);
		}
		
		@Override
		public void initViews(View rootView) {
			
			super.initViews(rootView);
			
			resizingIndicator = rootView.findViewById(R.id.resizing_indicator);
			fullScreenButton = rootView.findViewById(R.id.btn_play_fullscreen);
			fullScreenButton.setOnClickListener(v -> onFullScreenButtonClicked());
			videoPlayPause = rootView.findViewById(R.id.videoPlayPause);
			videoPlayPause.setOnClickListener(v -> onPlayPause());
			
			extraOptionsView = rootView.findViewById(R.id.extraOptionsView);
			rootView.addOnLayoutChangeListener(this);
			
			btnClosePopup = rootView.findViewById(R.id.btn_close_popup);
			btnClosePopup.setOnClickListener(v -> onClosePopup());
		}
		
		@Override
		protected void setupSubtitleView(@NonNull SubtitleView view, final float captionScale, @NonNull final CaptionStyleCompat captionStyle) {
			
			float captionRatio = (captionScale - 1f) / 5f + 1f;
			view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * captionRatio);
			view.setApplyEmbeddedStyles(captionStyle.equals(CaptionStyleCompat.DEFAULT));
			view.setStyle(captionStyle);
		}
		
		@Override
		public void onLayoutChange(final View view, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
			
			float widthDp = Math.abs(right - left) / getResources().getDisplayMetrics().density;
			final int visibility = widthDp > MINIMUM_SHOW_EXTRA_WIDTH_DP ? View.VISIBLE : View.GONE;
			extraOptionsView.setVisibility(visibility);
		}
		
		@Override
		public void destroy() {
			
			if (notRemoteView != null) notRemoteView.setImageViewBitmap(R.id.notificationCover, null);
			super.destroy();
		}
		
		@Override
		public void onFullScreenButtonClicked() {
			
			super.onFullScreenButtonClicked();
			
			setRecovery();
			Intent intent = NavigationHelper.getPlayerIntent(
					context,
					MainVideoPlayer.class,
					this.getPlayQueue(),
					this.getRepeatMode(),
					this.getPlaybackSpeed(),
					this.getPlaybackPitch(),
					this.getPlaybackSkipSilence(),
					this.getPlaybackQuality()
			);
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			context.startActivity(intent);
			onClosePopup();
		}
		
		@Override
		public void onDismiss(PopupMenu menu) {
			
			super.onDismiss(menu);
			
			if (isPlaying()) hideControls(500, 0);
		}
		
		@Override
		public void onStopTrackingTouch(SeekBar seekBar) {
			
			super.onStopTrackingTouch(seekBar);
			
			if (wasPlaying()) {
				hideControls(100, 0);
			}
		}
		
		@Override
		public void onShuffleClicked() {
			
			super.onShuffleClicked();
			
			updatePlayback();
		}
		
		@Override
		public void onUpdateProgress(int currentProgress, int duration, int bufferPercent) {
			
			updateProgress(currentProgress, duration, bufferPercent);
			super.onUpdateProgress(currentProgress, duration, bufferPercent);
			
			if (!shouldUpdateOnProgress) return;
			
			if (notRemoteView != null) {
				notRemoteView.setProgressBar(R.id.progress_bar_notification, duration, currentProgress, false);
			}
			updateNotification(-1);
		}
		
		@Override
		protected VideoPlaybackResolver.QualityResolver getQualityResolver() {
			
			return new VideoPlaybackResolver.QualityResolver() {
				
				@Override
				public int getDefaultResolutionIndex(List<VideoStream> sortedVideos) {
					return ListHelper.getPopupDefaultResolutionIndex(context, sortedVideos);
				}
				
				@Override
				public int getOverrideResolutionIndex(List<VideoStream> sortedVideos, String playbackQuality) {
					
					return ListHelper.getPopupResolutionIndex(context, sortedVideos, playbackQuality);
				}
			};
		}
		
		// Thumbnail Loading
		@Override
		public void onLoadingComplete(String imageUri, View view, Bitmap loadedImage) {
			
			super.onLoadingComplete(imageUri, view, loadedImage);
			
			if (playerImpl == null) return;
			
			// rebuild notification here since remote view does not release bitmaps, causing memory leaks
			resetNotification();
			updateNotification(-1);
		}
		
		@Override
		public void onLoadingFailed(String imageUri, View view, FailReason failReason) {
			
			super.onLoadingFailed(imageUri, view, failReason);
			
			resetNotification();
			updateNotification(-1);
		}
		
		@Override
		public void onLoadingCancelled(String imageUri, View view) {
			
			super.onLoadingCancelled(imageUri, view);
			
			resetNotification();
			updateNotification(-1);
		}
		
		// Activity Event Listener
		void setActivityListener(PlayerEventListener listener) {
			
			activityListener = listener;
			updateMetadata();
			updatePlayback();
			triggerProgressUpdate();
		}
		
		void removeActivityListener(PlayerEventListener listener) {
			
			if (activityListener == listener) {
				activityListener = null;
			}
		}
		
		private void updateMetadata() {
			
			if (activityListener != null && getCurrentMetadata() != null) {
				activityListener.onMetadataUpdate(getCurrentMetadata().getMetadata());
			}
		}
		
		private void updatePlayback() {
			
			if (activityListener != null && simpleExoPlayer != null && playQueue != null) {
				
				activityListener.onPlaybackUpdate(currentState, getRepeatMode(), playQueue.isShuffled(), simpleExoPlayer.getPlaybackParameters());
			}
		}
		
		private void updateProgress(int currentProgress, int duration, int bufferPercent) {
			
			if (activityListener != null) {
				activityListener.onProgressUpdate(currentProgress, duration, bufferPercent);
			}
		}
		
		private void stopActivityBinding() {
			
			if (activityListener != null) {
				activityListener.onServiceStopped();
				activityListener = null;
			}
		}
		
		// ExoPlayer Video Listener
		@Override
		public void onRepeatModeChanged(int i) {
			
			super.onRepeatModeChanged(i);
			
			setRepeatModeRemote(notRemoteView, i);
			updatePlayback();
			resetNotification();
			updateNotification(-1);
		}
		
		@Override
		public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
			
			super.onPlaybackParametersChanged(playbackParameters);
			updatePlayback();
		}
		
		// Playback Listener
		protected void onMetadataChanged(@NonNull final MediaSourceTag tag) {
			
			super.onMetadataChanged(tag);
			resetNotification();
			updateNotification(-1);
			updateMetadata();
		}
		
		@Override
		public void onPlaybackShutdown() {
			
			super.onPlaybackShutdown();
			onClosePopup();
		}
		
		// Broadcast Receiver
		@Override
		protected void setupBroadcastReceiver(IntentFilter intentFilter) {
			
			super.setupBroadcastReceiver(intentFilter);
			intentFilter.addAction(ACTION_CLOSE);
			intentFilter.addAction(ACTION_PLAY_PAUSE);
			intentFilter.addAction(ACTION_REPEAT);
			
			intentFilter.addAction(Intent.ACTION_SCREEN_ON);
			intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
		}
		
		@Override
		public void onBroadcastReceived(Intent intent) {
			
			super.onBroadcastReceived(intent);
			
			if (intent.getAction() == null) return;
			
			switch (intent.getAction()) {
				
				case ACTION_CLOSE:
					onClosePopup();
					break;
				case ACTION_PLAY_PAUSE:
					onPlayPause();
					break;
				case ACTION_REPEAT:
					onRepeatClicked();
					break;
				case Intent.ACTION_SCREEN_ON:
					enableVideoRenderer(true);
					onScreenOnOff(true);
					break;
				case Intent.ACTION_SCREEN_OFF:
					enableVideoRenderer(false);
					onScreenOnOff(false);
					break;
			}
		}
		
		// States
		@Override
		public void changeState(int state) {
			
			super.changeState(state);
			updatePlayback();
		}
		
		@Override
		public void onBlocked() {
			
			super.onBlocked();
			
			resetNotification();
			updateNotification(R.drawable.ic_play_arrow_white_24dp);
		}
		
		@Override
		public void onPlaying() {
			
			super.onPlaying();
			
			updateWindowFlags(ONGOING_PLAYBACK_WINDOW_FLAGS);
			
			resetNotification();
			updateNotification(R.drawable.ic_pause_white);
			
			videoPlayPause.setBackgroundResource(R.drawable.ic_pause_white);
			hideControls(DEFAULT_CONTROLS_DURATION, DEFAULT_CONTROLS_HIDE_TIME);
			
			startForeground(NOTIFICATION_ID, notBuilder.build());
		}
		
		@Override
		public void onBuffering() {
			
			super.onBuffering();
			
			resetNotification();
			updateNotification(R.drawable.ic_play_arrow_white_24dp);
		}
		
		@Override
		public void onPaused() {
			
			super.onPaused();
			
			updateWindowFlags(IDLE_WINDOW_FLAGS);
			
			resetNotification();
			updateNotification(R.drawable.ic_play_arrow_white_24dp);
			
			videoPlayPause.setBackgroundResource(R.drawable.ic_play_arrow_white_24dp);
			
			stopForeground(false);
		}
		
		@Override
		public void onPausedSeek() {
			
			super.onPausedSeek();
			
			resetNotification();
			updateNotification(R.drawable.ic_play_arrow_white_24dp);
		}
		
		@Override
		public void onCompleted() {
			
			super.onCompleted();
			
			updateWindowFlags(IDLE_WINDOW_FLAGS);
			
			resetNotification();
			updateNotification(R.drawable.ic_replay_white);
			
			if (notRemoteView != null) {
				notRemoteView.setProgressBar(R.id.progress_bar_notification, 100, 100, false);
			}
			
			videoPlayPause.setBackgroundResource(R.drawable.ic_replay_white);
			
			stopForeground(false);
		}
		
		@Override
		public void showControlsThenHide() {
			
			videoPlayPause.setVisibility(View.VISIBLE);
			
			super.showControlsThenHide();
		}
		
		public void showControls(long duration) {
			
			videoPlayPause.setVisibility(View.VISIBLE);
			
			super.showControls(duration);
		}
		
		public void hideControls(final long duration, long delay) {
			
			super.hideControlsAndButton(duration, delay, videoPlayPause);
		}
		
		// Utils
		void enableVideoRenderer(final boolean enable) {
			
			final int videoRendererIndex = getRendererIndex(C.TRACK_TYPE_VIDEO);
			if (videoRendererIndex != RENDERER_UNAVAILABLE) {
				trackSelector.setParameters(trackSelector.buildUponParameters().setRendererDisabled(videoRendererIndex, !enable));
			}
		}
		
		// Getters
		@SuppressWarnings("WeakerAccess")
		public TextView getResizingIndicator() {
			return resizingIndicator;
		}
	}
	
	private class PopupWindowOnGestureListener extends GestureDetector.SimpleOnGestureListener implements View.OnTouchListener {
		
		private int initialPopupX, initialPopupY;
		private boolean isMoving;
		
		private int onDownPopupWidth = 0;
		private boolean isResizing;
		private boolean isResizingRightSide;
		
		@Override
		public boolean onDoubleTap(MotionEvent e) {
			
			if (playerImpl == null || !playerImpl.isPlaying()) return false;
			
			playerImpl.hideControls(0, 0);
			
			if (e.getX() > popupWidth / 2) {
				playerImpl.onFastForward();
			}
			else {
				playerImpl.onFastRewind();
			}
			return true;
		}
		
		@Override
		public boolean onSingleTapConfirmed(MotionEvent e) {
			
			if (playerImpl == null || playerImpl.getPlayer() == null) return false;
			if (playerImpl.isControlsVisible()) {
				playerImpl.hideControls(200, 200);
			}
			else {
				playerImpl.showControlsThenHide();
				
			}
			return true;
		}
		
		@Override
		public boolean onDown(MotionEvent e) {
			
			initialPopupX = windowLayoutParams.x;
			initialPopupY = windowLayoutParams.y;
			popupWidth = windowLayoutParams.width;
			popupHeight = windowLayoutParams.height;
			onDownPopupWidth = windowLayoutParams.width;
			return super.onDown(e);
		}
		
		@Override
		public void onLongPress(MotionEvent e) {
			
			updateScreenSize();
			checkPositionBounds();
			
			if (playerImpl != null) {
				AnimationUtils.animateView(playerImpl.getCurrentDisplaySeek(), false, 0, 0);
				AnimationUtils.animateView(playerImpl.getResizingIndicator(), true, 200, 0);
			}
			isResizing = true;
			isResizingRightSide = e.getRawX() > windowLayoutParams.x + (windowLayoutParams.width / 2f);
		}
		
		@Override
		public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
			
			if (isResizing || playerImpl == null) return super.onScroll(e1, e2, distanceX, distanceY);
			
			if (playerImpl.getCurrentState() != BasePlayer.STATE_BUFFERING && (!isMoving || playerImpl.getControlsRoot().getAlpha() != 1f))
				playerImpl.showControls(0);
			isMoving = true;
			
			float diffX = (int) (e2.getRawX() - e1.getRawX()), posX = (int) (initialPopupX + diffX);
			float diffY = (int) (e2.getRawY() - e1.getRawY()), posY = (int) (initialPopupY + diffY);
			
			if (posX > (screenWidth - popupWidth)) posX = (int) (screenWidth - popupWidth);
			else if (posX < 0) posX = 0;
			
			if (posY > (screenHeight - popupHeight)) posY = (int) (screenHeight - popupHeight);
			else if (posY < 0) posY = 0;
			
			windowLayoutParams.x = (int) posX;
			windowLayoutParams.y = (int) posY;
			
			windowManager.updateViewLayout(playerImpl.getRootView(), windowLayoutParams);
			return true;
		}
		
		private void onScrollEnd() {
			
			if (playerImpl == null) return;
			if (playerImpl.isControlsVisible() && playerImpl.getCurrentState() == BasePlayer.STATE_PLAYING) {
				playerImpl.hideControls(VideoPlayer.DEFAULT_CONTROLS_DURATION, VideoPlayer.DEFAULT_CONTROLS_HIDE_TIME);
			}
		}
		
		@SuppressLint("ClickableViewAccessibility")
		@Override
		public boolean onTouch(View v, MotionEvent event) {
			
			gestureDetector.onTouchEvent(event);
			if (event.getAction() == MotionEvent.ACTION_MOVE && isResizing && !isMoving) {
				int width;
				if (isResizingRightSide) {
					width = (int) event.getRawX() - windowLayoutParams.x;
				}
				else {
					width = (int) (windowLayoutParams.width + (windowLayoutParams.x - event.getRawX()));
					if (width > minimumWidth) windowLayoutParams.x = initialPopupX - (width - onDownPopupWidth);
				}
				if (width <= maximumWidth && width >= minimumWidth) updatePopupSize(width, -1);
				return true;
			}
			
			if (event.getAction() == MotionEvent.ACTION_UP) {
				
				if (isMoving) {
					isMoving = false;
					onScrollEnd();
				}
				
				if (isResizing) {
					isResizing = false;
					AnimationUtils.animateView(playerImpl.getResizingIndicator(), false, 100, 0);
					playerImpl.changeState(playerImpl.getCurrentState());
				}
				savePositionAndSize();
			}
			return true;
		}
		
		private boolean handleMultiDrag(final MotionEvent event) {
			
			if (event.getPointerCount() != 2) return false;
			
			final float firstPointerX = event.getX(0);
			final float secondPointerX = event.getX(1);
			
			final float diff = Math.abs(firstPointerX - secondPointerX);
			if (firstPointerX > secondPointerX) {
				// second pointer is the anchor (the leftmost pointer)
				windowLayoutParams.x = (int) (event.getRawX() - diff);
			}
			else {
				// first pointer is the anchor
				windowLayoutParams.x = (int) event.getRawX();
			}
			
			checkPositionBounds();
			updateScreenSize();
			
			final int width = (int) Math.min(screenWidth, diff);
			updatePopupSize(width, -1);
			return true;
		}
	}
}