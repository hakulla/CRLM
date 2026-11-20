package org.mobileagent.agent;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.media.ExifInterface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Locale;

public class MainActivity extends Activity {
    private static java.lang.ref.WeakReference<MainActivity> currentRef;

    static AgentEngine debugEngine() {
        MainActivity activity = current();
        return activity == null ? null : activity.engine;
    }

    static boolean debugOpenConversation(final String id) {
        final MainActivity activity = current();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return false;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.openConversation(id);
            }
        });
        return true;
    }

    static boolean debugNewConversation() {
        final MainActivity activity = current();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return false;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.createConversation();
            }
        });
        return true;
    }

    static MainActivity current() {
        return currentRef == null ? null : currentRef.get();
    }

    private static final int REQUEST_STORAGE = 2201;
    private static final int REQUEST_SCREEN_CAPTURE = 2202;
    private static final int REQUEST_MODEL_IMPORT = 2204;
    private static final int REQUEST_PICK_IMAGES = 2206;
    private static final int REQUEST_PICK_FILES = 2207;
    private static final int REQUEST_CAMERA = 2208;
    static final String EXTRA_CONVERSATION_ID = "conversation_id";
    static final String EXTRA_NEW_TASK_PROMPT = "new_task_prompt";
    private static final int MAX_ATTACHMENT_COUNT = 6;
    private static final int MAX_FILE_COUNT = 8;
    private static final long MAX_FILE_BYTES = 50L * 1024L * 1024L;
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static final int BG = Color.rgb(10, 10, 10);
    static final int CARD = Color.rgb(22, 22, 22);
    static final int CARD_SOFT = Color.rgb(28, 28, 28);
    static final int CARD_USER = Color.rgb(28, 28, 28);
    static final int CARD_TOOL = Color.rgb(17, 17, 17);
    static final int DIVIDER = Color.rgb(35, 35, 35);
    static final int TEXT = Color.rgb(244, 244, 244);
    static final int MUTED = Color.rgb(158, 158, 158);
    static final int DIM = Color.rgb(105, 105, 105);
    static final int ACCENT = Color.rgb(255, 255, 255);
    static final int ERROR = Color.rgb(255, 107, 107);
    static final int SUCCESS = Color.rgb(118, 214, 170);
    static final int ICON_BG = Color.rgb(32, 32, 32);
    private static final String STATE_CHAT_SHOWN = "ui_chat_shown";
    private static final String STATE_VIEWING_RUN = "ui_viewing_run";
    private static final String STATE_VIEWED_CONVERSATION = "ui_viewed_conversation";
    private static final String STATE_ENGINE_CONVERSATION = "ui_engine_conversation";
    /** The last round of engines is running to detect errors in the 'mission just ended' to consume queues. */
    private boolean lastRunActive;
    /** Queue the hints only once to avoid repeating them each time they return to the front desk. */
    private boolean queuedReportHintShown;
    private final Runnable queuedReportRunnable = new Runnable() {
        @Override
        public void run() {
            consumeQueuedReport();
        }
    };

    private final AgentEngine.Listener agentListener = new AgentEngine.Listener() {
        @Override
        public void onAgentUpdate() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            // Operation just ended: Queueing errors in the consumer task centre (new conversation processing), avoiding the need to do something about it but the sea is sunk.
            boolean runningNow = engine.isRunning();
            if (lastRunActive && !runningNow) {
                handler.post(queuedReportRunnable);
            }
            lastRunActive = runningNow;
            if (!engine.isRunning() && !viewingRunningTask && viewedConversation != null) {
                if (switchingView) {
                    return;
                }
                switchingView = true;
                boolean opened = engine.openConversation(viewedConversation.id);
                viewingRunningTask = true;
                viewedConversation = null;
                switchingView = false;
                if (opened) {
                    workspace = engine.workspace();
                }
                renderAll();
                return;
            }
            if (!viewingRunningTask) {
                updateStatus();
                return;
            }
            if (drawerOpen) {
                updateStatus();
                return;
            }
            scheduleRender();
        }
    };

    private AppPrefs prefs;
    private Workspace workspace;
    private volatile AgentEngine engine;
    private SessionManager sessions;
    private PinScrollView feedScroll;
    private FeedList feedList;
    private EditText input;
    private Button sendButton;
    private TextView statusText;
    private TextView titleView;
    private FrameLayout screenRoot;
    private LinearLayout screenshotModeBar;
    private boolean screenshotReceiverRegistered;
    private boolean cameraMode;
    private boolean cameraCaptureInProgress;
    private PopupWindow cameraPopup;
    private SurfaceView cameraSurface;
    private Camera camera;
    private boolean cameraReady;
    private boolean cameraClosing;
    private FrameLayout drawerPanel;
    private LinearLayout drawerList;
    private LinearLayout homeFilterRow;
    private TextView taskCenterChipText;
    private boolean drawerOpen = true;
    private boolean activityResumed;
    private boolean homeGroupByWorkspace = true;
    private String homeFilter = "*";
    private boolean showArchived = false;
    private TextView modelText;
    private Dialog settingsDialog;
    private boolean settingsDirty;
    private ImageView modeIcon;
    private LinearLayout runStatusBar;
    private LinearLayout planBarHost;
    private TextView runStatusText;
    private TextView runStepText;
    private TextView runLimitText;
    private TextView runPauseText;
    private TextView runStopText;
    private ImageView runIcon;
    private ProgressBar runSpinner;
    private boolean feedAtBottom = true;
    private String lastFeedKey = "";
    private final java.util.Set<Long> reasoningOpen = new java.util.HashSet<>();
    private final java.util.Set<Long> reasoningClosed = new java.util.HashSet<>();
    /** Planboard development status: by default close, only the user is recorded for the start-up key.  */
    private final java.util.Set<String> planExpanded = new java.util.HashSet<>();
    /** Phase folding state:key Take the first of those stages entry.time.  */
    private final java.util.Set<Long> stageOpen = new java.util.HashSet<>();
    private final java.util.Set<Long> stageClosed = new java.util.HashSet<>();
    /** The results of the questionnaire:key Yes entry.time, Values are saved in the order in which they are given. */
    private final java.util.Map<Long, String[]> questionAnswers = new java.util.HashMap<>();
    private static final String UI_PREFS = "mobileagent_ui";
    private static final String KEY_QUESTION_ANSWERS = "question_answers";
    private boolean questionAnswersLoaded;
    /** The issue card that has been automatically loaded in this process avoids the flow of fresh and repeated windows. */
    private final java.util.Set<Long> autoOpenedQuestions = new java.util.HashSet<>();
    private boolean holdScroll = false;
    private int visibleEntryLimit = 80;
    private boolean requestedStorage;
    private TextView settingsModeValue;
    private TextView settingsWebValue;
    private boolean preprocessing;
    private Dialog preprocessWaitDialog;
    private long preprocessStartedAt;
    private static final long PREPROCESS_STUCK_MS = 45000L;
    private Conversation viewedConversation;
    private boolean viewingRunningTask = true;
    private boolean switchingView;
    private LinearLayout viewedBar;
    private LinearLayout attachStrip;
    private HorizontalScrollView attachScroll;
    private LinearLayout fileStrip;
    private HorizontalScrollView fileScroll;
    private final BroadcastReceiver screenshotReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ScreenshotWatchService.ACTION_STOPPED.equals(intent.getAction())) {
                updateScreenshotModeBar();
                return;
            }
            loadPendingScreenshots();
        }
    };

    private final List<AgentEngine.ImageAttachment> pendingImages = new ArrayList<>();
    private final List<AgentEngine.FileAttachment> pendingFiles = new ArrayList<>();
    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean renderPending;
    private final Runnable renderRunnable = new Runnable() {
        @Override
        public void run() {
            renderPending = false;
            if (!drawerOpen && !isFinishing() && !isDestroyed()) {
                renderAll();
            }
        }
    };

    /** Round-the-ground inspection: Autocorrection of pre-processed card death, running in a state inconsistent with interface. */
    private final Runnable healthRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            checkPreprocessStuck();
            engine.forceFinishIfStuck();
            handler.postDelayed(this, 5000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentRef = new java.lang.ref.WeakReference<>(this);
        Log.i("MobileAgent", "BUILD_TAG lite-20261030 v0.25.0");
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        prefs = new AppPrefs(this);
        loadQuestionAnswers();
        engine = AgentEngine.get(this);
        sessions = SessionManager.get(this);
        sessions.register(engine);
        workspace = engine.workspace();

        setContentView(buildUi());
        engine.setListener(agentListener);
        installApprovalHandler();
        renderAll();
        requestStoragePermission();
        requestNotificationPermission();
        engine.recoverInterruptedRun();
        startBridgeAutoIfEnabled();
        SourceBootstrap.ensureAsync(this);
        BootstrapInstaller.ensureAsync(this, () -> Rootfs.startInBackground(this));
        AutomationService.startIfEnabled(this);
        boolean intentHandled = openConversationFromIntent(getIntent());
        if (!intentHandled) {
            restoreUiState(savedInstanceState);
            // Since the update App Re-start: If exists'Waiting for installation results'Dialogue, automatically return to it and run again.
            resumePendingInstallConversation();
        }
        consumeQueuedReport();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openConversationFromIntent(intent);
    }

    private boolean openConversationFromIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        String prompt = intent.getStringExtra(EXTRA_NEW_TASK_PROMPT);
        if (prompt != null && !prompt.trim().isEmpty()) {
            intent.removeExtra(EXTRA_NEW_TASK_PROMPT);
            openNewTaskFromReport(prompt);
            return true;
        }
        String id = intent.getStringExtra(EXTRA_CONVERSATION_ID);
        if (id == null || id.trim().isEmpty()) {
            return false;
        }
        intent.removeExtra(EXTRA_CONVERSATION_ID);
        openConversation(id.trim());
        return true;
    }

    /** Activity When recreated after system recovery, you return the page and dialogue before you leave, avoiding falling back to the dialogue list. */
    private void restoreUiState(Bundle state) {
        if (state == null) {
            return;
        }
        if (!state.getBoolean(STATE_CHAT_SHOWN, false)) {
            return;
        }
        String engineId = state.getString(STATE_ENGINE_CONVERSATION);
        String viewedId = state.getString(STATE_VIEWED_CONVERSATION);
        String target = state.getBoolean(STATE_VIEWING_RUN, false) ? engineId : viewedId;
        if (target != null && !target.trim().isEmpty()) {
            openConversation(target.trim());
            return;
        }
        showChat();
        renderAll();
    }

    /** Mistakes in the consumption queue after the end of the mission: New dialogue brought to the scene (only during free time). */
    private void consumeQueuedReport() {
        if (engine.isRunning() || isFinishing() || isDestroyed()) {
            return;
        }
        String prompt = prefs.pendingTaskPrompt();
        if (prompt == null || prompt.trim().isEmpty()) {
            queuedReportHintShown = false;
            return;
        }
        prefs.clearPendingTaskPrompt();
        queuedReportHintShown = false;
        openNewTaskFromReport(prompt);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_CHAT_SHOWN, !drawerOpen);
        outState.putBoolean(STATE_VIEWING_RUN, viewingRunningTask);
        if (viewedConversation != null) {
            outState.putString(STATE_VIEWED_CONVERSATION, viewedConversation.id);
        }
        String engineId = engine.currentConversationId();
        if (engineId != null) {
            outState.putString(STATE_ENGINE_CONVERSATION, engineId);
        }
    }

    /** Mission Centre'Dealing with errors': New dialogue and full site, sent after user confirmation; task execution queues, automatically open after completion. */
    private void openNewTaskFromReport(String prompt) {
        prompt = sanitizeRepairPrompt(prompt);
        if (engine.isRunning()) {
            // Run without discarding: Queue to save after the current task is finished onAgentUpdate AutoNew Dialog Process
            prefs.setPendingTaskPrompt(prompt);
            if (!queuedReportHintShown) {
                queuedReportHintShown = true;
                showQueuedReportDialog(prompt);
            }
            return;
        }
        prefs.clearPendingTaskPrompt();
        queuedReportHintShown = false;
        createConversation();
        if (input != null) {
            input.setText(prompt);
            input.setSelection(input.getText().length());
            input.requestFocus();
        }
        showChat();
                Toast.makeText(this, "We've been brought to the wrong reporting site, confirmation post-dispatch to start processing.",
                Toast.LENGTH_LONG).show();
    }

    /**
     * The old version of the wrong paper puts the source session title and 'Target: Continue' at the top of the list, and the model can easily take the old task as a new one.
     * The entry was uniformly rewritten as a restoration mission boundary, and historical papers did not have to be moved article by article.
     */
    private static String sanitizeRepairPrompt(String prompt) {
        if (prompt == null || !prompt.contains("[Error reporting tool for autorecording")) {
            return prompt;
        }
        if (prompt.contains("Mission: final failure of repair")
                || prompt.contains("[♪ Fix the border ♪")) {
            return prompt;
        }
        String body = prompt
                .replace("[Auto-recording tool to report error]", "[Auto-recording tool to correct bugs")
                .replace("\nSession:", "\nSource session (for location only, not for the current mission):")
                .replace("\nObjectives:", "\nSource session target (for location only):");
        String header = "This time only the last failed tool is repaired; no operational tasks in the source session title or in the old plan are performed.\n\n";
        int first = body.indexOf('\n');
        if (first < 0) {
            return header + body;
        }
        return body.substring(0, first + 1) + header + body.substring(first + 1);
    }

    /** Task is being called in.'Dealing with errors'Gives visible feedback and avoids one flash Toast It's like it's not responding. */
    private void showQueuedReportDialog(final String prompt) {
        String preview = prompt == null ? "" : prompt.trim().replaceAll("\\s+", " ");
        if (preview.length() > 160) {
            preview = preview.substring(0, 160) + "...";
        }
        try {
            new AlertDialog.Builder(this)
                    .setTitle("The current task is still ongoing")
                    .setMessage("The error is queued and a new dialogue is automatically created and pasted after the end of the current task.\n\nPreview:" + preview)
                    .setPositiveButton("Copy Error", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("report", prompt == null ? "" : prompt));
                                Toast.makeText(MainActivity.this, "Errors reported copied", Toast.LENGTH_SHORT).show();
                            }
                        }
                    })
                    .setNegativeButton("Okay.", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "The current task is still ongoing, errors are queued and will be automatically created for dialogue after completion",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startBridgeAutoIfEnabled() {
        if (prefs.debugBridgeEnabled()) {
            DebugBridge.prepare(this);
            BridgeAutoService.start(this);
        } else {
            DebugBridge.writeDisabledMarker(this);
            if (prefs.bridgeAutoEnabled()) {
                BridgeAutoService.start(this);
            }
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2207);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        SourceBootstrap.ensureAsync(this);
        BootstrapInstaller.ensureAsync(this, () -> Rootfs.startInBackground(this));
        registerScreenshotReceiver();
        loadPendingScreenshots();
        updateScreenshotModeBar();
        workspace = engine.workspace();
        engine.setListener(agentListener);
        installApprovalHandler();
        if (!engine.isRunning() && !viewingRunningTask && viewedConversation != null) {
            if (engine.openConversation(viewedConversation.id)) {
                viewingRunningTask = true;
                viewedConversation = null;
                workspace = engine.workspace();
            }
        }
        renderAll();
        engine.backfillRecentErrorsNow();
        // When clicking 'Turn Up Wrong' WorkCenter It's written at the same time. prefs Bottom of the pocket:
        // If onNewIntent Because of the job ink/The plant agreed system was not delivered and returned to the front desk to make it possible to avoid the loss of incorrect texts.
        String pendingPrompt = prefs.pendingTaskPrompt();
        if (pendingPrompt != null && !pendingPrompt.trim().isEmpty()) {
            if (!engine.isRunning()) {
                prefs.clearPendingTaskPrompt();
                openNewTaskFromReport(pendingPrompt);
            } else if (!queuedReportHintShown) {
                queuedReportHintShown = true;
                Toast.makeText(this, "Queue pending error reporting and automatically create a new dialogue after the end of the current mission",
                        Toast.LENGTH_LONG).show();
            }
        }
        LanguageManager.apply(getWindow().getDecorView());
        handler.removeCallbacks(healthRunnable);
        handler.postDelayed(healthRunnable, 5000L);
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        closeCameraPreview();
        unregisterScreenshotReceiver();
        handler.removeCallbacks(healthRunnable);
        // Do not fully permanence on the main route (the session may be large); combine saving in the back.
        engine.saveCurrentAsync();
        LocalConfig.flushAsync();
        engine.setListener(null);
        // Do not cancel the approval channel when you reach the backstage:Agent Open Target App / Screenshot / The video is a video of the video.
        // The engine is still running backstage, and write-off will allow follow-up equipment-type tools to be directly judged as 'not available' and interrupt the mission.
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) {
            super.onBackPressed();
            return;
        }
        showHome();
    }

    private View buildUi() {
        screenRoot = new FrameLayout(this);
        screenRoot.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        ImageView back = vectorButton("ic_back", TEXT, 40);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHome();
            }
        });
        header.addView(back, iconSize(dp(40), 0));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(dp(11), 0, dp(6), 0);
        titleBlock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConversationSettings();
            }
        });
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, WRAP, 1f));

        titleView = new TextView(this);
        titleView.setText(engine.currentTitle());
        titleView.setTextColor(TEXT);
        titleView.setTextSize(18f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleBlock.addView(titleView, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout subtitleRow = new LinearLayout(this);
        subtitleRow.setOrientation(LinearLayout.HORIZONTAL);
        subtitleRow.setGravity(Gravity.CENTER_VERTICAL);
        subtitleRow.setPadding(0, dp(2), 0, 0);
        titleBlock.addView(subtitleRow, new LinearLayout.LayoutParams(MATCH, WRAP));
        ImageView subtitleIcon = vectorIcon("ic_device", DIM);
        subtitleRow.addView(subtitleIcon, iconSize(dp(12), dp(5)));
        statusText = new TextView(this);
        statusText.setTextSize(10.5f);
        statusText.setTextColor(MUTED);
        statusText.setSingleLine(true);
        statusText.setEllipsize(TextUtils.TruncateAt.END);
        subtitleRow.addView(statusText, new LinearLayout.LayoutParams(0, WRAP, 1f));

        ImageView folder = vectorButton("ic_folder", MUTED, 38);
        folder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showArtifacts("");
            }
        });
        header.addView(folder, iconSize(dp(38), dp(5)));

        ImageView more = vectorButton("ic_more", TEXT, 38);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChatMenu(v);
            }
        });
        header.addView(more, iconSize(dp(38), dp(5)));

        View divider = new View(this);
        divider.setBackgroundColor(DIVIDER);
        root.addView(divider, new LinearLayout.LayoutParams(MATCH, 1));

        feedScroll = new PinScrollView(this);
        feedScroll.setFillViewport(true);
        feedScroll.setClipToPadding(false);
        feedScroll.setVerticalScrollBarEnabled(false);
        feedScroll.setPadding(dp(12), dp(8), dp(12), dp(4));
        feedList = new FeedList(this);
        feedList.setOrientation(LinearLayout.VERTICAL);
        feedScroll.addView(feedList, new ScrollView.LayoutParams(MATCH, WRAP));
        feedScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY,
                                       int oldScrollX, int oldScrollY) {
                View child = feedScroll.getChildAt(0);
                if (child == null) {
                    return;
                }
                int viewport = feedScroll.getHeight()
                        - feedScroll.getPaddingTop() - feedScroll.getPaddingBottom();
                int distance = child.getBottom() - (scrollY + viewport);
                feedAtBottom = distance <= dp(28);
                // only record location; whether to bottom/Keep your eyes open and give them to me. renderAll, Avoiding Scrolling Event Feedback leading to Whip
            }
        });
        root.addView(feedScroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        LinearLayout composerShell = new LinearLayout(this);
        composerShell.setOrientation(LinearLayout.VERTICAL);
        composerShell.setPadding(dp(10), dp(3), dp(10), dp(8));
        root.addView(composerShell, new LinearLayout.LayoutParams(MATCH, WRAP));

        viewedBar = new LinearLayout(this);
        viewedBar.setOrientation(LinearLayout.HORIZONTAL);
        viewedBar.setGravity(Gravity.CENTER_VERTICAL);
        viewedBar.setBackground(rounded(CARD_SOFT, 12));
        viewedBar.setPadding(dp(10), dp(6), dp(6), dp(6));
        viewedBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams viewedBarParams =
                new LinearLayout.LayoutParams(MATCH, WRAP);
        viewedBarParams.setMargins(0, 0, 0, dp(5));
        composerShell.addView(viewedBar, viewedBarParams);
        ImageView viewedIcon = vectorIcon("ic_sparkle", Color.rgb(232, 188, 92));
        viewedBar.addView(viewedIcon, iconSize(dp(14), dp(6)));
        TextView viewedText = new TextView(this);
        viewedText.setText("Another dialogue mission continues in the backstage.");
        viewedText.setTextColor(MUTED);
        viewedText.setTextSize(11f);
        viewedText.setPadding(dp(7), 0, dp(7), 0);
        viewedBar.addView(viewedText, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView viewedReturn = new TextView(this);
        viewedReturn.setText("Return to ongoing dialogue");
        viewedReturn.setTextColor(Color.rgb(232, 188, 92));
        viewedReturn.setTextSize(11f);
        viewedReturn.setPadding(dp(8), dp(4), dp(8), dp(4));
        viewedReturn.setClickable(true);
        viewedReturn.setFocusable(true);
        viewedReturn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                returnToRunningConversation();
            }
        });
        viewedBar.addView(viewedReturn, new LinearLayout.LayoutParams(WRAP, WRAP));

        screenshotModeBar = new LinearLayout(this);
        screenshotModeBar.setOrientation(LinearLayout.HORIZONTAL);
        screenshotModeBar.setGravity(Gravity.CENTER_VERTICAL);
        screenshotModeBar.setBackground(rounded(CARD_SOFT, 12));
        screenshotModeBar.setPadding(dp(10), dp(6), dp(6), dp(6));
        screenshotModeBar.setVisibility(View.GONE);
        TextView screenshotModeText = new TextView(this);
        screenshotModeText.setText("Screenshot mode: use a system screenshot to automatically add a screenshot to the dialogue");
        screenshotModeText.setTextColor(MUTED);
        screenshotModeText.setTextSize(10.5f);
        screenshotModeBar.addView(screenshotModeText,
                new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView screenshotModeExit = new TextView(this);
        screenshotModeExit.setText("Quit");
        screenshotModeExit.setTextColor(ERROR);
        screenshotModeExit.setTextSize(10.5f);
        screenshotModeExit.setPadding(dp(8), dp(4), dp(8), dp(4));
        screenshotModeExit.setClickable(true);
        screenshotModeExit.setFocusable(true);
        screenshotModeExit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (screenshotModeBar != null) {
                    screenshotModeBar.setVisibility(View.GONE);
                }
                ScreenshotWatchService.stop(MainActivity.this);
                updateScreenshotModeBar();
                Toast.makeText(MainActivity.this, "Exited Screen Screenshot Mode",
                        Toast.LENGTH_SHORT).show();
            }
        });
        screenshotModeBar.addView(screenshotModeExit,
                new LinearLayout.LayoutParams(WRAP, WRAP));
        LinearLayout.LayoutParams screenshotBarParams =
                new LinearLayout.LayoutParams(MATCH, WRAP);
        screenshotBarParams.setMargins(0, 0, 0, dp(5));
        composerShell.addView(screenshotModeBar, screenshotBarParams);

        planBarHost = new LinearLayout(this);
        planBarHost.setOrientation(LinearLayout.VERTICAL);
        planBarHost.setVisibility(View.GONE);
        composerShell.addView(planBarHost, new LinearLayout.LayoutParams(MATCH, WRAP));

        runStatusBar = new LinearLayout(this);
        runStatusBar.setOrientation(LinearLayout.HORIZONTAL);
        runStatusBar.setGravity(Gravity.CENTER_VERTICAL);
        runStatusBar.setPadding(dp(8), dp(2), dp(8), dp(5));
        runStatusBar.setVisibility(View.GONE);
        composerShell.addView(runStatusBar, new LinearLayout.LayoutParams(MATCH, WRAP));

        runIcon = vectorIcon("ic_sparkle", MUTED);
        runStatusBar.addView(runIcon, iconSize(dp(14), dp(6)));
        runSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        runSpinner.setIndeterminate(true);
        runSpinner.setVisibility(View.GONE);
        runSpinner.setIndeterminateTintList(ColorStateList.valueOf(Color.rgb(176, 176, 176)));
        LinearLayout.LayoutParams spinnerParams =
                new LinearLayout.LayoutParams(dp(16), dp(16));
        spinnerParams.setMargins(0, 0, dp(6), 0);
        runStatusBar.addView(runSpinner, spinnerParams);
        runStatusText = new TextView(this);
        runStatusText.setText(engine.runPhase().isEmpty() ? "Task in progress..." : engine.runPhase());
        runStatusText.setTextColor(MUTED);
        runStatusText.setTextSize(11f);
        runStatusBar.addView(runStatusText, new LinearLayout.LayoutParams(0, WRAP, 1f));
        runStepText = new TextView(this);
        runStepText.setText("0/10");
        runStepText.setTextColor(MUTED);
        runStepText.setTextSize(9.5f);
        runStepText.setBackground(rounded(CARD, 9));
        runStepText.setPadding(dp(8), dp(2), dp(8), dp(2));
        runStatusBar.addView(runStepText, new LinearLayout.LayoutParams(WRAP, WRAP));

        runLimitText = new TextView(this);
        runLimitText.setText("Breaking the ceiling.");
        runLimitText.setTextColor(TEXT);
        runLimitText.setTextSize(9.5f);
        runLimitText.setBackground(rounded(CARD_SOFT, 9));
        runLimitText.setPadding(dp(9), dp(3), dp(9), dp(3));
        runLimitText.setClickable(true);
        runLimitText.setFocusable(true);
        runLimitText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleOverLimit(true);
            }
        });
        LinearLayout.LayoutParams limitParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        limitParams.setMargins(dp(6), 0, 0, 0);
        runStatusBar.addView(runLimitText, limitParams);

        runPauseText = new TextView(this);
        runPauseText.setText("Pause");
        runPauseText.setTextColor(TEXT);
        runPauseText.setTextSize(9.5f);
        runPauseText.setBackground(rounded(CARD_SOFT, 9));
        runPauseText.setPadding(dp(9), dp(3), dp(9), dp(3));
        runPauseText.setClickable(true);
        runPauseText.setFocusable(true);
        runPauseText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (engine.isPaused()) {
                    if (engine.resumeTask()) {
                        renderAll();
                        Toast.makeText(MainActivity.this, "Keep on with your mission.",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, "Cannot continue: the context of the mission is not available",
                                Toast.LENGTH_SHORT).show();
                    }
                } else if (engine.isRunning()) {
                    engine.requestPause();
                    updateStatus();
                    Toast.makeText(MainActivity.this, "Suspension in progress, effective after completion of current steps",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
        LinearLayout.LayoutParams pauseParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        pauseParams.setMargins(dp(6), 0, 0, 0);
        runStatusBar.addView(runPauseText, pauseParams);

        runStopText = new TextView(this);
        runStopText.setText("Stop");
        runStopText.setTextColor(ERROR);
        runStopText.setTextSize(9.5f);
        runStopText.setBackground(rounded(CARD_SOFT, 9));
        runStopText.setPadding(dp(9), dp(3), dp(9), dp(3));
        runStopText.setClickable(true);
        runStopText.setFocusable(true);
        runStopText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                engine.requestStop();
                updateStatus();
            }
        });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        stopParams.setMargins(dp(6), 0, 0, 0);
        runStatusBar.addView(runStopText, stopParams);

        attachScroll = new HorizontalScrollView(this);
        attachScroll.setHorizontalScrollBarEnabled(false);
        attachScroll.setVisibility(View.GONE);
        attachStrip = new LinearLayout(this);
        attachStrip.setOrientation(LinearLayout.HORIZONTAL);
        attachStrip.setPadding(0, 0, 0, dp(6));
        attachScroll.addView(attachStrip, new HorizontalScrollView.LayoutParams(WRAP, WRAP));
        composerShell.addView(attachScroll, new LinearLayout.LayoutParams(MATCH, WRAP));

        fileScroll = new HorizontalScrollView(this);
        fileScroll.setHorizontalScrollBarEnabled(false);
        fileScroll.setVisibility(View.GONE);
        fileStrip = new LinearLayout(this);
        fileStrip.setOrientation(LinearLayout.HORIZONTAL);
        fileStrip.setPadding(0, 0, 0, dp(6));
        fileScroll.addView(fileStrip, new HorizontalScrollView.LayoutParams(WRAP, WRAP));
        composerShell.addView(fileScroll, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setBackground(rounded(CARD, 26));
        composer.setPadding(dp(4), dp(4), dp(4), dp(4));
        composerShell.addView(composer, new LinearLayout.LayoutParams(MATCH, WRAP));

        ImageView plus = vectorIcon("ic_plus", MUTED);
        plus.setPadding(dp(8), dp(8), dp(8), dp(8));
        plus.setClickable(true);
        plus.setFocusable(true);
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cameraMode) {
                    captureCameraPhoto();
                } else {
                    showComposerMenu(v);
                }
            }
        });
        composer.addView(plus, iconSize(dp(32), 0));

        modeIcon = vectorIcon("ic_alert", MUTED);
        modeIcon.setPadding(dp(6), dp(6), dp(6), dp(6));
        modeIcon.setClickable(true);
        modeIcon.setFocusable(true);
        modeIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModeSheet();
            }
        });
        composer.addView(modeIcon, iconSize(dp(27), dp(1)));

        input = new EditText(this);
        input.setHint("Describe your mission....");
        input.setHintTextColor(DIM);
        input.setTextColor(TEXT);
        input.setTextSize(13.5f);
        input.setMaxLines(4);
        input.setMinLines(1);
        input.setSingleLine(false);
        input.setBackground(null);
        input.setPadding(dp(5), dp(8), dp(5), dp(8));
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.addView(input, new LinearLayout.LayoutParams(0, WRAP, 1f));
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateStatus();
            }
        });

        modelText = new TextView(this);
        modelText.setText(engine.currentConversation() == null
                ? AppPrefs.DEFAULT_MODEL : modelLabel(engine.currentConversation()));
        modelText.setTextColor(TEXT);
        modelText.setTextSize(9f);
        modelText.setSingleLine(true);
        modelText.setEllipsize(TextUtils.TruncateAt.END);
        modelText.setMaxWidth(dp(78));
        modelText.setPadding(dp(4), dp(8), dp(4), dp(8));
        modelText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModelSheet();
            }
        });
        composer.addView(modelText, new LinearLayout.LayoutParams(WRAP, WRAP));

        sendButton = new Button(this);
        sendButton.setAllCaps(false);
        sendButton.setText("↑");
        sendButton.setTextSize(20f);
        sendButton.setTextColor(Color.rgb(8, 8, 8));
        sendButton.setBackground(rounded(Color.WHITE, 19));
        sendButton.setPadding(0, 0, 0, dp(1));
        sendButton.setMinimumWidth(0);
        sendButton.setMinimumHeight(0);
        sendButton.setStateListAnimator(null);
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSendOrStop();
            }
        });
        composer.addView(sendButton, iconSize(dp(36), dp(3)));

        screenRoot.addView(root, new FrameLayout.LayoutParams(MATCH, MATCH));

        drawerPanel = buildHomeView();
        screenRoot.addView(drawerPanel, new FrameLayout.LayoutParams(MATCH, MATCH));
        drawerOpen = true;
        drawerPanel.setVisibility(View.VISIBLE);
        renderHomeList();

        return screenRoot;
    }

    private FrameLayout buildHomeView() {
        FrameLayout home = new FrameLayout(this);
        home.setBackgroundColor(BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        home.addView(content, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(15), dp(10), dp(3));
        content.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        ImageView grid = vectorIcon("ic_grid", TEXT);
        header.addView(grid, iconSize(dp(22), 0));

        TextView title = new TextView(this);
        title.setText("All Dialogues");
        title.setTextColor(TEXT);
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(10), 0, dp(3), 0);
        header.addView(title, new LinearLayout.LayoutParams(WRAP, WRAP));

        ImageView chevron = vectorIcon("ic_chevron_down", MUTED);
        chevron.setPadding(dp(2), dp(6), dp(2), dp(6));
        chevron.setClickable(true);
        chevron.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHomeMenu(v);
            }
        });
        header.addView(chevron, iconSize(dp(18), 0));

        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        if (prefs.hasKey()) {
            View dot = new View(this);
            dot.setBackground(rounded(SUCCESS, 4));
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(7), dp(7));
            dotParams.setMargins(0, 0, dp(10), 0);
            header.addView(dot, dotParams);
        }

        ImageView more = vectorIcon("ic_more", TEXT);
        more.setPadding(dp(8), dp(8), dp(8), dp(8));
        more.setClickable(true);
        more.setFocusable(true);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHomeMenu(v);
            }
        });
        header.addView(more, iconSize(dp(34), 0));

        HorizontalScrollView filters = new HorizontalScrollView(this);
        filters.setHorizontalScrollBarEnabled(false);
        filters.setClipToPadding(false);
        filters.setPadding(dp(10), dp(6), dp(10), dp(2));
        homeFilterRow = new LinearLayout(this);
        homeFilterRow.setOrientation(LinearLayout.HORIZONTAL);
        filters.addView(homeFilterRow, new HorizontalScrollView.LayoutParams(WRAP, WRAP));
        content.addView(filters, new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        drawerList = new LinearLayout(this);
        drawerList.setOrientation(LinearLayout.VERTICAL);
        drawerList.setPadding(dp(16), 0, dp(16), dp(90));
        scroll.addView(drawerList, new ScrollView.LayoutParams(MATCH, WRAP));
        content.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        ImageView fab = vectorIcon("ic_plus", Color.rgb(8, 8, 8));
        fab.setBackground(rounded(Color.WHITE, 27));
        fab.setPadding(dp(16), dp(16), dp(16), dp(16));
        fab.setClickable(true);
        fab.setFocusable(true);
        fab.setElevation(dp(8));
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createConversation();
            }
        });
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(dp(54), dp(54),
                Gravity.BOTTOM | Gravity.END);
        fabParams.setMargins(0, 0, dp(20), dp(28));
        home.addView(fab, fabParams);

        return home;
    }

    private void showHome() {
        hideKeyboard();
        closeCameraPreview();
        drawerOpen = true;
        if (drawerPanel != null) {
            drawerPanel.setVisibility(View.VISIBLE);
            renderHomeList();
        }
    }

    private void showChat() {
        drawerOpen = false;
        if (drawerPanel != null) {
            drawerPanel.setVisibility(View.GONE);
        }
        updateStatus();
    }

    private void renderHomeList() {
        if (drawerList == null || homeFilterRow == null) {
            return;
        }
        List<Conversation.Summary> summaries = engine.listConversations();
        LinkedHashSet<String> workspaces = new LinkedHashSet<>();
        for (Conversation.Summary summary : summaries) {
            workspaces.add(summary.workspace == null ? "" : summary.workspace);
        }
        if (!"*".equals(homeFilter) && !workspaces.contains(homeFilter)) {
            homeFilter = "*";
        }

        homeFilterRow.removeAllViews();
        addHomeFilterChip("All", "*", null);
        addTaskCenterChip();
        addArchiveToggleChip();
        for (String workspace : workspaces) {
            addHomeFilterChip(workspaceLabel(workspace), workspace, "ic_folder");
        }

        List<Conversation.Summary> filtered = new java.util.ArrayList<>();
        for (Conversation.Summary summary : summaries) {
            if (summary.archived != showArchived) {
                continue;
            }
            String workspace = summary.workspace == null ? "" : summary.workspace;
            if ("*".equals(homeFilter) || workspace.equals(homeFilter)) {
                filtered.add(summary);
            }
        }

        drawerList.removeAllViews();
        if (filtered.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(showArchived ? "No archive conversation yet." : "No conversation yet.");
            empty.setTextColor(MUTED);
            empty.setTextSize(14f);
            empty.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(MATCH, WRAP);
            emptyParams.setMargins(0, dp(90), 0, 0);
            drawerList.addView(empty, emptyParams);
            TextView hint = new TextView(this);
            hint.setText("Bottom-right corner of point + Start a new mission");
            hint.setTextColor(DIM);
            hint.setTextSize(11f);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, dp(8), 0, 0);
            drawerList.addView(hint, new LinearLayout.LayoutParams(MATCH, WRAP));
            return;
        }

        if (homeGroupByWorkspace) {
            Map<String, List<Conversation.Summary>> groups = new LinkedHashMap<>();
            for (Conversation.Summary summary : filtered) {
                String workspace = summary.workspace == null ? "" : summary.workspace;
                List<Conversation.Summary> group = groups.get(workspace);
                if (group == null) {
                    group = new java.util.ArrayList<>();
                    groups.put(workspace, group);
                }
                group.add(summary);
            }
            for (Map.Entry<String, List<Conversation.Summary>> entry : groups.entrySet()) {
                addHomeGroupHeader(workspaceLabel(entry.getKey()));
                for (Conversation.Summary summary : entry.getValue()) {
                    addHomeConversationRow(summary);
                }
            }
        } else {
            long now = System.currentTimeMillis();
            long day = 24L * 60 * 60 * 1000;
            String[] labels = {"Today", "Yesterday.", "Even earlier."};
            List<List<Conversation.Summary>> buckets = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                buckets.add(new java.util.ArrayList<Conversation.Summary>());
            }
            for (Conversation.Summary summary : filtered) {
                long age = now - summary.updatedAt;
                int bucket = age < day ? 0 : age < 2 * day ? 1 : 2;
                buckets.get(bucket).add(summary);
            }
            for (int i = 0; i < 3; i++) {
                if (buckets.get(i).isEmpty()) {
                    continue;
                }
                addHomeGroupHeader(labels[i]);
                for (Conversation.Summary summary : buckets.get(i)) {
                    addHomeConversationRow(summary);
                }
            }
        }
    }

    private void addHomeFilterChip(String label, String value, String iconName) {
        boolean selected = value.equals(homeFilter);
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(rounded(selected ? Color.WHITE : CARD, 18));
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        chip.setClickable(true);
        chip.setFocusable(true);
        if (iconName != null) {
            ImageView icon = vectorIcon(iconName, selected ? Color.rgb(8, 8, 8) : MUTED);
            chip.addView(icon, iconSize(dp(14), dp(6)));
        }
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(selected ? Color.rgb(8, 8, 8) : MUTED);
        text.setTextSize(12f);
        chip.addView(text, new LinearLayout.LayoutParams(WRAP, WRAP));
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                homeFilter = value;
                renderHomeList();
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(WRAP, WRAP);
        params.setMargins(0, 0, dp(8), 0);
        homeFilterRow.addView(chip, params);
    }

    private void addTaskCenterChip() {
        WorkStore store = WorkSupervisor.get(this).store;
        int running = store.byStatus(WorkStore.RUNNING).size();
        int pending = store.needsAttention().size();
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(rounded(CARD, 18));
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.addView(vectorIcon("ic_grid", pending > 0 ? Color.rgb(232, 188, 92) : MUTED),
                iconSize(dp(14), dp(6)));
        TextView text = new TextView(this);
        text.setText("Mission Centre (MK)" + running + "/" + pending + ")");
        text.setTextColor(pending > 0 ? Color.rgb(232, 188, 92) : MUTED);
        text.setTextSize(12f);
        taskCenterChipText = text;
        chip.addView(text, new LinearLayout.LayoutParams(WRAP, WRAP));
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, WorkCenterActivity.class));
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(WRAP, WRAP);
        params.setMargins(0, 0, dp(8), 0);
        homeFilterRow.addView(chip, params);
    }

    private void updateTaskCenterChip() {
        if (taskCenterChipText == null) {
            return;
        }
        WorkStore store = WorkSupervisor.get(this).store;
        int running = store.byStatus(WorkStore.RUNNING).size();
        int pending = store.needsAttention().size();
        taskCenterChipText.setText("Mission Centre (MK)" + running + "/" + pending + ")");
        taskCenterChipText.setTextColor(pending > 0 ? Color.rgb(232, 188, 92) : MUTED);
    }

    private void addArchiveToggleChip() {
        boolean selected = showArchived;
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackground(rounded(selected ? Color.WHITE : CARD, 18));
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        chip.setClickable(true);
        chip.setFocusable(true);
        TextView text = new TextView(this);
        text.setText(selected ? "Archived" : "Archive");
        text.setTextColor(selected ? Color.rgb(8, 8, 8) : MUTED);
        text.setTextSize(12f);
        chip.addView(text, new LinearLayout.LayoutParams(WRAP, WRAP));
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showArchived = !showArchived;
                renderHomeList();
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(WRAP, WRAP);
        params.setMargins(0, 0, dp(8), 0);
        homeFilterRow.addView(chip, params);
    }

    private void addHomeGroupHeader(String label) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(16), dp(4), dp(5));
        ImageView icon = vectorIcon("ic_folder", DIM);
        header.addView(icon, iconSize(dp(15), dp(7)));
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(MUTED);
        text.setTextSize(11.5f);
        header.addView(text, new LinearLayout.LayoutParams(0, WRAP, 1f));
        drawerList.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void addHomeConversationRow(final Conversation.Summary summary) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(5), dp(8), dp(1), dp(8));
        row.setClickable(true);
        row.setFocusable(true);

        ImageView icon = vectorIcon("ic_sparkle", MUTED);
        row.addView(icon, iconSize(dp(18), dp(10)));

        TextView title = new TextView(this);
        title.setText(summary.title);
        title.setTextColor(TEXT);
        title.setTextSize(14.5f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));

        ImageView itemMenu = vectorIcon("ic_more", DIM);
        itemMenu.setPadding(dp(6), dp(6), dp(6), dp(6));
        itemMenu.setClickable(true);
        itemMenu.setFocusable(true);
        itemMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showConversationItemMenu(summary, v);
            }
        });
        row.addView(itemMenu, iconSize(dp(28), 0));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openConversation(summary.id);
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showConversationItemMenu(summary, v);
                return true;
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.setMargins(0, 1, 0, 1);
        drawerList.addView(row, params);
    }

    private void switchEngine(AgentEngine next) {
        if (next == null || next == engine) {
            return;
        }
        engine.saveCurrentAsync();
        engine.setListener(null);
        engine = next;
        engine.setListener(agentListener);
                workspace = engine.workspace();
        prefs.setLastConversationId(engine.currentConversationId());
        // Key: Synchronize the switch engine'Current Active Dialogue', Otherwise, you'll return when you update your descent. lastConversationId, 
        // The new dialogue is the only one that has led to a resumption of dialogue (e.g. the last new empty dialogue).
        InstallWatcher.setActiveConversation(this, engine.currentConversationId());
    }

    private void createConversation() {
        if (!engine.isRunning() && engine.getEntries().isEmpty()
                && engine.currentConversation().messages.length() == 0) {
            showChat();
            return;
        }
        try {
            closeCameraPreview();
            AgentEngine next = sessions.create(prefs, engine.currentConversation());
            switchEngine(next);
            visibleEntryLimit = 80;
            clearPendingAttachments();
            showChat();
            renderAll();
        } catch (Exception e) {
            Toast.makeText(this, "Could not close temporary folder: %s" + e.getClass().getSimpleName(),
                    Toast.LENGTH_SHORT).show();
        }
    }

        /** Re-launch after installation upgrades have been completed: return to dialogue awaiting installation results and run again automatically. */
    private boolean resumePendingInstallConversation() {
        final String pendingId = InstallWatcher.takePendingConversation(this);
        if (pendingId == null) {
            return false;
        }
        if (engine.loadConversationForView(pendingId) == null) {
            android.util.Log.w("InstallWatcher", "pending conversation missing: " + pendingId);
            Toast.makeText(this, "Last conversation waiting for installation no longer exists", Toast.LENGTH_SHORT).show();
            return false;
        }
        android.util.Log.i("InstallWatcher", "resume pending conversation: " + pendingId);
        openConversation(pendingId);
        if (input == null) {
            return true;
        }
        input.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (input == null || input.getText() == null) {
                    return;
                }
                input.setText("((a) The application has just been updated and restarted, with confirmation that it will be readable;"
                        + "Successfully installed, the task ahead of the installation continues, and the failure explains the reason.");
                input.setSelection(input.getText().length());
                onSendOrStop();
            }
        }, 800);
        return true;
    }

    private void openConversation(String id) {
        InstallWatcher.setActiveConversation(this, id);
        AgentEngine target = sessions.get(id);
        if (target == null) {
            Conversation conversation = engine.loadConversationForView(id);
            if (conversation == null) {
                Toast.makeText(this, "Failed to open dialogue", Toast.LENGTH_SHORT).show();
                return;
            }
            target = sessions.open(conversation);
        }
        if (target == null) {
            Toast.makeText(this, "Failed to open dialogue", Toast.LENGTH_SHORT).show();
            return;
        }
        closeCameraPreview();
        switchEngine(target);
        viewingRunningTask = true;
        viewedConversation = null;
        visibleEntryLimit = 80;
        clearPendingAttachments();
        showChat();
        renderAll();
    }

    private void showConversationItemMenu(final Conversation.Summary summary, View anchor) {
        if (summary.archived) {
            showAgentMenu(anchor, summary.title,
                    new String[]{"Open", "Cancel Archive", "Delete"},
                    new String[]{"ic_sparkle", "ic_archive", "ic_close"},
                    new int[]{TEXT, TEXT, ERROR},
                    new Runnable[]{
                            new Runnable() {
                                @Override
                                public void run() {
                                    openConversation(summary.id);
                                }
                            },
                            new Runnable() {
                                @Override
                                public void run() {
                                    unarchiveConversation(summary);
                                }
                            },
                            new Runnable() {
                                @Override
                                public void run() {
                                    confirmDeleteConversation(summary);
                                }
                            }
                    });
            return;
        }
        showAgentMenu(anchor, summary.title,
                new String[]{"Open", "Rename", "Archive", "Delete"},
                new String[]{"ic_sparkle", "ic_file", "ic_archive", "ic_close"},
                new int[]{TEXT, TEXT, TEXT, ERROR},
                new Runnable[]{
                        new Runnable() {
                            @Override
                            public void run() {
                                openConversation(summary.id);
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                showRenameConversation(summary);
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                archiveConversation(summary);
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                confirmDeleteConversation(summary);
                            }
                        }
                });
    }

    private void archiveConversation(final Conversation.Summary summary) {
        if (engine.setConversationArchived(summary.id, true)) {
            Toast.makeText(this, "Archived, available in Archived", Toast.LENGTH_SHORT).show();
            renderAll();
            renderHomeList();
        } else {
            Toast.makeText(this, "Archive failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void unarchiveConversation(final Conversation.Summary summary) {
        if (engine.setConversationArchived(summary.id, false)) {
            Toast.makeText(this, "Canceled Archive", Toast.LENGTH_SHORT).show();
            renderAll();
            renderHomeList();
        } else {
            Toast.makeText(this, "Failed to cancel archive", Toast.LENGTH_SHORT).show();
        }
    }

    private void showRenameConversation(final Conversation.Summary summary) {
        AgentEngine target = sessions.get(summary.id);
        if ((target != null && target.isRunning())
                || (engine.isRunning() && summary.id.equals(engine.currentConversationId()))) {
            Toast.makeText(this, "The dialogue is under way, stop and rename", Toast.LENGTH_SHORT).show();
            return;
        }
        AgentKit.input(this, "Rename Dialogue", "Dialogue title", summary.title, "Save",
                new AgentKit.InputHandler() {
                    @Override
                    public boolean onInput(String value, Dialog dialog) {
                        if (value.isEmpty()) {
                            Toast.makeText(MainActivity.this, "Please enter the title",
                                    Toast.LENGTH_SHORT).show();
                            return false;
                        }
                        if (engine.renameConversation(summary.id, value)) {
                            if (target != null && target != engine) {
                                sessions.remove(summary.id);
                            }
                            renderAll();
                            renderHomeList();
                            return true;
                        }
                        Toast.makeText(MainActivity.this, "Rename failed",
                                Toast.LENGTH_SHORT).show();
                        return false;
                    }
                });
    }

    private void confirmDeleteConversation(final Conversation.Summary summary) {
        AgentEngine target = sessions.get(summary.id);
        if ((target != null && target.isRunning())
                || (engine.isRunning() && summary.id.equals(engine.currentConversationId()))) {
            Toast.makeText(this, "The dialogue is under way, stop and then delete.", Toast.LENGTH_SHORT).show();
            return;
        }
        AgentKit.confirm(this, "Delete the conversation?",
                summary.title + "\nCould not be restored after deletion.", "Delete", true,
                new Runnable() {
                    @Override
                    public void run() {
                        if (engine.deleteConversation(summary.id)) {
                            sessions.remove(summary.id);
                            sessions.register(engine);
                            workspace = engine.workspace();
                            renderAll();
                            renderHomeList();
                        } else {
                            Toast.makeText(MainActivity.this, "Failed to delete",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                }, true);
    }

    private void showChatMenu(View anchor) {
        if (!viewingRunningTask) {
            showViewedConversationMenu(anchor);
            return;
        }
        Conversation current = engine.currentConversation();
        if (current == null) {
            return;
        }
        final Conversation.Summary summary = current.summary(currentWorkspacePathLabel());
        showAgentMenu(anchor, "Current Dialogue",
                new String[]{"Session Settings", "Session Rewind", "Long-term memory", "Mission Centre",
                        "Rename", "Documentation", "Delete"},
                new String[]{"ic_settings", "ic_sparkle", "ic_sparkle", "ic_grid",
                        "ic_file", "ic_folder", "ic_close"},
                new int[]{TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, ERROR},
                new Runnable[]{
                        new Runnable() {
                            @Override
                            public void run() {
                                showConversationSettings();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                showSessionReview();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                showMemory();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                startActivity(new Intent(MainActivity.this, WorkCenterActivity.class));
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                showRenameConversation(summary);
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                showFiles();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                confirmDeleteConversation(summary);
                            }
                        }
                });
    }

    private void showHomeMenu(View anchor) {
        showAgentMenu(anchor, "Other Organiser",
                new String[]{
                        homeGroupByWorkspace ? "Division of groups by job ✓" : "Division of groups by job",
                        homeGroupByWorkspace ? "Group by Date" : "Group by Date ✓",
                        "Import Dialogue",
                        "Automation",
                        "Mission Centre",
                        "Settings"
                },
                new String[]{"ic_grid", "ic_file", "ic_file", "ic_archive", "ic_grid", "ic_settings"},
                new int[]{TEXT, TEXT, TEXT, TEXT, TEXT, TEXT},
                new Runnable[]{
                        new Runnable() {
                            @Override
                            public void run() {
                                homeGroupByWorkspace = true;
                                renderHomeList();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                homeGroupByWorkspace = false;
                                renderHomeList();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                importConversationsFromWorkspace();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                startActivity(new Intent(MainActivity.this, AutomationActivity.class));
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                startActivity(new Intent(MainActivity.this, WorkCenterActivity.class));
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                showSettingsScreen();
                            }
                        }
                });
    }

    private void importConversationsFromWorkspace() {
        if (engine.isRunning()) {
            Toast.makeText(this, "Task in progress, stop before import", Toast.LENGTH_SHORT).show();
            return;
        }
        int imported = 0;
        try {
            File dir = engine.baseWorkspace().resolve("imports");
            File[] files = dir.listFiles();
            if (files != null) {
                java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });
                for (File file : files) {
                    if (!file.isFile() || !file.getName().endsWith(".json")) {
                        continue;
                    }
                    try {
                        JSONObject object = new JSONObject(readTextFile(file));
                        Conversation conversation = Conversation.fromJson(object);
                        if (conversation != null && engine.importConversation(conversation)) {
                            imported++;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (imported > 0) {
            workspace = engine.workspace();
            visibleEntryLimit = 80;
            showChat();
            renderAll();
            Toast.makeText(this, "Imported " + imported + " Dialogue", Toast.LENGTH_SHORT).show();
        } else {
            renderHomeList();
            Toast.makeText(this, "imports No new dialogue file in directory", Toast.LENGTH_SHORT).show();
        }
    }

    private static String readTextFile(File file) {
        try {
            java.io.FileInputStream input = new java.io.FileInputStream(file);
            try {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toString("UTF-8");
            } finally {
                input.close();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private void showViewedConversationMenu(View anchor) {
        String title = viewedConversation == null ? "Historical dialogue" : viewedConversation.title;
        showAgentMenu(anchor, title,
                new String[]{"Return to ongoing dialogue", "Suspension of ongoing tasks", "Stopping ongoing tasks"},
                new String[]{"ic_sparkle", "ic_alert", "ic_close"},
                new int[]{TEXT, Color.rgb(232, 188, 92), ERROR},
                new Runnable[]{
                        new Runnable() {
                            @Override
                            public void run() {
                                returnToRunningConversation();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                if (engine.isRunning()) {
                                    engine.requestPause();
                                    Toast.makeText(MainActivity.this,
                                            "Tasks in progress", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(MainActivity.this,
                                            "The mission is over.", Toast.LENGTH_SHORT).show();
                                }
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                if (engine.isRunning()) {
                                    engine.requestStop();
                                    Toast.makeText(MainActivity.this,
                                            "Stopping Tasks", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(MainActivity.this,
                                            "The mission is over.", Toast.LENGTH_SHORT).show();
                                }
                            }
                        }
                });
    }

    private void showComposerMenu(View anchor) {
        if (!viewingRunningTask) {
            Toast.makeText(this, "read-only viewing; actionable after returning ongoing conversations",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        hideKeyboard();
        showAgentMenu(anchor, "Add",
                new String[]{"Album Picture", "Take pictures.", "Screenshot", "System File", "Slash Command",
                        "Workspace File", "Build products", "Session Settings", "Capabilities and memory", "Devices and Permissions"},
                new String[]{"ic_image", "ic_camera", "ic_screenshot", "ic_file",
                        "ic_terminal", "ic_folder", "ic_archive", "ic_settings",
                        "ic_sparkle", "ic_device"},
                new int[]{TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT, TEXT},
                new Runnable[]{
                        new Runnable() {
                            @Override
                            public void run() {
                                pickImagesFromGallery();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                enterCameraMode();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                enterScreenshotWatchMode();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                pickFilesFromSystem();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                showSlashCommands();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                showFiles();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                showApkManager();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                showConversationSettings();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                showCapabilities();
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                showDevice();
                            }
                        }
                }, true);
    }

    private void showModelSheet() {
        if (engine.isRunning()) {
            Toast.makeText(this, "In the course of the assignment, stop and replace the model", Toast.LENGTH_SHORT).show();
            return;
        }
        final Conversation conversation = engine.currentConversation();
        if (conversation == null) {
            return;
        }
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(roundedTop(CARD, 22));
        panel.setPadding(0, dp(8), 0, dp(18));

        View handle = new View(this);
        handle.setBackground(rounded(Color.rgb(80, 80, 80), 2));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(38), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(handle, handleParams);

        FrameLayout header = new FrameLayout(this);
        panel.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView title = new TextView(this);
        title.setText("Select Model");
        title.setTextColor(TEXT);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new FrameLayout.LayoutParams(MATCH, dp(52)));
        ImageView close = vectorButton("ic_close", MUTED, 30);
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(30), dp(30),
                Gravity.END | Gravity.CENTER_VERTICAL);
        closeParams.setMargins(0, 0, dp(14), 0);
        header.addView(close, closeParams);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        TextView banner = new TextView(this);
        banner.setText("Only affect current dialogue · " + workspaceLabel(conversation.workspace));
        banner.setTextColor(SUCCESS);
        banner.setTextSize(11f);
        banner.setBackground(rounded(Color.rgb(17, 36, 27), 8));
        banner.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams bannerParams = new LinearLayout.LayoutParams(MATCH, WRAP);
        bannerParams.setMargins(dp(16), dp(2), dp(16), dp(8));
        panel.addView(banner, bannerParams);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackground(rounded(Color.rgb(28, 28, 28), 16));
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(MATCH, WRAP);
        tabParams.setMargins(dp(16), dp(6), dp(16), dp(6));
        panel.addView(tabs, tabParams);
        final TextView tabDefault = new TextView(this);
        tabDefault.setText("Clouds");
        tabDefault.setGravity(Gravity.CENTER);
        tabDefault.setTextSize(12.5f);
        tabDefault.setPadding(0, dp(8), 0, dp(8));
        tabDefault.setTextColor(Color.rgb(8, 8, 8));
        tabDefault.setBackground(rounded(Color.WHITE, 13));
        tabs.addView(tabDefault, new LinearLayout.LayoutParams(0, WRAP, 1f));
        final TextView tabCustom = new TextView(this);
        tabCustom.setText("Custom");
        tabCustom.setGravity(Gravity.CENTER);
        tabCustom.setTextSize(12.5f);
        tabCustom.setPadding(0, dp(8), 0, dp(8));
        tabCustom.setTextColor(MUTED);
        tabs.addView(tabCustom, new LinearLayout.LayoutParams(0, WRAP, 1f));
        final TextView tabLocal = new TextView(this);
        tabLocal.setText("Local");
        tabLocal.setGravity(Gravity.CENTER);
        tabLocal.setTextSize(12.5f);
        tabLocal.setPadding(0, dp(8), 0, dp(8));
        tabLocal.setTextColor(MUTED);
        tabs.addView(tabLocal, new LinearLayout.LayoutParams(0, WRAP, 1f));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(4), dp(16), 0);
        scroll.addView(list, new ScrollView.LayoutParams(MATCH, WRAP));
        panel.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        final int[] selectedTab = {0};
        final Runnable[] renderRowsRef = new Runnable[1];
        final Runnable renderRows = new Runnable() {
            @Override
            public void run() {
                list.removeAllViews();
                TextView[] tabsViews = {tabDefault, tabCustom, tabLocal};
                for (int i = 0; i < tabsViews.length; i++) {
                    boolean active = selectedTab[0] == i;
                    tabsViews[i].setTextColor(active ? Color.rgb(8, 8, 8) : MUTED);
                    tabsViews[i].setBackground(rounded(active ? Color.WHITE
                            : Color.TRANSPARENT, 13));
                }
                boolean cloudSource = Conversation.SOURCE_CLOUD.equals(
                        conversation.modelSource);
                if (selectedTab[0] == 2) {
                    LocalAi localAi = LocalAi.get(MainActivity.this);
                    if (!localAi.backendAvailable()) {
                        list.addView(formHint("Local running library not available, install complete APK. "),
                                new LinearLayout.LayoutParams(MATCH, WRAP));
                        return;
                    }
                    List<File> models = localAi.models();
                    if (models.isEmpty()) {
                        TextView empty = formHint("There are no local models."
                                + "Dot 'Local Model Management'...'Download or Import GGUF. ");
                        list.addView(empty, new LinearLayout.LayoutParams(MATCH, WRAP));
                    } else {
                        File selectedModel = localAi.selectedFile();
                        for (final File file : models) {
                            addLocalModelRow(list, file, localAi, selectedModel,
                                    dialog, selectedModel != null
                                            && file.equals(selectedModel)
                                            && !cloudSource);
                        }
                    }
                    TextView manage = new TextView(MainActivity.this);
                    manage.setText("Local Model Management...");
                    manage.setTextColor(MUTED);
                    manage.setTextSize(11.5f);
                    manage.setPadding(dp(6), dp(14), dp(6), dp(6));
                    manage.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dialog.dismiss();
                            showLocalAi();
                        }
                    });
                    list.addView(manage, new LinearLayout.LayoutParams(MATCH, WRAP));
                    return;
                }
                if (selectedTab[0] == 1) {
                    addModelRow(list, conversation.model, "Current Custom Model",
                            cloudSource, false, dialog, null);
                    addModelRow(list, "Custom Model...", "Enter any type OpenAI Compatible Model Name",
                            false, true, dialog, null);
                } else {
                    List<ModelService> services = prefs.services();
                    if (services.isEmpty()) {
                        list.addView(formHint("No model service exists. Add address and Key, "
                                        + "Re-feat the model."),
                                new LinearLayout.LayoutParams(MATCH, WRAP));
                    } else {
                        String currentService = conversationServiceId(conversation);
                        for (final ModelService service : services) {
                            final List<String> models = prefs.catalogModels(service.baseUrl);
                            TextView meta = new TextView(MainActivity.this);
                            meta.setText(service.displayName() + " · " + service.host()
                                    + " · " + (models.isEmpty() ? "No model retrieved"
                                    : models.size() + " Model") + " · Click to refresh");
                            meta.setTextColor(service.id.equals(currentService)
                                    ? SUCCESS : MUTED);
                            meta.setTextSize(10.5f);
                            meta.setPadding(dp(6), dp(10), dp(6), dp(6));
                            meta.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    refreshCatalog(service, renderRowsRef[0]);
                                }
                            });
                            list.addView(meta, new LinearLayout.LayoutParams(MATCH, WRAP));
                            if (models.isEmpty()) {
                                addActionRow(list, "Get Model List...",
                                        service.host() + " · " + service.keyState(),
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                dialog.dismiss();
                                                showModelServiceEditor(service.id);
                                            }
                                        });
                                continue;
                            }
                            boolean hasCurrent = false;
                            for (String id : models) {
                                if (service.id.equals(currentService)
                                        && id.equals(conversation.model)) {
                                    hasCurrent = true;
                                }
                                addCatalogModelRow(list, service, id, conversation, dialog);
                            }
                            if (!hasCurrent && service.id.equals(currentService)) {
                                addCatalogModelRow(list, service, conversation.model,
                                        conversation, dialog);
                                list.addView(formHint("The current model is not in the list of the service"),
                                        new LinearLayout.LayoutParams(MATCH, WRAP));
                            }
                        }
                    }
                    addActionRow(list, "Manage model services...",
                            prefs.services().size() + " Services · Can keep multiples at the same time",
                            new Runnable() {
                                @Override
                                public void run() {
                                    dialog.dismiss();
                                    showModelService();
                                }
                            });
                }
            }
        };
        renderRowsRef[0] = renderRows;
        tabDefault.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedTab[0] = 0;
                renderRows.run();
            }
        });
        tabCustom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedTab[0] = 1;
                renderRows.run();
            }
        });
        tabLocal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedTab[0] = 2;
                renderRows.run();
            }
        });
        renderRows.run();

        TextView more = new TextView(this);
        more.setText("More model parameters ›");
        more.setTextColor(MUTED);
        more.setTextSize(11f);
        more.setPadding(dp(18), dp(12), dp(18), dp(4));
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                showConversationSettings();
            }
        });
        panel.addView(more, new LinearLayout.LayoutParams(MATCH, WRAP));

        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.55f;
            window.setAttributes(attributes);
            window.setLayout(MATCH, (int) (getResources().getDisplayMetrics().heightPixels * 0.72));
        }
        dialog.show();
        if (window != null) {
            window.setLayout(MATCH, (int) (getResources().getDisplayMetrics().heightPixels * 0.72));
        }
    }

    private void addModelRow(final LinearLayout list, final String name, String subtitle,
                             boolean selected, boolean custom, final Dialog dialog,
                             final String fixedModel) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(11), dp(4), dp(11));
        row.setClickable(true);
        row.setFocusable(true);

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(rounded(ICON_BG, 10));
        ImageView icon = vectorIcon(custom ? "ic_file" : "ic_sparkle", MUTED);
        iconBox.addView(icon, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        row.addView(iconBox, iconSize(dp(34), dp(11)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        row.addView(info, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextColor(TEXT);
        nameView.setTextSize(13.5f);
        info.addView(nameView, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView subView = new TextView(this);
        subView.setText(subtitle);
        subView.setTextColor(DIM);
        subView.setTextSize(9.5f);
        subView.setPadding(0, dp(2), 0, 0);
        info.addView(subView, new LinearLayout.LayoutParams(MATCH, WRAP));

        if (selected) {
            ImageView check = vectorIcon("ic_check", MUTED);
            row.addView(check, iconSize(dp(18), dp(4)));
        } else {
            row.addView(new View(this), iconSize(dp(18), dp(4)));
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (custom) {
                    showCustomModelDialog();
                } else if (fixedModel != null) {
                    applyModel(fixedModel);
                }
            }
        });
        list.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void addCatalogModelRow(final LinearLayout list, final ModelService service,
                                    final String model, final Conversation conversation,
                                    final Dialog dialog) {
        ModelProfile profile = prefs.modelProfile(service.baseUrl, model);
        boolean selected = Conversation.SOURCE_CLOUD.equals(conversation.modelSource)
                && model.equals(conversation.model)
                && service.id.equals(conversationServiceId(conversation));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(11), dp(4), dp(11));
        row.setClickable(true);
        row.setFocusable(true);

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(rounded(ICON_BG, 10));
        ImageView icon = vectorIcon("ic_sparkle", MUTED);
        iconBox.addView(icon, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        row.addView(iconBox, iconSize(dp(34), dp(11)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        row.addView(info, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView nameView = new TextView(this);
        nameView.setText(model);
        nameView.setTextColor(TEXT);
        nameView.setTextSize(13f);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(nameView, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView subView = new TextView(this);
        subView.setText(profile.summary() + " · " + service.displayName());
        subView.setTextColor(DIM);
        subView.setTextSize(9.5f);
        subView.setPadding(0, dp(2), 0, 0);
        info.addView(subView, new LinearLayout.LayoutParams(MATCH, WRAP));

        if (selected) {
            row.addView(vectorIcon("ic_check", MUTED), iconSize(dp(18), dp(4)));
        } else {
            row.addView(new View(this), iconSize(dp(18), dp(4)));
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                applyServiceModel(service, model);
                Toast.makeText(MainActivity.this,
                        "Switched to " + model + " · " + service.displayName(),
                        Toast.LENGTH_SHORT).show();
            }
        });
        list.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void addActionRow(final LinearLayout list, String title, String subtitle,
                              final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(12), dp(6), dp(12));
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        row.addView(info, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView nameView = new TextView(this);
        nameView.setText(title);
        nameView.setTextColor(SUCCESS);
        nameView.setTextSize(12.5f);
        info.addView(nameView, new LinearLayout.LayoutParams(MATCH, WRAP));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subView = new TextView(this);
            subView.setText(subtitle);
            subView.setTextColor(DIM);
            subView.setTextSize(9.5f);
            subView.setPadding(0, dp(2), 0, 0);
            info.addView(subView, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        row.addView(vectorIcon("ic_chevron_right", DIM), iconSize(dp(16), dp(2)));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        list.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void refreshCatalog(final ModelService service, final Runnable onDone) {
        final String key = service.apiKey == null || service.apiKey.trim().isEmpty()
                ? prefs.apiKey() : service.apiKey.trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "Please configure in Model Service API Key",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Fetching " + service.displayName() + " . The list of models...",
                Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ModelCatalog.FetchResult result =
                        ModelCatalog.fetch(service.baseUrl, key);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (!result.ok()) {
                            Toast.makeText(MainActivity.this, result.error,
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        prefs.saveCatalog(service.baseUrl, result.models);
                        Toast.makeText(MainActivity.this,
                                "Retrieved " + result.models.size() + " Model",
                                Toast.LENGTH_SHORT).show();
                        if (onDone != null) {
                            onDone.run();
                        }
                    }
                });
            }
        }).start();
    }

    private String modelLabel(Conversation conversation) {
        if (conversation != null
                && Conversation.SOURCE_LOCAL.equals(conversation.modelSource)) {
            File file = LocalAi.get(this).selectedFile();
            return (file == null ? "Local Model" : file.getName()) + " · Local";
        }
        return conversation == null ? "-" : conversation.model;
    }

    private void addLocalModelRow(final LinearLayout list, final File file,
                                  final LocalAi localAi, File selected,
                                  final Dialog dialog, boolean active) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(11), dp(4), dp(11));
        row.setClickable(true);
        row.setFocusable(true);
        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(rounded(ICON_BG, 10));
        ImageView icon = vectorIcon("ic_terminal", MUTED);
        iconBox.addView(icon, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        row.addView(iconBox, iconSize(dp(34), dp(11)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        row.addView(info, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView nameView = new TextView(this);
        nameView.setText(file.getName());
        nameView.setTextColor(TEXT);
        nameView.setTextSize(13f);
        nameView.setMaxLines(1);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(nameView, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView subView = new TextView(this);
        subView.setText(LocalAi.formatSize(file.length())
                + (file.length() > 700L * 1024L * 1024L
                ? " · It's better quality, slower." : " · Faster.")
                + " · Offline dialogue pure, no tools");
        subView.setTextColor(DIM);
        subView.setTextSize(9.5f);
        info.addView(subView, new LinearLayout.LayoutParams(MATCH, WRAP));
        if (active) {
            row.addView(vectorIcon("ic_check", MUTED), iconSize(dp(18), dp(4)));
        } else {
            row.addView(new View(this), iconSize(dp(18), dp(4)));
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                localAi.selectModel(file);
                engine.setModelSource(Conversation.SOURCE_LOCAL);
                dialog.dismiss();
                renderAll();
                updateStatus();
                preloadLocalModel();
                Toast.makeText(MainActivity.this,
                        "Current dialogue uses local models:" + file.getName(),
                        Toast.LENGTH_SHORT).show();
            }
        });
        list.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void showCustomModelDialog() {
        final Conversation conversation = engine.currentConversation();
        if (conversation == null) {
            return;
        }
        AgentKit.input(this, "Custom Model", "For example: example-model", conversation.model, "Use",
                new AgentKit.InputHandler() {
                    @Override
                    public boolean onInput(String value, Dialog dialog) {
                        if (value.isEmpty()) {
                            Toast.makeText(MainActivity.this, "Please enter the model name",
                                    Toast.LENGTH_SHORT).show();
                            return false;
                        }
                        applyModel(value);
                        return true;
                    }
                });
    }

    private void applyModel(String model) {
        Conversation conversation = engine.currentConversation();
        if (conversation == null || model == null || model.trim().isEmpty()) {
            return;
        }
        String id = model.trim();
        ModelService service = prefs.service(conversationServiceId(conversation));
        if (service == null) {
            service = prefs.service(prefs.activeServiceId());
        }
        if (service == null) {
            Toast.makeText(this, "Please add a service to the Model Service.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        applyServiceModel(service, id);
        Toast.makeText(this, "Model has been converted to " + id, Toast.LENGTH_SHORT).show();
    }

    private GradientDrawable roundedTop(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadii(new float[]{
                dp(radiusDp), dp(radiusDp), dp(radiusDp), dp(radiusDp), 0, 0, 0, 0
        });
        return drawable;
    }

    private void showAgentMenu(View anchor, String title, String[] labels, String[] icons,
                               int[] colors, final Runnable[] actions) {
        showAgentMenu(anchor, title, labels, icons, colors, actions, false);
    }

    private void showAgentMenu(View anchor, String title, String[] labels, String[] icons,
                               int[] colors, final Runnable[] actions, boolean bottom) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackground(rounded(CARD, 16));
        content.setPadding(dp(6), dp(6), dp(6), dp(6));

        if (title != null && !title.isEmpty()) {
            TextView header = new TextView(this);
            header.setText(title);
            header.setTextColor(DIM);
            header.setTextSize(10f);
            header.setSingleLine(true);
            header.setEllipsize(TextUtils.TruncateAt.END);
            header.setPadding(dp(10), dp(6), dp(10), dp(5));
            content.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        final PopupWindow popup = new PopupWindow(content, dp(214), WRAP, true);
        for (int i = 0; i < labels.length; i++) {
            final Runnable action = actions[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(9), dp(10), dp(9));
            row.setClickable(true);
            row.setFocusable(true);
            ImageView icon = vectorIcon(icons[i], colors[i]);
            row.addView(icon, iconSize(dp(19), dp(10)));
            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextColor(colors[i]);
            label.setTextSize(13f);
            row.addView(label, new LinearLayout.LayoutParams(0, WRAP, 1f));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    popup.dismiss();
                    action.run();
                }
            });
            content.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(18));
        if (bottom) {
            popup.showAtLocation(anchor, Gravity.BOTTOM | Gravity.START, dp(10), dp(78));
        } else {
            popup.showAsDropDown(anchor, Math.min(0, anchor.getWidth() - dp(214)), dp(4));
        }
    }


    private ImageView vectorIcon(String name, int color) {
        ImageView view = new ImageView(this);
        int id = getResources().getIdentifier(name, "drawable", getPackageName());
        if (id != 0) {
            view.setImageResource(id);
        }
        view.setColorFilter(color);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        return view;
    }

    private ImageView vectorButton(String name, int color, int sizeDp) {
        ImageView view = vectorIcon(name, color);
        view.setPadding(dp(8), dp(8), dp(8), dp(8));
        view.setBackground(rounded(ICON_BG, sizeDp / 2f));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private LinearLayout.LayoutParams iconSize(int size, int rightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.setMargins(0, 0, rightMargin, 0);
        return params;
    }

    private String currentWorkspacePathLabel() {
        String path = engine.currentWorkspacePath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    private String workspaceLabel(String path) {
        String value = path == null ? "" : path.trim();
        return value.isEmpty() || "/".equals(value) ? "Root Directory" : value;
    }

    private void scheduleRender() {
        if (renderPending) {
            return;
        }
        renderPending = true;
        handler.postDelayed(renderRunnable, 300L);
    }

    private void renderAll() {
        if (feedList == null) {
            return;
        }
        renderPending = false;
        handler.removeCallbacks(renderRunnable);
        workspace = engine.workspace();
        boolean viewingOther = !viewingRunningTask && viewedConversation != null;
        String feedKey = viewingOther
                ? (viewedConversation == null ? "" : viewedConversation.id)
                : (engine.currentConversation() == null
                ? "" : engine.currentConversation().id);
        boolean switchedConversation = !feedKey.equals(lastFeedKey);
        if (switchedConversation) {
            lastFeedKey = feedKey;
            feedAtBottom = true;
        }
        final int savedScroll = feedScroll.getScrollY();
        final boolean keepScrollPosition = !feedAtBottom || holdScroll;
        holdScroll = false;
        feedList.beginRender(!switchedConversation);
        if (viewingOther) {
            addViewedConversationBanner();
        }
        JSONArray plan = viewingOther ? viewedConversation.plan : engine.getPlan();
        List<AgentEngine.Entry> entries = viewingOther
                ? entriesFrom(viewedConversation.entries) : engine.getEntries();
        if (entries.size() > visibleEntryLimit) {
            int hidden = entries.size() - visibleEntryLimit;
            addLoadEarlierRow(hidden);
            entries = new ArrayList<>(entries.subList(hidden, entries.size()));
        }
        if (entries.isEmpty()) {
            addWelcomeCard();
        } else {
            renderTimeline(entries);
        }
        feedList.endRender();
        if (switchedConversation) {
            // Cold Start/When you switch sessions, the problems that have been experienced in history are shown only in cards and not automatically;
            // Only emerging issues during the lifetime of this interface will be automatically launched.
            for (AgentEngine.Entry existing : entries) {
                if (existing.type == AgentEngine.Entry.QUESTIONS) {
                    autoOpenedQuestions.add(Long.valueOf(existing.time));
                }
            }
        }
        maybeAutoOpenQuestion(entries, viewingOther);
        renderPlanBar(plan, feedKey);
        updateStatus();
        // The drawer list is only open./Refresh sessions when added or deleted; following 300ms Rendering the throttle full refreshing will kill the main thread.
        if (keepScrollPosition) {
            feedScroll.restoreScroll(savedScroll);
        } else {
            feedScroll.followBottom(true);
        }
        updateScreenshotModeBar();
    }

    private static List<AgentEngine.Entry> entriesFrom(JSONArray array) {
        List<AgentEngine.Entry> entries = new ArrayList<>();
        if (array == null) {
            return entries;
        }
        for (int i = 0; i < array.length(); i++) {
            AgentEngine.Entry entry = AgentEngine.entryFromJson(array.optJSONObject(i));
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private void addViewedConversationBanner() {
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setBackground(rounded(CARD_SOFT, 14));
        banner.setPadding(dp(14), dp(10), dp(14), dp(10));
        banner.setClickable(true);
        banner.setFocusable(true);
        TextView title = AgentKit.title(this, "Read-only historical conversations", 12.5f);
        banner.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView hint = AgentKit.hint(this,
                "The task in progress is still in the backstage and will not be suspended."
                        + "The content during the departure is shown once.");
        banner.addView(hint, new LinearLayout.LayoutParams(MATCH, WRAP));
        banner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                returnToRunningConversation();
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.setMargins(0, dp(2), 0, dp(8));
        banner.setTag("banner|" + (lastFeedKey == null ? "" : lastFeedKey));
        feedList.addView(banner, params);
    }

    private void returnToRunningConversation() {
        viewingRunningTask = true;
        viewedConversation = null;
        workspace = engine.workspace();
        visibleEntryLimit = 80;
        showChat();
        renderAll();
    }

    private void addLoadEarlierRow(int hidden) {
        TextView more = new TextView(this);
        more.setText("Check for earlier. " + hidden + " Article");
        more.setTextColor(MUTED);
        more.setTextSize(11f);
        more.setGravity(Gravity.CENTER);
        more.setPadding(0, dp(10), 0, dp(10));
        more.setClickable(true);
        more.setFocusable(true);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                visibleEntryLimit += 80;
                renderAll();
            }
        });
        more.setTag("earlier|" + hidden);
        feedList.addView(more, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void renderTimeline(List<AgentEngine.Entry> entries) {
        boolean live = engine.isRunning();
        String pendingReasoning = "";
        long pendingReasoningAt = 0L;
        String pendingAssistant = "";
        String lastThought = "";
                int i = 0;
        while (i < entries.size()) {
            if (inStage(entries, i)) {
                int from = i;
                int to = i;
                int calls = 0;
                while (to < entries.size() && inStage(entries, to)) {
                    AgentEngine.Entry step = entries.get(to);
                    if (step.type == AgentEngine.Entry.TOOL && step.text.startsWith("Call ")) {
                        calls++;
                    }
                    to++;
                }
                boolean liveStage = live && to >= entries.size();
                addStageGroup(entries, from, to, calls, liveStage);
                i = to;
                continue;
            }
            AgentEngine.Entry entry = entries.get(i);
            if (entry.type == AgentEngine.Entry.REASONING) {
                // Save it for a while. Wait for the next move./I'll decide which one to use.'Thinking', 
                // Avoids the same round showing both deep thinking and showing intent. 
                if (entry.text != null && !entry.text.trim().isEmpty()) {
                    pendingReasoning = entry.text;
                    pendingReasoningAt = entry.time;
                }
                i++;
                continue;
            }
            if (entry.type == AgentEngine.Entry.ASSISTANT && followedByCall(entries, i)) {
                // This is the model's description of this step; no hurry to show, when the next tool calls
                // With it. intent Merge into one.'Next'Decision-making.
                pendingAssistant = entry.text == null ? "" : entry.text.trim();
                i++;
                continue;
            }
            if (entry.type == AgentEngine.Entry.TOOL && entry.text.startsWith("Call ")) {
                String intent = intentOf(entry);
                String primary = !intent.isEmpty() ? intent : pendingAssistant;
                String preview = "";
                String full = "";
                if (!primary.trim().isEmpty()) {
                    preview = nextStepPreview(primary);
                    String extra = pendingAssistant.isEmpty() || pendingAssistant.equals(intent)
                            ? "" : pendingAssistant + "\n\n";
                    full = joinThought(pendingReasoning, extra + intent);
                    if (full.trim().isEmpty()) {
                        full = primary;
                    }
                } else if (!pendingReasoning.isEmpty()) {
                    preview = thoughtPreview(pendingReasoning);
                    full = pendingReasoning;
                }
                if (!preview.isEmpty() && !sameThought(preview, lastThought)) {
                    addThoughtRow(entry.time, preview, full);
                    lastThought = preview;
                }
                pendingAssistant = "";
                pendingReasoning = "";
                AgentEngine.Entry result = null;
                if (i + 1 < entries.size()
                        && entries.get(i + 1).type == AgentEngine.Entry.TOOL
                        && entries.get(i + 1).text.startsWith("Tools ")) {
                    result = entries.get(i + 1);
                }
                boolean running = result == null && live && i >= entries.size() - 1;
                addStepRow(entry, result, running);
                if (result != null) {
                    i++;
                }
                i++;
                continue;
            }
            if (entry.type == AgentEngine.Entry.TOOL && entry.text.startsWith("Tools ")) {
                i++;
                continue;
            }
            if (!pendingReasoning.isEmpty()) {
                String preview = thoughtPreview(pendingReasoning);
                if (!preview.isEmpty() && !sameThought(preview, lastThought)) {
                    addThoughtRow(pendingReasoningAt, preview, pendingReasoning);
                    lastThought = preview;
                }
                pendingReasoning = "";
            }
            pendingAssistant = "";
            renderPlainEntry(entry, false);
            if (entry.type == AgentEngine.Entry.USER || entry.type == AgentEngine.Entry.ASSISTANT) {
                lastThought = "";
            }
            i++;
        }
        if (!pendingReasoning.isEmpty()) {
            String preview = thoughtPreview(pendingReasoning);
            if (!preview.isEmpty() && !sameThought(preview, lastThought)) {
                addThoughtRow(pendingReasoningAt, preview, pendingReasoning);
            }
        }
    }

    private static String intentOf(AgentEngine.Entry entry) {
        if (entry == null || entry.text == null || !entry.text.startsWith("Call ")) {
            return "";
        }
        String body = entry.text.substring(3).trim();
        int space = body.indexOf(' ');
        if (space < 0) {
            return "";
        }
        try {
            return new JSONObject(body.substring(space + 1)).optString("intent", "").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String thoughtPreview(String text) {
        if (text == null) {
            return "";
        }
        String[] lines = text.replace("\r", "").split("\n");
        for (String line : lines) {
            String value = line.trim().replaceFirst("^[#>*\\-\\s]+", "").trim();
            if (!value.isEmpty()) {
                return value.length() > 140 ? value.substring(0, 140) + "..." : value;
            }
        }
        return "";
    }

    /** Agent It's...'Thinking'The core is the next command, not the double drive; the prefix is added when no next semantic is added. */
    private static String nextStepPreview(String text) {
        String preview = thoughtPreview(text);
        if (preview.isEmpty()) {
            return "";
        }
        String lower = preview.toLowerCase(Locale.ROOT);
        if (preview.startsWith("Next") || preview.startsWith("Next")
                || preview.startsWith("And then...") || preview.startsWith("Now.")
                || lower.startsWith("next") || lower.startsWith("then")
                || lower.startsWith("now")) {
            return preview;
        }
        return "Next steps:" + preview;
    }

    private static boolean sameThought(String left, String right) {
        String a = left == null ? "" : left.replaceAll("\\s+", " ").trim();
        String b = right == null ? "" : right.replaceAll("\\s+", " ").trim();
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        int n = Math.min(48, Math.min(a.length(), b.length()));
        return n >= 12 && a.substring(0, n).equals(b.substring(0, n));
    }

    private static String joinThought(String reasoning, String thought) {
        String a = reasoning == null ? "" : reasoning.trim();
        String b = thought == null ? "" : thought.trim();
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty() || a.contains(b)) {
            return a;
        }
        return a + "\n\n" + b;
    }

    private void addThoughtRow(final long key, String preview, final String full) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(6), dp(2), dp(6));
        row.setClickable(true);
        row.setFocusable(true);
        row.addView(vectorIcon("ic_sparkle", Color.rgb(232, 188, 92)),
                iconSize(dp(15), dp(9)));
        TextView text = new TextView(this);
        text.setText("Thinking · " + preview);
        text.setTextColor(MUTED);
        text.setTextSize(11.5f);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(text, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(vectorIcon("ic_chevron_right", DIM), iconSize(dp(14), dp(2)));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDetailSheet("Think.", full == null || full.trim().isEmpty() ? preview : full);
            }
        });
        row.setTag("th|" + key + "|" + preview);
        feedList.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void addStepRow(final AgentEngine.Entry call, final AgentEngine.Entry result,
                            boolean running) {
        String body = call.text.substring(3).trim();
        int space = body.indexOf(' ');
        String name = space < 0 ? body : body.substring(0, space);
        String argsText = space < 0 ? "" : body.substring(space + 1).trim();
        JSONObject args = null;
        try {
            args = new JSONObject(argsText);
        } catch (Exception ignored) {
        }
        String label = ToolPresentation.label(name, args);
        String summary = ToolPresentation.argumentSummary(name, args);
        String iconName = ToolPresentation.icon(name);
        boolean failed = result != null && result.text.startsWith("Tools ")
                && (result.text.contains("Failed") || result.text.contains("Error"));
        String status = running ? "..." : failed ? "×" : "✓";
        int statusColor = running ? Color.rgb(232, 188, 92)
                : failed ? ERROR : Color.rgb(120, 200, 140);
        final String detail = "Actions:" + label + (summary.isEmpty() ? "" : "  " + summary)
                + (argsText.isEmpty() || "{}".equals(argsText) ? "" : "\n\nParameters:\n" + argsText)
                + (result == null ? "" : "\n\nResults:\n" + result.text);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(7), dp(2), dp(7));
        row.setClickable(true);
        row.setFocusable(true);
        TextView state = new TextView(this);
        state.setText(status);
        state.setTextColor(statusColor);
        state.setTextSize(12f);
        state.setGravity(Gravity.CENTER);
        row.addView(state, iconSize(dp(18), dp(8)));
        row.addView(vectorIcon(iconName, failed ? ERROR : MUTED), iconSize(dp(16), dp(8)));

        SpannableStringBuilder builder = new SpannableStringBuilder();
        int start = builder.length();
        builder.append(label);
        builder.setSpan(new ForegroundColorSpan(failed ? ERROR : TEXT), start, builder.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!summary.isEmpty()) {
            builder.append("  ");
            start = builder.length();
            builder.append(summary);
            builder.setSpan(new ForegroundColorSpan(MUTED), start, builder.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView text = new TextView(this);
        text.setText(builder);
        text.setTextSize(12.5f);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(text, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(vectorIcon("ic_chevron_right", DIM), iconSize(dp(15), dp(2)));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDetailSheet("Details of the steps", detail);
            }
        });
        row.setTag("step|" + call.time + "|" + call.text);
        feedList.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void showDetailSheet(String title, String text) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        try {
            ScrollView scroll = new ScrollView(this);
            TextView body = new TextView(this);
            body.setText(text);
            body.setTextColor(MUTED);
            body.setTextSize(12f);
            body.setLineSpacing(dp(3), 1f);
            body.setTextIsSelectable(true);
            body.setPadding(dp(16), dp(4), dp(16), dp(10));
            scroll.addView(body, new ScrollView.LayoutParams(
                    android.widget.ScrollView.LayoutParams.MATCH_PARENT,
                    android.widget.ScrollView.LayoutParams.WRAP_CONTENT));
            LinearLayout content = AgentKit.column(this);
            content.addView(scroll, new LinearLayout.LayoutParams(MATCH, dp(420)));
            AgentKit.sheet(this, title, content);
        } catch (WindowManager.BadTokenException e) {
            Log.w("AgentUI", "detail sheet skipped: bad token", e);
        } catch (RuntimeException e) {
            Log.w("AgentUI", "detail sheet skipped", e);
        }
    }

    /** belong to"Process"Line: the intent sentence for the tool to call and its outcome line, model thinking, and the subsequent call. */
    private static boolean inStage(List<AgentEngine.Entry> entries, int index) {
        AgentEngine.Entry entry = entries.get(index);
        if (entry.type == AgentEngine.Entry.REASONING) {
            return true;
        }
        if (entry.type == AgentEngine.Entry.TOOL) {
            return entry.text.startsWith("Call ") || entry.text.startsWith("Tools ");
        }
        if (entry.type == AgentEngine.Entry.ASSISTANT) {
            return followedByCall(entries, index);
        }
        return false;
    }

    private static boolean followedByCall(List<AgentEngine.Entry> entries, int index) {
        for (int j = index + 1; j < entries.size(); j++) {
            AgentEngine.Entry next = entries.get(j);
            if (next.type == AgentEngine.Entry.TOOL && next.text.startsWith("Call ")) {
                return true;
            }
            return false;
        }
        return false;
    }

    private void renderPlainEntry(AgentEngine.Entry entry, boolean lastReasoning) {
        switch (entry.type) {
            case AgentEngine.Entry.USER:
                addUserRow(entry);
                break;
            case AgentEngine.Entry.ASSISTANT:
                addAssistantRow(entry);
                break;
            case AgentEngine.Entry.REASONING:
                addReasoningRow(entry, lastReasoning);
                break;
            case AgentEngine.Entry.ERROR:
                addErrorRow(entry);
                break;
            case AgentEngine.Entry.QUESTIONS:
                addQuestionsRow(entry);
                break;
            case AgentEngine.Entry.IMAGE:
                addScreenshotRow(entry);
                break;
            default:
                addStatusRow(entry);
                break;
        }
    }

    /**
     * One stage: a header counting the tool calls, with the process rows underneath.
     * The live stage stays open; finished stages collapse so the stage result reads as
     * the headline, the way a task log should.
     */
    private void addStageGroup(List<AgentEngine.Entry> entries, int from, int to,
                               int calls, boolean live) {
        final long key = entries.get(from).time;
        final Long boxKey = Long.valueOf(key);
        final boolean expanded = stageOpen.contains(boxKey)
                || (!stageClosed.contains(boxKey) && live);

                String lastAction = "";
        for (int i = to - 1; i >= from; i--) {
            AgentEngine.Entry step = entries.get(i);
            if (step.type != AgentEngine.Entry.TOOL || !step.text.startsWith("Call ")) {
                continue;
            }
            String body = step.text.substring(3).trim();
            int space = body.indexOf(' ');
            String name = space < 0 ? body : body.substring(0, space);
            JSONObject stepArgs = null;
            try {
                stepArgs = new JSONObject(space < 0 ? "" : body.substring(space + 1).trim());
            } catch (Exception ignored) {
            }
            lastAction = stepArgs == null ? ToolPresentation.label(name)
                    : ToolPresentation.label(name, stepArgs);
            break;
        }

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(rounded(CARD_SOFT, 11));
        header.setPadding(dp(9), dp(7), dp(8), dp(7));
        header.setClickable(true);
        header.setFocusable(true);
        header.addView(vectorIcon(calls > 0 ? "ic_terminal" : "ic_sparkle", MUTED),
                iconSize(dp(16), dp(9)));
        TextView label = new TextView(this);
        label.setText(expanded
                ? "Implementation process · " + (calls > 0 ? calls + " Step Tool Call" : "Think.") + " · Put it away."
                : "Implementation process · " + (calls > 0 ? calls + " Step" : "Think.")
                + (live ? " · Running" : "")
                + (lastAction.isEmpty() ? "" : " · " + lastAction));
        label.setTextColor(MUTED);
        label.setTextSize(12f);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setTextColor(MUTED);
        label.setTextSize(12f);
        header.addView(label, new LinearLayout.LayoutParams(0, WRAP, 1f));
        header.addView(vectorIcon(expanded ? "ic_chevron_down" : "ic_chevron_right", DIM),
                iconSize(dp(16), dp(2)));
        header.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (expanded) {
                    stageOpen.remove(boxKey);
                    stageClosed.add(boxKey);
                } else {
                    stageClosed.remove(boxKey);
                    stageOpen.add(boxKey);
                }
                renderAll();
            }
        });
        header.setTag("g|" + key + "|" + expanded + "|" + calls);
        feedList.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
        if (!expanded) {
            return;
        }
        for (int i = from; i < to; i++) {
            AgentEngine.Entry entry = entries.get(i);
            if (entry.type == AgentEngine.Entry.TOOL) {
                if (!entry.text.startsWith("Call ")) {
                    continue;
                }
                if (i + 1 < to && entries.get(i + 1).type == AgentEngine.Entry.TOOL
                        && entries.get(i + 1).text.startsWith("Tools ")) {
                    i++;
                }
                addToolRow(entry);
            } else if (entry.type == AgentEngine.Entry.REASONING) {
                addReasoningRow(entry, false);
            } else {
                                // Process area is done with a small gray-word intent to avoid mixing with the text bubble of the final response
                addIntentRow(entry.time, entry.text);
            }
        }
    }

    private void renderPlanBar(JSONArray plan, String feedKey) {
        if (planBarHost == null) {
            return;
        }
        if (plan == null || plan.length() == 0) {
            planBarHost.removeAllViews();
            planBarHost.setVisibility(View.GONE);
            return;
        }
        planBarHost.setVisibility(View.VISIBLE);
        addPlanCard(plan, feedKey);
    }

    private void addPlanCard(JSONArray plan, String feedKey) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, 16));
        card.setPadding(dp(14), dp(9), dp(14), dp(9));

        int completed = 0;
        String activeStep = "";
        for (int i = 0; i < plan.length(); i++) {
            JSONObject step = plan.optJSONObject(i);
            if (step == null) {
                continue;
            }
            String status0 = step.optString("status", "");
            if ("completed".equals(status0)) {
                completed++;
            } else if (activeStep.length() == 0 && "in_progress".equals(status0)) {
                activeStep = step.optString("title", "").trim();
            }
        }
        final String planKey = feedKey == null ? "" : feedKey;
        final boolean planOpen = planExpanded.contains(planKey);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
        header.setClickable(true);
        header.setFocusable(true);
        header.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (planExpanded.contains(planKey)) {
                    planExpanded.remove(planKey);
                } else {
                    planExpanded.add(planKey);
                }
                renderAll();
            }
        });
        TextView title = new TextView(this);
        title.setText(activeStep.length() == 0
                ? (completed >= plan.length() ? "Implementation plan completed" : "Implementation plan")
                : "Implementation plan · " + activeStep);
        title.setTextColor(TEXT);
        title.setTextSize(12f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView progress = new TextView(this);
        progress.setText(completed + "/" + plan.length());
        progress.setTextColor(MUTED);
        progress.setTextSize(10f);
        progress.setBackground(rounded(ICON_BG, 8));
        progress.setPadding(dp(7), dp(2), dp(7), dp(2));
        header.addView(progress, new LinearLayout.LayoutParams(WRAP, WRAP));
        ImageView chevron = vectorIcon(
                planOpen ? "ic_chevron_down" : "ic_chevron_right", DIM);
        LinearLayout.LayoutParams chevronParams =
                new LinearLayout.LayoutParams(dp(14), dp(14));
        chevronParams.setMargins(dp(6), 0, 0, 0);
        header.addView(chevron, chevronParams);

        if (planOpen) for (int i = 0; i < plan.length(); i++) {
            JSONObject step = plan.optJSONObject(i);
            if (step == null) {
                continue;
            }
            String status = step.optString("status", "pending");
            String stepTitle = step.optString("title", "");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(1));
            ImageView icon = vectorIcon(
                    "completed".equals(status) ? "ic_check"
                            : "in_progress".equals(status) ? "ic_sparkle" : "ic_chevron_right",
                    "completed".equals(status) ? SUCCESS
                            : "in_progress".equals(status) ? ACCENT : DIM);
            row.addView(icon, iconSize(dp(15), dp(8)));
            TextView label = new TextView(this);
            label.setText(stepTitle);
            label.setTextColor("pending".equals(status) ? MUTED : TEXT);
            label.setTextSize(11.5f);
            if ("completed".equals(status)) {
                label.setPaintFlags(label.getPaintFlags()
                        | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            }
            row.addView(label, new LinearLayout.LayoutParams(0, WRAP, 1f));
            card.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.setMargins(0, dp(6), 0, dp(4));
        card.setTag("plan|" + planKey + "|" + planOpen + "|" + plan.toString());
        planBarHost.removeAllViews();
        planBarHost.addView(card, params);
        planBarHost.setVisibility(View.VISIBLE);
    }

    private String modeLabel(String mode) {
        if (Conversation.APPROVAL_ASK.equals(mode)) {
            return "Ask for approval";
        }
        if (Conversation.APPROVAL_READONLY.equals(mode)) {
            return "Read-only Mode";
        }
        return "Automatic approval";
    }

    private void showModeSheet() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(roundedTop(CARD, 22));
        panel.setPadding(0, dp(8), 0, dp(18));

        View handle = new View(this);
        handle.setBackground(rounded(Color.rgb(80, 80, 80), 2));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(38), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(handle, handleParams);

        FrameLayout header = new FrameLayout(this);
        panel.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView title = new TextView(this);
        title.setText("Selection Mode");
        title.setTextColor(TEXT);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new FrameLayout.LayoutParams(MATCH, dp(52)));
        ImageView close = vectorButton("ic_close", MUTED, 30);
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(30), dp(30),
                Gravity.END | Gravity.CENTER_VERTICAL);
        closeParams.setMargins(0, 0, dp(14), 0);
        header.addView(close, closeParams);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        String current = engine.approvalMode();
        addModeRow(panel, dialog, Conversation.APPROVAL_AUTO, "Automatic approval",
                "Tools are self-executing; equipment control switches are still in force.", "ic_sparkle",
                current.equals(Conversation.APPROVAL_AUTO));
        addModeRow(panel, dialog, Conversation.APPROVAL_ASK, "Ask for approval",
                "When writing a file, executing an order, constructing an installation or operating interface, first App Internal confirmation.", "ic_alert",
                current.equals(Conversation.APPROVAL_ASK));
        addModeRow(panel, dialog, Conversation.APPROVAL_READONLY, "Read-only Mode",
                "Read only workspaces and models; do not write files, do not execute commands, do not operate interfaces, and do not read screens or clipboards.", "ic_lock",
                current.equals(Conversation.APPROVAL_READONLY));
        TextView modeHint = AgentKit.hint(this,
                "Asking for permission to choose 'Always Allow' Session Settings → Tool Authorization Clear.");
        modeHint.setPadding(dp(18), dp(10), dp(18), 0);
        panel.addView(modeHint, new LinearLayout.LayoutParams(MATCH, WRAP));

        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.55f;
            window.setAttributes(attributes);
        }
        dialog.show();
        if (window != null) {
            window.setLayout(MATCH, WRAP);
        }
    }

    private void addModeRow(LinearLayout panel, final Dialog dialog, final String mode,
                            String title, String description, String iconName, boolean selected) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(12), dp(16), dp(12));
        row.setClickable(true);
        row.setFocusable(true);

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackground(rounded(ICON_BG, 10));
        ImageView icon = vectorIcon(iconName,
                Conversation.APPROVAL_ASK.equals(mode) ? Color.rgb(232, 188, 92)
                        : Conversation.APPROVAL_READONLY.equals(mode)
                        ? Color.rgb(255, 148, 98) : MUTED);
        iconBox.addView(icon, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        row.addView(iconBox, iconSize(dp(36), dp(12)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        row.addView(info, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(TEXT);
        titleView.setTextSize(13.5f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        info.addView(titleView, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView desc = new TextView(this);
        desc.setText(description);
        desc.setTextColor(DIM);
        desc.setTextSize(9.5f);
        desc.setPadding(0, dp(2), 0, 0);
        info.addView(desc, new LinearLayout.LayoutParams(MATCH, WRAP));

        if (selected) {
            ImageView check = vectorIcon("ic_check", TEXT);
            row.addView(check, iconSize(dp(20), dp(2)));
        } else {
            View circle = new View(this);
            circle.setBackground(rounded(Color.TRANSPARENT, 9));
            circle.setBackground(rounded(ICON_BG, 9));
            row.addView(circle, iconSize(dp(18), dp(2)));
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                engine.setApprovalMode(mode);
                if (settingsModeValue != null) {
                    settingsModeValue.setText(modeLabel(mode));
                }
                updateStatus();
                Toast.makeText(MainActivity.this, "Run mode:" + modeLabel(mode),
                        Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            }
        });
        panel.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    /** App While in the back, equipment-type tools follow the 'equipment control' master switch authorization to avoid the task being stopped because of the inability to fire the authorization panel. */
    private boolean allowDeviceToolInBackground(String tool) {
        if (prefs == null || tool == null || !prefs.allowDeviceControl()) return false;
        return (ToolPolicy.classify(tool, false) & ToolPolicy.DEVICE) != 0;
    }

    private void notifyBackgroundApproval(final String tool) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(getApplicationContext(),
                        "The following is a list of the most recent events in the world:" + tool, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void installApprovalHandler() {
        engine.setApprovalHandler(new AgentEngine.ApprovalHandler() {
            @Override
                        public int requestApproval(final String tool, final String arguments) {
                if (isFinishing() || isDestroyed()) {
                    return AgentEngine.APPROVAL_DENY;
                }
                // App - I'm sorry. sheet I can't even get it out or light it. I need to get back here immediately.
                // The main loop must not be blocked until a hit that can never wait."Stop at target interface."). 
                if (!activityResumed) {
                    // Cut to target. App The post-authorized panel is neither visible nor visible. Device-type tools press the 'Eccter Control' master switch
                    // Direct release to ensure that the main cycle continues; other restricted operations skip and prompt back App. 
                    if (allowDeviceToolInBackground(tool)) {
                        notifyBackgroundApproval(tool);
                        return AgentEngine.APPROVAL_ONCE;
                    }
                    Toast.makeText(getApplicationContext(),
                            "Agent 'and need your permission.'" + tool + "', Skipped this operation; back App We can confirm it later.",
                            Toast.LENGTH_LONG).show();
                    return AgentEngine.APPROVAL_DENY;
                }
                final CountDownLatch latch = new CountDownLatch(1);
                final int[] result = {AgentEngine.APPROVAL_DENY};
                final boolean[] done = {false};
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                                                if (isFinishing() || isDestroyed()) {
                            if (!done[0]) {
                                done[0] = true;
                                latch.countDown();
                            }
                            return;
                        }
                        // The competition is under the bridge: the ruling is still in the front desk, and the decision is not to be taken.post It's probably been cut elsewhere. App. 
                        // The bullet window is invisible at this time, and the thread must be released immediately (default) DENY)And notify the user.
                        if (!activityResumed) {
                            boolean bgAllowed = allowDeviceToolInBackground(tool);
                            if (!done[0]) {
                                done[0] = true;
                                if (bgAllowed) result[0] = AgentEngine.APPROVAL_ONCE;
                                latch.countDown();
                            }
                            if (bgAllowed) {
                                notifyBackgroundApproval(tool);
                            } else {
                                Toast.makeText(getApplicationContext(),
                                        "Agent 'need authorization.'" + tool + "', Skipped this operation (%1)App Not at the reception desk)",
                                        Toast.LENGTH_LONG).show();
                            }
                            return;
                        }
                        hideKeyboard();
                        String detail = arguments == null ? "{}" : arguments;
                        try {
                            detail = new JSONObject(detail).toString(2);
                        } catch (Exception ignored) {
                        }
                        if (detail.length() > 900) {
                            detail = detail.substring(0, 900) + "\n...";
                        }

                        LinearLayout content = AgentKit.column(MainActivity.this);
                        content.setPadding(dp(18), 0, dp(18), 0);

                        LinearLayout toolRow = AgentKit.row(MainActivity.this);
                        ImageView icon = vectorIcon(ToolPresentation.icon(tool), MUTED);
                        toolRow.addView(icon, iconSize(dp(20), dp(10)));
                        LinearLayout toolInfo = AgentKit.column(MainActivity.this);
                        TextView toolName = AgentKit.title(MainActivity.this, tool, 14f);
                        toolInfo.addView(toolName, new LinearLayout.LayoutParams(MATCH, WRAP));
                        TextView toolDesc = AgentKit.hint(MainActivity.this,
                                ToolPresentation.label(tool));
                        toolInfo.addView(toolDesc, new LinearLayout.LayoutParams(MATCH, WRAP));
                        toolRow.addView(toolInfo, new LinearLayout.LayoutParams(0, WRAP, 1f));
                        content.addView(toolRow, new LinearLayout.LayoutParams(MATCH, WRAP));

                        TextView args = new TextView(MainActivity.this);
                        args.setText(detail);
                        args.setTextColor(MUTED);
                        args.setTextSize(10.5f);
                        args.setTypeface(Typeface.MONOSPACE);
                        args.setLineSpacing(dp(2), 1f);
                        args.setPadding(dp(12), dp(10), dp(12), dp(10));
                        args.setBackground(rounded(CARD_TOOL, 12));
                        args.setMaxLines(10);
                        args.setEllipsize(TextUtils.TruncateAt.END);
                        args.setTextIsSelectable(true);
                        LinearLayout.LayoutParams argsParams =
                                new LinearLayout.LayoutParams(MATCH, WRAP);
                        argsParams.setMargins(0, dp(12), 0, 0);
                        content.addView(args, argsParams);

                        TextView hint = AgentKit.hint(MainActivity.this,
                                "This operation is confirmed by you. Always allow only for the current dialogue, which can be cleared in settings.");
                        LinearLayout.LayoutParams hintParams =
                                new LinearLayout.LayoutParams(MATCH, WRAP);
                        hintParams.setMargins(0, dp(8), 0, 0);
                        content.addView(hint, hintParams);

                        LinearLayout actions = AgentKit.row(MainActivity.this);
                        actions.setGravity(Gravity.END);
                        actions.setPadding(0, dp(16), 0, 0);
                        final Dialog[] holder = new Dialog[1];
                        final Runnable finish = new Runnable() {
                            @Override
                            public void run() {
                                if (!done[0]) {
                                    done[0] = true;
                                    latch.countDown();
                                }
                            }
                        };
                        Button deny = AgentKit.danger(MainActivity.this, "Reject");
                        deny.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                result[0] = AgentEngine.APPROVAL_DENY;
                                if (holder[0] != null) {
                                    holder[0].dismiss();
                                }
                                finish.run();
                            }
                        });
                        actions.addView(deny, new LinearLayout.LayoutParams(WRAP, WRAP));
                        Button once = AgentKit.secondary(MainActivity.this, "Allowed once.");
                        LinearLayout.LayoutParams onceParams =
                                new LinearLayout.LayoutParams(WRAP, WRAP);
                        onceParams.setMargins(dp(8), 0, 0, 0);
                        actions.addView(once, onceParams);
                        once.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                result[0] = AgentEngine.APPROVAL_ONCE;
                                if (holder[0] != null) {
                                    holder[0].dismiss();
                                }
                                finish.run();
                            }
                        });
                        Button always = AgentKit.primary(MainActivity.this, "Always allowed");
                        LinearLayout.LayoutParams alwaysParams =
                                new LinearLayout.LayoutParams(WRAP, WRAP);
                        alwaysParams.setMargins(dp(8), 0, 0, 0);
                        actions.addView(always, alwaysParams);
                        always.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                result[0] = AgentEngine.APPROVAL_ALWAYS;
                                if (holder[0] != null) {
                                    holder[0].dismiss();
                                }
                                finish.run();
                            }
                        });
                        content.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));

                        Dialog dialog = AgentKit.sheet(MainActivity.this, "I need your authorization.", content);
                        holder[0] = dialog;
                        dialog.setCancelable(false);
                        dialog.setCanceledOnTouchOutside(false);
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!done[0]) {
                                            result[0] = AgentEngine.APPROVAL_DENY;
                                            if (holder[0] != null) {
                                                holder[0].dismiss();
                                            }
                                            finish.run();
                                        }
                                    }
                                }, 120000);
                    }
                });
                try {
                    if (!latch.await(125, TimeUnit.SECONDS)) {
                        return AgentEngine.APPROVAL_DENY;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return AgentEngine.APPROVAL_DENY;
                }
                return result[0];
            }
        });
    }

    private void addWelcomeCard() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER_HORIZONTAL);
        block.setPadding(dp(24), dp(100), dp(24), dp(24));

        ImageView icon = vectorIcon("ic_sparkle", MUTED);
        block.addView(icon, iconSize(dp(28), 0));

        TextView title = new TextView(this);
        title.setText("Start a new mission");
        title.setTextColor(TEXT);
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(14), 0, 0);
        block.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView body = new TextView(this);
        body.setText("The blogger says:Agent The project will be implemented in the current working area.\nAnd here you show every step.");
        body.setTextColor(MUTED);
        body.setTextSize(12.5f);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(dp(4), 1f);
        body.setPadding(0, dp(10), 0, 0);
        block.addView(body, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView meta = new TextView(this);
        meta.setText(workspaceLabel(engine.currentWorkspacePath())
                + "  ·  " + (engine.currentConversation() == null
                ? "-" : engine.currentConversation().model));
        meta.setTextColor(DIM);
        meta.setTextSize(10.5f);
        meta.setGravity(Gravity.CENTER);
        meta.setPadding(0, dp(16), 0, 0);
        block.addView(meta, new LinearLayout.LayoutParams(MATCH, WRAP));

        block.setTag("welcome|" + workspaceLabel(engine.currentWorkspacePath())
                + "|" + (engine.currentConversation() == null
                ? "-" : engine.currentConversation().model));
        feedList.addView(block, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void addUserRow(AgentEngine.Entry entry) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD_USER, 18));
        card.setPadding(dp(14), dp(10), dp(14), dp(12));

        TextView label = new TextView(this);
        label.setText("You.");
        label.setTextColor(MUTED);
        label.setTextSize(9.5f);
        card.addView(label, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView body = new TextView(this);
        body.setText(entry.text);
        body.setTextColor(TEXT);
        body.setTextSize(14f);
        body.setLineSpacing(dp(3), 1f);
        body.setTextIsSelectable(true);
        body.setPadding(0, dp(5), 0, 0);
        card.addView(body, new LinearLayout.LayoutParams(MATCH, WRAP));

        if (entry.images != null && !entry.images.isEmpty()) {
            LinearLayout strip = new LinearLayout(this);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setPadding(0, dp(8), 0, 0);
            for (int i = 0; i < entry.images.size(); i++) {
                final String path = entry.images.get(i);
                ImageView thumb = new ImageView(this);
                Bitmap preview = ImageUtils.loadThumbnail(path, 160);
                if (preview != null) {
                    thumb.setImageBitmap(preview);
                }
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackground(rounded(CARD_SOFT, 10));
                thumb.setClipToOutline(true);
                thumb.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showAttachmentPreview(path);
                    }
                });
                LinearLayout.LayoutParams thumbParams =
                        new LinearLayout.LayoutParams(dp(72), dp(72));
                thumbParams.rightMargin = dp(8);
                strip.addView(thumb, thumbParams);
            }
            card.addView(strip, new LinearLayout.LayoutParams(WRAP, WRAP));
        }

        if (entry.files != null && !entry.files.isEmpty()) {
            LinearLayout fileRow = new LinearLayout(this);
            fileRow.setOrientation(LinearLayout.VERTICAL);
            fileRow.setPadding(0, dp(8), 0, 0);
            for (int i = 0; i < entry.files.size(); i++) {
                String path = entry.files.get(i);
                TextView chip = new TextView(this);
                chip.setText("Documentation · " + displayUploadName(path));
                chip.setTextColor(MUTED);
                chip.setTextSize(11.5f);
                chip.setSingleLine(true);
                chip.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                chip.setTextIsSelectable(true);
                chip.setBackground(rounded(CARD_SOFT, 9));
                chip.setPadding(dp(9), dp(5), dp(9), dp(5));
                LinearLayout.LayoutParams chipParams =
                        new LinearLayout.LayoutParams(WRAP, WRAP);
                chipParams.topMargin = dp(5);
                fileRow.addView(chip, chipParams);
            }
            card.addView(fileRow, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.setMargins(0, dp(4), 0, dp(10));
        card.setTag("u|" + entry.time + "|" + entry.text + "|"
                + (entry.images == null ? 0 : entry.images.size()) + "|"
                + (entry.files == null ? 0 : entry.files.size()));
        feedList.addView(card, params);
    }

    private void addScreenshotRow(AgentEngine.Entry entry) {
        if (entry.images == null || entry.images.isEmpty()) {
            return;
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, 15));
        card.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView label = new TextView(this);
        label.setText("Picture · Open Preview by Clicking on a Photo Name");
        label.setTextColor(MUTED);
        label.setTextSize(10f);
        label.setPadding(0, 0, 0, dp(6));
        card.addView(label, new LinearLayout.LayoutParams(MATCH, WRAP));

        for (int i = 0; i < entry.images.size(); i++) {
            final String path = entry.images.get(i);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(0, dp(3), 0, dp(3));
            item.setClickable(true);
            item.setFocusable(true);

            ImageView thumb = new ImageView(this);
            Bitmap preview = ImageUtils.loadThumbnail(path, 180);
            if (preview != null) {
                thumb.setImageBitmap(preview);
            }
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(rounded(CARD_SOFT, 9));
            thumb.setClipToOutline(true);
            LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(dp(58), dp(58));
            thumbParams.rightMargin = dp(10);
            item.addView(thumb, thumbParams);

            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setText(new java.io.File(path).getName());
            name.setTextColor(Color.rgb(126, 199, 255));
            name.setTextSize(12f);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            name.getPaint().setFlags(Paint.UNDERLINE_TEXT_FLAG);
            text.addView(name, new LinearLayout.LayoutParams(MATCH, WRAP));
            TextView hint = new TextView(this);
            hint.setText("Open a preview window");
            hint.setTextColor(DIM);
            hint.setTextSize(10f);
            text.addView(hint, new LinearLayout.LayoutParams(MATCH, WRAP));
            item.addView(text, new LinearLayout.LayoutParams(0, WRAP, 1f));
            item.addView(vectorIcon("ic_chevron_right", DIM), iconSize(dp(15), dp(2)));

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAttachmentPreview(path);
                }
            });
            item.setTag("img|" + entry.time + "|" + path);
            card.addView(item, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.setMargins(0, dp(2), 0, dp(8));
        card.setTag("imgcard|" + entry.time + "|" + entry.images.size());
        feedList.addView(card, params);
    }

    private void addAssistantRow(AgentEngine.Entry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(2), dp(7), dp(2), dp(10));

        View spacer = new View(this);
        row.addView(spacer, iconSize(dp(25), 0));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        row.addView(content, new LinearLayout.LayoutParams(0, WRAP, 1f));
        addAssistantContent(content, entry.text);

        row.setTag("a|" + entry.time + "|" + entry.text);
        feedList.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void addAssistantContent(LinearLayout content, String text) {
        String[] parts = text.split("```", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.trim().isEmpty()) {
                continue;
            }
            if (i % 2 == 1) {
                String code = part;
                int newline = code.indexOf('\n');
                if (newline >= 0 && newline < 20) {
                    String first = code.substring(0, newline).trim();
                    if (!first.isEmpty() && first.matches("[A-Za-z0-9_+-]+")) {
                        code = code.substring(newline + 1);
                    }
                }
                TextView block = new TextView(this);
                block.setText(code.trim());
                block.setTextColor(Color.rgb(220, 220, 220));
                block.setTextSize(11.5f);
                block.setTypeface(Typeface.MONOSPACE);
                block.setTextIsSelectable(true);
                block.setBackground(rounded(CARD_TOOL, 8));
                block.setPadding(dp(10), dp(9), dp(10), dp(9));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
                params.setMargins(0, dp(3), 0, dp(7));
                content.addView(block, params);
                        } else {
                String plain = part.trim();
                if (plain.isEmpty()) {
                    continue;
                }
                LinearLayout mdBox = new LinearLayout(this);
                mdBox.setOrientation(LinearLayout.VERTICAL);
                UiMarkdown.render(this, mdBox, plain);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
                params.setMargins(0, 0, 0, dp(3));
                content.addView(mdBox, params);
            }
        }
    }

    private void addReasoningRow(final AgentEngine.Entry entry, boolean last) {
        final boolean expanded = reasoningOpen.contains(entry.time)
                || (!reasoningClosed.contains(entry.time) && last
                && entry.text.length() <= 4000);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(2), dp(2), dp(2), dp(2));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(0), dp(7), dp(0), dp(7));
        header.setClickable(true);
        header.setFocusable(true);
        ImageView icon = vectorIcon("ic_sparkle", MUTED);
        header.addView(icon, iconSize(dp(16), dp(9)));
        TextView label = new TextView(this);
        label.setText(expanded ? "Deep thinking. · Put it away."
                : "Deep thinking. · " + entry.text.length() + " Word · Expand");
        label.setTextColor(MUTED);
        label.setTextSize(12f);
        header.addView(label, new LinearLayout.LayoutParams(0, WRAP, 1f));
        ImageView chevron = vectorIcon(
                expanded ? "ic_chevron_down" : "ic_chevron_right", DIM);
        header.addView(chevron, iconSize(dp(16), dp(2)));
        header.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (expanded) {
                    reasoningOpen.remove(entry.time);
                    reasoningClosed.add(entry.time);
                } else {
                    reasoningClosed.remove(entry.time);
                    reasoningOpen.add(entry.time);
                }
                renderAll();
            }
        });
        container.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        if (expanded) {
            TextView body = new TextView(this);
            body.setText(entry.text);
            body.setTextColor(MUTED);
            body.setTextSize(12f);
            body.setLineSpacing(dp(4), 1f);
            body.setTextIsSelectable(true);
            body.setPadding(dp(25), 0, dp(4), dp(8));
            container.addView(body, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        container.setTag("r|" + entry.time + "|" + expanded + "|" + entry.text);
        feedList.addView(container, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void addToolRow(final AgentEngine.Entry entry) {
        String body = entry.text.substring(3).trim();
        int space = body.indexOf(' ');
        String name = space < 0 ? body : body.substring(0, space);
        String argsText = space < 0 ? "" : body.substring(space + 1).trim();
        JSONObject args = null;
        try {
            args = new JSONObject(argsText);
        } catch (Exception ignored) {
        }
        String summary = ToolPresentation.argumentSummary(name, args);
        String label = ToolPresentation.label(name);
        String iconName = ToolPresentation.icon(name);
        final String detail = label + (summary.isEmpty() ? "" : "  " + summary)
                + (argsText.isEmpty() || "{}".equals(argsText)
                ? "" : "\n\n" + argsText);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(7), dp(2), dp(7));
        row.setClickable(true);
        row.setFocusable(true);

        ImageView icon = vectorIcon(iconName, MUTED);
        row.addView(icon, iconSize(dp(17), dp(9)));

        SpannableStringBuilder builder = new SpannableStringBuilder();
        int start = builder.length();
        builder.append(label);
        builder.setSpan(new ForegroundColorSpan(TEXT), start, builder.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!summary.isEmpty()) {
            builder.append("  ");
            start = builder.length();
            builder.append(summary);
            builder.setSpan(new ForegroundColorSpan(MUTED), start, builder.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView text = new TextView(this);
        text.setText(builder);
        text.setTextSize(12.5f);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(text, new LinearLayout.LayoutParams(0, WRAP, 1f));

        ImageView chevron = vectorIcon("ic_chevron_right", DIM);
        row.addView(chevron, iconSize(dp(15), dp(2)));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LinearLayout content = AgentKit.column(MainActivity.this);
                content.setPadding(dp(18), 0, dp(18), 0);
                TextView body = new TextView(MainActivity.this);
                body.setText(detail);
                body.setTextColor(MUTED);
                body.setTextSize(12f);
                body.setLineSpacing(dp(3), 1f);
                body.setTextIsSelectable(true);
                content.addView(body, new LinearLayout.LayoutParams(MATCH, WRAP));
                AgentKit.sheet(MainActivity.this, "Details of the steps", content);
            }
        });
        row.setTag("t|" + entry.time + "|" + entry.text);
        feedList.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        // The model's one-line check for this step gets its own row under the call.
        String intent = args == null ? "" : args.optString("intent", "").trim();
        if (!intent.isEmpty()) {
            addIntentRow(entry.time, intent);
        }
    }

    private void addIntentRow(long key, String intent) {
        TextView row = new TextView(this);
        String shown = intent.length() > 120 ? intent.substring(0, 120) + "..." : intent;
        row.setText("Think. · " + shown);
        row.setTextColor(MUTED);
        row.setTextSize(11.5f);
        row.setPadding(dp(31), 0, dp(8), dp(6));
        row.setLineSpacing(dp(2), 1f);
        row.setTag("i|" + key + "|" + intent);
        feedList.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    /** Card: Show in the stream of conversations, click to open the question-by-issue answer drawer. */
    /** When there's only one option, it's just..."Suggested answers", It's not supposed to be a single choice./Multiple. */
    private static String choiceSuffix(JSONObject item) {
        if (item == null) {
            return "";
        }
        JSONArray options = item.optJSONArray("options");
        int count = options == null ? 0 : options.length();
        if (count < 2) {
            return "";
        }
        return item.optBoolean("multi", false) ? "((More Selectable)" : "(Single)";
    }

    private void loadQuestionAnswers() {
        if (questionAnswersLoaded) {
            return;
        }
        questionAnswersLoaded = true;
        try {
            String raw = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
                    .getString(KEY_QUESTION_ANSWERS, "");
            if (raw == null || raw.trim().isEmpty()) {
                return;
            }
            org.json.JSONObject all = new org.json.JSONObject(raw);
            java.util.Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                org.json.JSONArray array = all.optJSONArray(key);
                if (array == null) {
                    continue;
                }
                String[] answers = new String[array.length()];
                for (int i = 0; i < array.length(); i++) {
                    answers[i] = array.optString(i, "");
                }
                try {
                    questionAnswers.put(Long.valueOf(Long.parseLong(key)), answers);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void persistQuestionAnswers() {
        try {
            org.json.JSONObject all = new org.json.JSONObject();
            for (java.util.Map.Entry<Long, String[]> item : questionAnswers.entrySet()) {
                org.json.JSONArray array = new org.json.JSONArray();
                for (String answer : item.getValue()) {
                    array.put(answer == null ? "" : answer);
                }
                all.put(String.valueOf(item.getKey()), array);
            }
            getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_QUESTION_ANSWERS, all.toString())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private void addQuestionsRow(final AgentEngine.Entry entry) {
        final JSONArray questions = parseQuestions(entry.text);
        if (questions == null || questions.length() == 0) {
            addStatusRow(entry);
            return;
        }
        String[] saved = questionAnswers.get(Long.valueOf(entry.time));
        boolean answered = saved != null && answeredFully(saved);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD_SOFT, 16));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView title = new TextView(this);
        title.setText(answered ? "Question answered. · Click to Modify"
                : "I need your answer. " + questions.length() + " A question.");
        title.setTextColor(answered ? MUTED : TEXT);
        title.setTextSize(13f);
        card.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));

        for (int i = 0; i < questions.length(); i++) {
            JSONObject item = questions.optJSONObject(i);
            if (item == null) {
                continue;
            }
            TextView line = new TextView(this);
            line.setText((i + 1) + ". " + item.optString("question", "")
                    + choiceSuffix(item));
            line.setTextColor(MUTED);
            line.setTextSize(11.5f);
            line.setPadding(0, dp(7), 0, 0);
            card.addView(line, new LinearLayout.LayoutParams(MATCH, WRAP));
            if (answered && saved[i] != null && !saved[i].trim().isEmpty()) {
                TextView answer = new TextView(this);
                answer.setText("Response:" + saved[i]);
                answer.setTextColor(TEXT);
                answer.setTextSize(11.5f);
                answer.setPadding(dp(10), dp(2), 0, 0);
                card.addView(answer, new LinearLayout.LayoutParams(MATCH, WRAP));
            }
        }

        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new QuestionSheet(entry, questions).show();
            }
        });
        card.setTag("q|" + entry.time + "|" + entry.text + "|" + (answered ? 1 : 0));
        feedList.addView(card, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private static JSONArray parseQuestions(String text) {
        try {
            JSONArray array = new JSONArray(text);
            JSONArray normalized = new JSONArray();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = AgentEngine.normalizeQuestion(array.optJSONObject(i));
                if (item != null) {
                    normalized.put(item);
                }
            }
            return normalized.length() > 0 ? normalized : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The first issue is directly addressed when a new set of questions arises, and it is no longer necessary to allow user point cards. */
    private void maybeAutoOpenQuestion(List<AgentEngine.Entry> entries, boolean viewingOther) {
        if (viewingOther || entries.isEmpty()) {
            return;
        }
        int index = -1;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).type == AgentEngine.Entry.QUESTIONS) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        // (b) Only automatically play up to date issues that are not covered by the follow-up dialogue;ask The result line of your own tool does not count as overwhelm.
        for (int i = index + 1; i < entries.size(); i++) {
            int type = entries.get(i).type;
            if (type == AgentEngine.Entry.USER || type == AgentEngine.Entry.ASSISTANT) {
                return;
            }
        }
        final AgentEngine.Entry entry = entries.get(index);
        if (questionAnswers.containsKey(Long.valueOf(entry.time))
                || autoOpenedQuestions.contains(Long.valueOf(entry.time))) {
            return;
        }
        final JSONArray questions = parseQuestions(entry.text);
        if (questions == null || questions.length() == 0) {
            return;
        }
        feedList.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed() || !activityResumed
                        || feedList == null || !feedList.isAttachedToWindow()) {
                    return;
                }
                Window window = getWindow();
                if (window == null || window.getDecorView().getWindowToken() == null) {
                    return;
                }
                autoOpenedQuestions.add(Long.valueOf(entry.time));
                try {
                    new QuestionSheet(entry, questions).show();
                } catch (WindowManager.BadTokenException e) {
                    // Before the window goes out. Activity Window destroyed/Replace, skip, and not let the whole round fail.
                    Log.w("AgentUI", "question sheet skipped: bad token", e);
                } catch (RuntimeException e) {
                    Log.w("AgentUI", "question sheet skipped", e);
                }
            }
        });
    }

    private static boolean containsText(JSONArray options, String value) {
        for (int i = 0; i < options.length(); i++) {
            if (value.equals(options.optString(i, "").trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean answeredFully(String[] answers) {
        if (answers.length == 0) {
            return false;
        }
        for (String answer : answers) {
            if (answer == null || answer.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * One question per page: the model's recommendation is listed first, multi-select
     * is honoured, a free-text answer always wins, and already answered pages can be
     * revisited before the whole set is submitted back to the agent.
     */
    private final class QuestionSheet {
        private final AgentEngine.Entry entry;
        private final JSONArray questions;
        private final java.util.List<java.util.Set<String>> picked = new ArrayList<>();
        private final java.util.List<String> custom = new ArrayList<>();
        private LinearLayout body;
        private TextView backLabel;
        private TextView forwardLabel;
        private TextView progressLabel;
        private int page;
        private Dialog dialog;

        QuestionSheet(AgentEngine.Entry entry, JSONArray questions) {
            this.entry = entry;
            this.questions = questions;
            String[] saved = questionAnswers.get(Long.valueOf(entry.time));
            for (int i = 0; i < questions.length(); i++) {
                java.util.Set<String> chosen = new java.util.LinkedHashSet<>();
                String text = saved != null && i < saved.length && saved[i] != null
                        ? saved[i].trim() : "";
                JSONObject item = questions.optJSONObject(i);
                JSONArray options = item == null ? null : item.optJSONArray("options");
                if (options != null) {
                    for (String part : text.split(", ")) {
                        String value = part.trim();
                        if (!value.isEmpty() && containsText(options, value)) {
                            chosen.add(value);
                        }
                    }
                }
                picked.add(chosen);
                custom.add(chosen.isEmpty() ? text : "");
            }
        }

        void show() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            body = AgentKit.column(MainActivity.this);
            body.setPadding(dp(18), dp(4), dp(18), dp(4));
            ScrollView scroll = new ScrollView(MainActivity.this);
            scroll.addView(body, new ScrollView.LayoutParams(
                    android.widget.ScrollView.LayoutParams.MATCH_PARENT,
                    android.widget.ScrollView.LayoutParams.WRAP_CONTENT));

            LinearLayout footer = new LinearLayout(MainActivity.this);
            footer.setOrientation(LinearLayout.HORIZONTAL);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            footer.setPadding(dp(18), dp(10), dp(18), 0);
            backLabel = footerButton("‹ Previous question");
            progressLabel = new TextView(MainActivity.this);
            progressLabel.setTextColor(DIM);
            progressLabel.setTextSize(11.5f);
            progressLabel.setGravity(Gravity.CENTER);
            forwardLabel = footerButton("Next question. ›");
            footer.addView(backLabel, new LinearLayout.LayoutParams(0, WRAP, 1f));
            footer.addView(progressLabel, new LinearLayout.LayoutParams(0, WRAP, 1.4f));
            footer.addView(forwardLabel, new LinearLayout.LayoutParams(0, WRAP, 1f));
            backLabel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (page > 0) {
                        page--;
                        refresh();
                    }
                }
            });
            forwardLabel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (page < questions.length() - 1) {
                        page++;
                        refresh();
                    } else {
                        submit();
                    }
                }
            });

            LinearLayout panel = AgentKit.column(MainActivity.this);
            panel.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));
            panel.addView(footer, new LinearLayout.LayoutParams(MATCH, WRAP));
            try {
                dialog = AgentKit.sheet(MainActivity.this, "Answer the question.", panel);
                refresh();
            } catch (WindowManager.BadTokenException e) {
                Log.w("AgentUI", "question sheet failed: bad token", e);
            } catch (RuntimeException e) {
                Log.w("AgentUI", "question sheet failed", e);
            }
        }

        private TextView footerButton(String text) {
            TextView button = new TextView(MainActivity.this);
            button.setText(text);
            button.setTextColor(TEXT);
            button.setTextSize(12.5f);
            button.setGravity(Gravity.CENTER);
            button.setPadding(dp(10), dp(9), dp(10), dp(9));
            button.setBackground(rounded(CARD_SOFT, 10));
            return button;
        }

        private void refresh() {
            body.removeAllViews();
            JSONObject item = questions.optJSONObject(page);
            if (item == null) {
                return;
            }
            boolean multi = item.optBoolean("multi", false);
            String question = item.optString("question", "");
            TextView head = new TextView(MainActivity.this);
            head.setText(question + (multi ? "\n((More Selectable)" : "\n(Single)"));
            head.setTextColor(TEXT);
            head.setTextSize(14f);
            head.setLineSpacing(dp(3), 1f);
            body.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

            JSONArray options = item.optJSONArray("options");
            java.util.Set<String> chosen = picked.get(page);
            if (options != null && options.length() > 0) {
                for (int i = 0; i < options.length(); i++) {
                    final String value = options.optString(i, "").trim();
                    if (value.isEmpty()) {
                        continue;
                    }
                    final boolean selected = chosen.contains(value);
                    LinearLayout row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(10), dp(10), dp(10), dp(10));
                    row.setClickable(true);
                    row.setBackground(rounded(selected ? CARD_SOFT : CARD, 12));
                    TextView mark = new TextView(MainActivity.this);
                    mark.setText(selected ? (multi ? "☑" : "●") : (multi ? "☐" : "○"));
                    mark.setTextColor(selected ? TEXT : DIM);
                    mark.setTextSize(13f);
                    row.addView(mark, new LinearLayout.LayoutParams(WRAP, WRAP));
                    TextView label = new TextView(MainActivity.this);
                    label.setText((i == 0 ? "Recommendations · " : "") + value);
                    label.setTextColor(selected ? TEXT : MUTED);
                    label.setTextSize(12.5f);
                    label.setPadding(dp(9), 0, 0, 0);
                    row.addView(label, new LinearLayout.LayoutParams(0, WRAP, 1f));
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (multi) {
                                if (chosen.contains(value)) {
                                    chosen.remove(value);
                                } else {
                                    chosen.add(value);
                                }
                            } else {
                                chosen.clear();
                                chosen.add(value);
                            }
                            custom.set(page, "");
                            refresh();
                        }
                    });
                    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                            MATCH, WRAP);
                    rowParams.topMargin = dp(6);
                    body.addView(row, rowParams);
                }
            }

            final EditText free = new EditText(MainActivity.this);
            free.setHint("Or you can just write your answer.");
            free.setText(custom.get(page));
            free.setTextColor(TEXT);
            free.setHintTextColor(DIM);
            free.setTextSize(12.5f);
            free.setSingleLine(false);
            free.setMaxLines(4);
            free.setPadding(dp(12), dp(10), dp(12), dp(10));
            free.setBackground(rounded(CARD, 12));
            body.addView(free, new LinearLayout.LayoutParams(MATCH, WRAP, dp(8)));

            progressLabel.setText((page + 1) + " / " + questions.length());
            backLabel.setEnabled(page > 0);
            backLabel.setAlpha(page > 0 ? 1f : 0.35f);
            forwardLabel.setText(page < questions.length() - 1 ? "Next question. ›" : "Submit");
            free.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    String text = s == null ? "" : s.toString().trim();
                    custom.set(page, text);
                    if (!text.isEmpty()) {
                        picked.get(page).clear();
                    }
                }
            });
        }

        private void submit() {
            StringBuilder composed = new StringBuilder("Answer to the question:");
            String[] answers = new String[questions.length()];
            for (int i = 0; i < questions.length(); i++) {
                JSONObject item = questions.optJSONObject(i);
                String question = item == null ? "" : item.optString("question", "");
                String answer = answerFor(i);
                answers[i] = answer;
                composed.append('\n').append(i + 1).append(". ").append(question)
                        .append("\n   Answer:").append(answer);
            }
            if (!answeredFully(answers)) {
                Toast.makeText(MainActivity.this, "There's still no answer.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            questionAnswers.put(Long.valueOf(entry.time), answers);
            persistQuestionAnswers();
            if (dialog != null) {
                dialog.dismiss();
            }
            renderAll();
            for (String answer : answers) {
                if (answer != null && "Stop Task".equals(answer.trim())) {
                    engine.requestStop();
                    Toast.makeText(MainActivity.this, "Other Organiser",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            if (!engine.startTask(composed.toString())) {
                Toast.makeText(MainActivity.this, "Agent Under implementation. Try again later.",
                        Toast.LENGTH_SHORT).show();
            }
        }

        private String answerFor(int index) {
            String text = custom.get(index);
            if (text != null && !text.trim().isEmpty()) {
                return text.trim();
            }
            StringBuilder builder = new StringBuilder();
            for (String value : picked.get(index)) {
                if (builder.length() > 0) {
                    builder.append(", ");
                }
                builder.append(value);
            }
            return builder.toString();
        }
    }

    private void addErrorRow(AgentEngine.Entry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(2), dp(7), dp(2), dp(7));
        ImageView icon = vectorIcon("ic_close", ERROR);
        row.addView(icon, iconSize(dp(15), dp(9)));
        TextView body = new TextView(this);
        body.setText(entry.text);
        body.setTextColor(ERROR);
        body.setTextSize(12f);
        body.setLineSpacing(dp(3), 1f);
        row.addView(body, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.setTag("e|" + entry.time + "|" + entry.text);
        feedList.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void addStatusRow(AgentEngine.Entry entry) {
        TextView body = new TextView(this);
        body.setText(entry.text);
        body.setTextColor(DIM);
        body.setTextSize(10.5f);
        body.setPadding(dp(4), dp(5), dp(4), dp(5));
        body.setTag("s|" + entry.time + "|" + entry.text);
        feedList.addView(body, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void onSendOrStop() {
        if (!viewingRunningTask) {
            Toast.makeText(this, "read-only viewing; actionable after returning ongoing conversations",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String text = input.getText().toString().trim();
        if (engine.isRunning()) {
            if (text.isEmpty() && pendingImages.isEmpty() && pendingFiles.isEmpty()) {
                engine.requestStop();
                updateStatus();
                return;
            }
            if (text.startsWith("/")) {
                if ("/status".equalsIgnoreCase(text)) {
                    handleSlashCommand(text);
                } else {
                    Toast.makeText(this, "A slash command cannot be performed while the task is running;"
                            + "You can enter a plug-in directly or stop the task first.", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            submitInjection(text);
            return;
        }
        if (engine.isPaused() && text.isEmpty()
                && pendingImages.isEmpty() && pendingFiles.isEmpty()) {
            if (engine.resumeTask()) {
                renderAll();
                Toast.makeText(this, "Keep on with your mission.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Cannot continue: the context of the mission is not available",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (preprocessing) {
            Toast.makeText(this, "Local preprocession, please wait", Toast.LENGTH_SHORT).show();
            return;
        }
        if (text.isEmpty()) {
            if (!pendingImages.isEmpty()) {
                text = "Please look at these pictures and show what you see.";
            } else if (!pendingFiles.isEmpty()) {
                text = "Please check my upload file.";
            }
        }
        if (text.startsWith("/")) {
            if (handleSlashCommand(text)) {
                input.setText("");
                hideKeyboard();
            }
            return;
        }
        submitUserText(text, false);
    }

    private void submitInjection(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return;
        }
        if (!pendingImages.isEmpty() || !pendingFiles.isEmpty()) {
            Toast.makeText(this, "Could not add attachments to the assignment, please wait until after the assignment has ended",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        LocalPreprocessor.Result base = LocalPreprocessor.rulesOnly(text);
        if (base.action == LocalPreprocessor.ACTION_BLOCK
                || base.action == LocalPreprocessor.ACTION_LOCAL_REPLY) {
            showLocalGuardResult(base, text);
            return;
        }
        String outgoing = base.action == LocalPreprocessor.ACTION_REDACT
                ? base.text : text;
        if (engine.injectMessage(outgoing)) {
            if (base.action == LocalPreprocessor.ACTION_REDACT) {
                engine.addLocalNote("Local pre-treatment: plugins are dissensitized "
                        + String.join(", ", base.findings));
            }
            input.setText("");
            hideKeyboard();
            renderAll();
            Toast.makeText(this, engine.isPaused()
                            ? "We've signed up for orders. We're continuing...." : "Interrupted, effective in the next round.",
                    Toast.LENGTH_SHORT).show();
        } else {
            startTaskDirect(outgoing);
        }
    }

    private void submitUserText(final String raw, final boolean fromCommand) {
        if (!viewingRunningTask) {
            Toast.makeText(this, "read-only viewing; actionable after returning ongoing conversations",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (engine.isRunning()) {
            Toast.makeText(this, "Agent Under implementation. Please wait.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (preprocessing) {
            Toast.makeText(this, "Local preprocession, please wait", Toast.LENGTH_SHORT).show();
            return;
        }
        final String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return;
        }
        final String mode = engine.preprocessMode();
        if (Conversation.PREPROCESS_OFF.equals(mode)) {
            startTaskDirect(text);
            return;
        }
        final LocalPreprocessor.Result base = LocalPreprocessor.rulesOnly(text);
        if (base.action == LocalPreprocessor.ACTION_BLOCK
                || base.action == LocalPreprocessor.ACTION_LOCAL_REPLY) {
            showLocalGuardResult(base, text);
            return;
        }
        if (base.action == LocalPreprocessor.ACTION_REDACT) {
            showRedactionSheet(base, text);
            return;
        }
        if (Conversation.PREPROCESS_RULES.equals(mode) || fromCommand) {
            startTaskDirect(text);
            return;
        }
        runLocalModel(base, text);
    }

    private void runLocalModel(final LocalPreprocessor.Result base, final String original) {
        setPreprocessBusy(true);
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(18), 0, dp(18), 0);
        content.addView(AgentKit.hint(this,
                        "Local models are being judged. The first load takes about a few seconds; the model is fully functional and is not connected."),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        final TextView status = AgentKit.title(this, "In judgement....", 13f);
        status.setPadding(0, dp(14), 0, 0);
        content.addView(status, new LinearLayout.LayoutParams(MATCH, WRAP));
        final Dialog[] holder = new Dialog[1];
        final boolean[] canceled = {false};
        final boolean[] finished = {false};
        Button cancel = AgentKit.secondary(this, "Cancel");
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        cancelParams.setMargins(0, dp(16), 0, 0);
        content.addView(cancel, cancelParams);
        holder[0] = AgentKit.sheet(this, "Local Pre-processing", content);
        preprocessWaitDialog = holder[0];
        holder[0].setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                if (!finished[0]) {
                    canceled[0] = true;
                    setPreprocessBusy(false);
                }
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                canceled[0] = true;
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        LocalAi.get(this).classify(original, new LocalAi.ClassifyCallback() {
            @Override
            public void onResult(LocalAi.ClassifyResult result) {
                if (canceled[0] || isFinishing() || isDestroyed()) {
                    return;
                }
                finished[0] = true;
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                preprocessWaitDialog = null;
                setPreprocessBusy(false);
                if (result.error != null) {
                    Toast.makeText(MainActivity.this,
                            "Local models are not available:" + result.error + "; Continue as per the rules",
                            Toast.LENGTH_LONG).show();
                    startTaskDirect(original);
                    return;
                }
                LocalPreprocessor.Result combined =
                        LocalPreprocessor.applyModelHint(base, result);
                if (combined.action == LocalPreprocessor.ACTION_CONFIRM) {
                    showModelConfirm(combined, original);
                } else {
                    startTaskDirect(original);
                }
            }
        });
    }

    private void showLocalGuardResult(final LocalPreprocessor.Result result,
                                      final String original) {
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(18), 0, dp(18), 0);
        TextView message = new TextView(this);
        message.setText(result.reply);
        message.setTextColor(TEXT);
        message.setTextSize(13f);
        message.setLineSpacing(dp(3), 1f);
        message.setTextIsSelectable(true);
        content.addView(message, new LinearLayout.LayoutParams(MATCH, WRAP));
        content.addView(AgentKit.hint(this,
                        "This is a complete local determination, no upload, no consumption model. token. "),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout actions = AgentKit.row(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(16), 0, 0);
        final Dialog[] holder = new Dialog[1];
        Button modify = AgentKit.secondary(this, "Request for revision");
        modify.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                input.setText(original);
                input.setSelection(input.getText().length());
                input.requestFocus();
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        actions.addView(modify, new LinearLayout.LayoutParams(WRAP, WRAP));
        if ("impossible".equals(result.kind)) {
            Button override = AgentKit.secondary(this, "Try it again.");
            LinearLayout.LayoutParams overrideParams = new LinearLayout.LayoutParams(WRAP, WRAP);
            overrideParams.setMargins(dp(8), 0, 0, 0);
            actions.addView(override, overrideParams);
            override.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                    showGuardOverrideConfirm(result, original);
                }
            });
        }
        Button close = AgentKit.primary(this, "Okay.");
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        closeParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(close, closeParams);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        content.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));
        holder[0] = AgentKit.sheet(this,
                result.action == LocalPreprocessor.ACTION_BLOCK
                        ? "Local pre-treatment intercepted." : "Local answer", content);
    }

    private void showGuardOverrideConfirm(final LocalPreprocessor.Result guard,
                                          final String original) {
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(18), 0, dp(18), 0);
        TextView message = new TextView(this);
        message.setText("Continuing requests will skip local security rules and be processed by cloud-based models, possibly through automated access to real operations such as reading, sending and so on.\n\n"
                + "Agent I still won't get it. root, Installation of a confirmation or lock screen around the system; the key, password, cell phone number, etc. in the request will still automatically dissensitize.\n\n"
                + "Please confirm that this is your own equipment, your own account number and understand the possible consequences.");
        message.setTextColor(TEXT);
        message.setTextSize(13f);
        message.setLineSpacing(dp(3), 1f);
        message.setTextIsSelectable(true);
        content.addView(message, new LinearLayout.LayoutParams(MATCH, WRAP));
        content.addView(AgentKit.hint(this,
                        "Reasons for local interception:" + UiText.oneLine(guard.reply, 160)),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout actions = AgentKit.row(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(16), 0, 0);
        final Dialog[] holder = new Dialog[1];
        Button cancel = AgentKit.secondary(this, "Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(WRAP, WRAP));
        Button proceed = AgentKit.primary(this, "Get to the point. Go on.");
        LinearLayout.LayoutParams proceedParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        proceedParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(proceed, proceedParams);
        proceed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                startTaskWithGuardOverride(guard, original);
            }
        });
        content.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));
        holder[0] = AgentKit.sheet(this, "Confirming that we're crossing local protection?", content);
    }

    private void startTaskWithGuardOverride(LocalPreprocessor.Result guard, String original) {
        LocalPreprocessor.Sensitive sanitized = LocalPreprocessor.redactForOverride(original);
        StringBuilder note = new StringBuilder("Local pre-treatment has been intercepted:")
                .append(UiText.oneLine(guard.reply, 120))
                .append("; Users ' informed choice is still free");
        if (engine.isLocalModel()) {
            engine.setModelSource(Conversation.SOURCE_CLOUD);
            note.append(", Current dialogue has been converted to cloud model");
        }
        if (!sanitized.findings.isEmpty()) {
            note.append(", Sensitive information has automatically dissensitized:")
                    .append(String.join(", ", sanitized.findings));
        }
        note.append(". ");
        if (startTaskDirect(sanitized.text)) {
            engine.addLocalNote(note.toString());
        }
    }

    private void showRedactionSheet(final LocalPreprocessor.Result result,
                                    final String original) {
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(18), 0, dp(18), 0);
        content.addView(AgentKit.hint(this,
                        "Local rules found " + String.join(", ", result.findings)
                                + ", Replaced with placeholder. Original content will not be uploaded to cloud."),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView preview = new TextView(this);
        preview.setText(result.text);
        preview.setTextColor(MUTED);
        preview.setTextSize(11.5f);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setLineSpacing(dp(2), 1f);
        preview.setPadding(dp(12), dp(10), dp(12), dp(10));
        preview.setBackground(rounded(CARD_TOOL, 12));
        preview.setMaxLines(8);
        preview.setTextIsSelectable(true);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(MATCH, WRAP);
        previewParams.setMargins(0, dp(12), 0, 0);
        content.addView(preview, previewParams);
        LinearLayout actions = AgentKit.row(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(16), 0, 0);
        final Dialog[] holder = new Dialog[1];
        Button cancel = AgentKit.secondary(this, "Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(WRAP, WRAP));
        Button modify = AgentKit.secondary(this, "Modify");
        LinearLayout.LayoutParams modifyParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        modifyParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(modify, modifyParams);
        modify.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                input.setText(original);
                input.setSelection(input.getText().length());
                input.requestFocus();
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        Button send = AgentKit.primary(this, "De-sensitization.");
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        sendParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(send, sendParams);
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                engine.addLocalNote("Local pre-treatment: dissensitized "
                        + String.join(", ", result.findings));
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                startTaskDirect(result.text);
            }
        });
        content.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));
        holder[0] = AgentKit.sheet(this, "Sensitive information detected", content);
    }

    private void showModelConfirm(final LocalPreprocessor.Result result,
                                  final String original) {
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(18), 0, dp(18), 0);
        TextView message = new TextView(this);
        message.setText(result.reply);
        message.setTextColor(TEXT);
        message.setTextSize(13f);
        message.setLineSpacing(dp(3), 1f);
        content.addView(message, new LinearLayout.LayoutParams(MATCH, WRAP));
        content.addView(AgentKit.hint(this,
                        "This is a local small model judgement, which is only for reference purposes; you can modify it and send it back, or you can continue with the cloud-based model."),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout actions = AgentKit.row(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(16), 0, 0);
        final Dialog[] holder = new Dialog[1];
        Button cancel = AgentKit.secondary(this, "Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(WRAP, WRAP));
        Button modify = AgentKit.secondary(this, "Modify");
        LinearLayout.LayoutParams modifyParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        modifyParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(modify, modifyParams);
        modify.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                input.setText(original);
                input.setSelection(input.getText().length());
                input.requestFocus();
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        Button send = AgentKit.primary(this, "The clouds continue.");
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        sendParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(send, sendParams);
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                StringBuilder note = new StringBuilder("Local model tips:")
                        .append(UiText.oneLine(result.reply, 120))
                        .append("(User confirmed continuation");
                if (engine.isLocalModel()) {
                    engine.setModelSource(Conversation.SOURCE_CLOUD);
                    note.append(", Switched to Cloud Model");
                }
                note.append(")");
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                if (startTaskDirect(original)) {
                    engine.addLocalNote(note.toString());
                }
            }
        });
        content.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));
        holder[0] = AgentKit.sheet(this, "Local model recommended confirmation", content);
    }

    private void preloadLocalModel() {
        final LocalAi ai = LocalAi.get(this);
        if (ai.isLoaded()) {
            ai.warmInBackground();
            return;
        }
        if (ai.selectedFile() == null) {
            Toast.makeText(this, "Please download or import on the local model page first GGUF",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Loading local models in the background...", Toast.LENGTH_SHORT).show();
        ai.loadSelected(new LocalAi.LoadCallback() {
            @Override
            public void onDone(boolean ok, String info, String error) {
                if (ok) {
                    ai.warmInBackground();
                }
                Toast.makeText(MainActivity.this,
                        ok ? "Local model is ready" : "Local model load failed:" + error,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void setPreprocessBusy(boolean busy) {
        preprocessing = busy;
        if (busy) {
            // Recording time of start, for identification'Pre-treatment card died.'(The input box is permanently disabled when the local model is lost.
            preprocessStartedAt = System.currentTimeMillis();
        } else {
            preprocessStartedAt = 0L;
        }
        if (sendButton != null) {
            sendButton.setEnabled(!busy);
        }
        if (input != null) {
            boolean editable = !busy && viewingRunningTask;
            input.setEnabled(editable);
            input.setAlpha(editable ? 1f : 0.55f);
        }
        updateStatus();
    }

    /** Local preprocesser timed out: Auto unable input when the echo is lost, avoiding permanent intercailing'Local preprocession'.  */
    private void checkPreprocessStuck() {
        if (!preprocessing || preprocessStartedAt <= 0L) {
            return;
        }
        if (System.currentTimeMillis() - preprocessStartedAt < PREPROCESS_STUCK_MS) {
            return;
        }
        if (preprocessWaitDialog != null) {
            try {
                preprocessWaitDialog.dismiss();
            } catch (Exception ignored) {
            }
            preprocessWaitDialog = null;
        }
        setPreprocessBusy(false);
        Toast.makeText(this, "Local preprocession timeout has been restored; can be sent back directly",
                Toast.LENGTH_LONG).show();
    }

    private boolean startTaskDirect(String text) {
        String outgoing = text == null ? "" : text.trim();
        final ArrayList<AgentEngine.ImageAttachment> attachments =
                new ArrayList<>(pendingImages);
        final ArrayList<AgentEngine.FileAttachment> files =
                new ArrayList<>(pendingFiles);
        if (outgoing.isEmpty() && attachments.isEmpty() && files.isEmpty()) {
            return false;
        }
        if (outgoing.isEmpty()) {
            outgoing = attachments.isEmpty() ? "Please check my upload file."
                    : "Please look at the picture I've attached and show what you see.";
        }
        if (!viewingRunningTask) {
            Toast.makeText(this, "read-only viewing; actionable after returning ongoing conversations",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        feedAtBottom = true;
        if (engine.isRunning()) {
            if (!attachments.isEmpty() || !files.isEmpty()) {
                Toast.makeText(this, "Could not add attachments to the assignment, please wait until after the assignment has ended",
                        Toast.LENGTH_SHORT).show();
                return false;
            }
            if (engine.injectMessage(outgoing)) {
                input.setText("");
                hideKeyboard();
                renderAll();
                Toast.makeText(this, "Interrupted, effective in the next round.",
                        Toast.LENGTH_SHORT).show();
                return true;
            }
            Toast.makeText(this, "Mandate is closed and being sent as new",
                    Toast.LENGTH_SHORT).show();
        }
        Conversation activeConversation = engine.currentConversation();
        String activeKey = activeConversation == null ? prefs.apiKey()
                : prefs.apiKeyFor(activeConversation.modelService, activeConversation.baseUrl);
        if (engine.requiresApiKey() && activeKey.isEmpty()) {
            Toast.makeText(this, "Current model service not configured API Key", Toast.LENGTH_SHORT).show();
            showModelService();
            return false;
        }
        if (engine.isLocalModel() && sessions.otherRunning(engine.currentConversationId())) {
            Toast.makeText(this, "Local models can only run one task at a time; please wait for other conversations to be completed or for the cloud model to be used",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        if (engine.startTask(outgoing, attachments, files)) {
            closeCameraPreview();
            ScreenshotWatchService.stop(this);
            updateScreenshotModeBar();
            input.setText("");
            clearPendingAttachments();
            hideKeyboard();
            renderAll();
            return true;
        }
        Toast.makeText(this, "Agent Under implementation. Please wait.", Toast.LENGTH_SHORT).show();
        return false;
    }

    /** One-click to break the ceiling; due to the suspension of the ceiling, run directly and no more loses /overlimit.  */
    private void toggleOverLimit(boolean resumeIfLimited) {
        boolean enabled = !prefs.allowOverLimit();
        prefs.setAllowOverLimit(enabled);
        boolean resumed = false;
        if (enabled && resumeIfLimited && engine.isPaused() && engine.limitPaused()) {
            resumed = engine.resumeTask();
        }
        String message;
        if (enabled) {
            message = resumed
                    ? "The 'Frust the ceiling' has been activated and continues to be implemented.token You're on your own."
                    : "The 'Bridging the ceiling' has been activated: the rotation, budget, length and context windows are not being forced to suspend or block any more."
                            + "token You can take the consumption. You can click it off again.";
        } else {
            message = "The 'Break the ceiling' has been closed: restore the default protection of the rotation, budget and window.";
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        renderAll();
    }

    private boolean handleSlashCommand(String text) {
        String body = text == null ? "" : text.substring(1).trim();
        String command = body;
        String args = "";
        int space = body.indexOf(' ');
        if (space >= 0) {
            command = body.substring(0, space);
            args = body.substring(space + 1).trim();
        }
        command = command.toLowerCase(Locale.ROOT);
        if (command.isEmpty() || "help".equals(command) || "?".equals(command)) {
            showSlashCommands();
            return true;
        }
        if ("export".equals(command)) {
            Toast.makeText(this, engine.exportConversationJson(), Toast.LENGTH_LONG).show();
            return true;
        }
        if ("overlimit".equals(command)) {
            toggleOverLimit(true);
            return true;
        }
        if ("compact".equals(command)) {
            Toast.makeText(this, engine.compactNow(), Toast.LENGTH_LONG).show();
            renderAll();
            return true;
        }
        if ("new".equals(command) || "clear".equals(command)) {
            createConversation();
            return true;
        }
        if ("init".equals(command)) {
            String result = engine.initProjectRules();
            Toast.makeText(this, result, Toast.LENGTH_LONG).show();
            if (result.startsWith("Created")) {
                openEditor("AGENTS.md", "");
            }
            return true;
        }
        if ("plan".equals(command)) {
            if (args.isEmpty()) {
                input.setText("/plan ");
                input.setSelection(input.getText().length());
                input.requestFocus();
                Toast.makeText(this, "Yes. /plan Additional tasks later", Toast.LENGTH_SHORT).show();
                return false;
            }
            startTaskFromCommand("Call first. update_plan Development 3-8 The first step is to be verified."
                    + "The following tasks were then carried out as planned, and the evidence was validated and reported upon after completion:\n" + args);
            return true;
        }
        if ("review".equals(command)) {
            startTaskFromCommand("Please review the current workspace from the perspective of a senior engineer."
                    + (args.isEmpty() ? "" : "Medium " + args)
                    + ": First. glob/list_files Understanding structures, checking accuracy, security, border conditions,"
                    + "Read-only reviews, without modifying the document. Output by level of seriousness, you can read the document."
                    + "Each article attached file:line And minimal rehabilitation proposal.");
            return true;
        }
        if ("retro".equals(command)) {
            showSessionReview();
            return true;
        }
        if ("memory".equals(command)) {
            showMemory();
            return true;
        }
        if ("skills".equals(command)) {
            showCapabilities();
            return true;
        }
        if ("mcp".equals(command)) {
            showMcpDialog();
            return true;
        }
        if ("files".equals(command)) {
            showFiles();
            return true;
        }
        if ("settings".equals(command)) {
            showConversationSettings();
            return true;
        }
        if ("selfcheck".equals(command)) {
            showSelfCheck();
            return true;
        }
        if ("status".equals(command)) {
            AgentEngine.ContextInfo info = engine.contextInfo();
            Toast.makeText(this, "Model " + (engine.currentConversation() == null ? "-"
                            : engine.currentConversation().model)
                            + " · Mode " + modeLabel(engine.approvalMode())
                            + " · Context " + info.percent + "%(About " + info.usedTokens
                            + "/" + info.budgetTokens + " tokens)"
                            + " · Authorized Tools " + engine.alwaysAllowedTools().size() + " individual",
                    Toast.LENGTH_LONG).show();
            return true;
        }
        showSlashCommands();
        Toast.makeText(this, "Unknown command /" + command, Toast.LENGTH_SHORT).show();
        return true;
    }

    private void startTaskFromCommand(String prompt) {
        submitUserText(prompt, true);
    }

    private void showSlashCommands() {
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(18), 0, dp(18), 0);
        TextView hint = AgentKit.hint(this, "Enter directly in the input box / Additional orders may also be executed.");
        content.addView(hint, new LinearLayout.LayoutParams(MATCH, WRAP));
        String[][] commands = {
                {"/plan", "Develop a verifiable implementation plan", "ic_grid"},
                {"/compact", "Compress earlier context to keep the latest message", "ic_archive"},
                {"/retro", "Generate session reset and save to workspace", "ic_sparkle"},
                {"/memory", "View, add or refine long-term memories", "ic_sparkle"},
                {"/review", "Review the area code on a read-only basis", "ic_file"},
                {"/init", "Create AGENTS.md Project rules", "ic_file"},
                {"/new", "New Dialogue", "ic_plus"},
                {"/selfcheck", "Inspection Agent You're in a state of mind and you're gonna give a fix.", "ic_alert"},
                {"/status", "View context, mode and authorized status", "ic_terminal"},
                {"/overlimit", "Open/Close the 'break the ceiling': no longer impose a moratorium or block (cost-sharing)", "ic_alert"},
                {"/export", "Export current dialogue, message and tool definition to workspace .agent/export(Manual, sensitive content risk)", "ic_folder"},
                {"/files", "Browse workspace files", "ic_folder"},
                {"/settings", "Opens the current session settings", "ic_settings"}
        };
        final Dialog[] holder = new Dialog[1];
        for (final String[] command : commands) {
            LinearLayout row = AgentKit.row(this);
            row.setPadding(dp(4), dp(10), dp(4), dp(10));
            row.setClickable(true);
            row.setFocusable(true);
            row.addView(vectorIcon(command[2], MUTED), iconSize(dp(18), dp(10)));
            LinearLayout info = AgentKit.column(this);
            TextView name = AgentKit.title(this, command[0], 12.5f);
            info.addView(name, new LinearLayout.LayoutParams(MATCH, WRAP));
            TextView desc = AgentKit.hint(this, command[1]);
            info.addView(desc, new LinearLayout.LayoutParams(MATCH, WRAP));
            row.addView(info, new LinearLayout.LayoutParams(0, WRAP, 1f));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                    handleSlashCommand(command[0]);
                }
            });
            content.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        holder[0] = AgentKit.sheet(this, "Slash Command", scroll, 520);
    }

    private void updateStatus() {
        if (statusText == null) {
            return;
        }
        updateTaskCenterChip();
        boolean viewingOther = !viewingRunningTask && viewedConversation != null;
        Conversation display = viewingOther ? viewedConversation
                : engine.currentConversation();
        if (viewedBar != null) {
            viewedBar.setVisibility(viewingOther ? View.VISIBLE : View.GONE);
        }
        if (titleView != null) {
            titleView.setText(display == null ? Conversation.DEFAULT_TITLE : display.title);
        }
        if (statusText != null) {
            if (viewingOther) {
                String status = workspaceLabel(display.workspace) + " · Read-only View";
                statusText.setText(status);
                statusText.setTextColor(Color.rgb(232, 188, 92));
                statusText.setClickable(true);
                statusText.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        returnToRunningConversation();
                    }
                });
            } else {
                statusText.setClickable(false);
                statusText.setOnClickListener(null);
                AgentEngine.ContextInfo context = engine.contextInfo();
                String status = workspaceLabel(engine.currentWorkspacePath())
                        + " · Context " + context.percent + "%";
                if (context.droppedMessages > 0) {
                    status += " · Cropd";
                }
                if (engine.isRunning()) {
                    status += " · Under implementation";
                } else if (engine.isPaused()) {
                    status += " · Paused";
                }
                statusText.setText(status);
                statusText.setTextColor(context.percent >= 85 ? ERROR : MUTED);
            }
        }
        if (modelText != null) {
            modelText.setText(display == null ? "-" : modelLabel(display));
        }
        if (modeIcon != null) {
            String mode = display == null ? Conversation.APPROVAL_AUTO
                    : display.approvalMode;
            modeIcon.setColorFilter(Conversation.APPROVAL_ASK.equals(mode)
                    ? Color.rgb(232, 188, 92)
                    : Conversation.APPROVAL_READONLY.equals(mode)
                    ? Color.rgb(255, 148, 98) : MUTED);
        }
        boolean running = engine.isRunning() && !viewingOther;
        boolean paused = engine.isPaused() && !viewingOther;
        boolean hasText = input != null && input.getText().length() > 0;
        if (runStatusBar != null) {
            runStatusBar.setVisibility(running || paused ? View.VISIBLE : View.GONE);
            int max = Math.max(1, engine.maxSteps());
            String maxLabel = prefs.allowOverLimit() ? "Unlimited" : String.valueOf(max);
            if (paused) {
                if (runSpinner != null) {
                    runSpinner.setVisibility(View.GONE);
                }
                if (runIcon != null) {
                    runIcon.setVisibility(View.VISIBLE);
                }
                runStatusText.setText(engine.limitPaused() ? "Achieving the ceiling, suspended" : "Task suspended.");
                runStepText.setText(engine.currentStep() + "/" + maxLabel);
                runPauseText.setText("Go on.");
            } else if (running) {
                if (runSpinner != null) {
                    runSpinner.setVisibility(View.VISIBLE);
                }
                if (runIcon != null) {
                    runIcon.setVisibility(View.GONE);
                }
                String statusText = hasText ? "The plug will be effective in the next round."
                : (engine.runPhase().isEmpty() ? "Task in progress..." : engine.runPhase());
        String statusDetail = engine.runDetail();
        if (!hasText && statusDetail != null && !statusDetail.isEmpty()) {
            statusText = statusText + "  " + statusDetail;
        }
        runStatusText.setText(statusText);
        runStatusText.setSingleLine(true);
        runStatusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
                runStepText.setText(engine.currentStep() + "/" + maxLabel);
                runPauseText.setText("Pause");
            } else {
                if (runSpinner != null) {
                    runSpinner.setVisibility(View.GONE);
                }
                if (runIcon != null) {
                    runIcon.setVisibility(View.VISIBLE);
                }
            }
            runPauseText.setVisibility(paused || running ? View.VISIBLE : View.GONE);
            runStopText.setVisibility(paused || running ? View.VISIBLE : View.GONE);
            if (runLimitText != null) {
                boolean overLimit = prefs.allowOverLimit();
                runLimitText.setVisibility(paused || running ? View.VISIBLE : View.GONE);
                runLimitText.setText(overLimit ? "The ceiling is open."
                        : engine.limitPaused() ? "Break the ceiling and continue." : "Breaking the ceiling.");
                runLimitText.setTextColor(overLimit ? Color.rgb(232, 188, 92) : TEXT);
            }
        }
        if (sendButton != null) {
            String symbol;
            if (preprocessing) {
                symbol = "...";
            } else if (viewingOther) {
                symbol = "↑";
            } else if (running) {
                symbol = hasText ? "↑" : "■";
            } else if (paused) {
                symbol = hasText ? "↑" : "▶";
            } else {
                symbol = "↑";
            }
            sendButton.setText(symbol);
            sendButton.setTextColor(Color.rgb(8, 8, 8));
            sendButton.setTextSize(preprocessing ? 14f : running || paused ? 15f : 20f);
            sendButton.setBackground(rounded(Color.WHITE, 19));
            sendButton.setEnabled(!preprocessing && !viewingOther);
        }
        if (input != null) {
            boolean editable = !preprocessing && !viewingOther;
            input.setEnabled(editable);
            input.setAlpha(editable ? 1f : 0.55f);
            if (preprocessing) {
                input.setHint("Local preprocession...");
            } else if (viewingOther) {
                input.setHint("Read-only view: actionable after returning to ongoing dialogue");
            } else if (running) {
                input.setHint("Intersect: The task continues and the content will be added to the next round...");
            } else if (paused) {
                input.setHint("Job suspended: enter new command to continue, or dot ▶ Go on....");
            } else {
                input.setHint("Describe your mission....");
            }
        }
    }

    private void showArtifacts(final String relativePath) {
        if (!viewingRunningTask) {
            Toast.makeText(this, "Reading-only; browsing documents after returning to ongoing conversations",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        File directory;
        try {
            directory = workspace.resolve(relativePath);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!directory.isDirectory()) {
            if (directory.isFile()) {
                openEditor(relativePath, engine.currentWorkspacePath());
            } else {
                Toast.makeText(this, "The directory is not available", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        FrameLayout header = new FrameLayout(this);
        header.setPadding(dp(10), dp(10), dp(10), dp(4));
        root.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
        ImageView back = vectorButton("ic_back", TEXT, 40);
        header.addView(back, new FrameLayout.LayoutParams(dp(40), dp(40),
                Gravity.START | Gravity.CENTER_VERTICAL));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (relativePath == null || relativePath.isEmpty()) {
                    return;
                }
                int slash = relativePath.lastIndexOf('/');
                String parent = slash < 0 ? "" : relativePath.substring(0, slash);
                showArtifacts(parent);
            }
        });
        TextView title = new TextView(this);
        title.setText("Products");
        title.setTextColor(TEXT);
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new FrameLayout.LayoutParams(MATCH, dp(48), Gravity.CENTER));

        TextView path = new TextView(this);
        path.setText(workspaceLabel(engine.currentWorkspacePath())
                + (relativePath == null || relativePath.isEmpty() ? "" : " / " + relativePath));
        path.setTextColor(DIM);
        path.setTextSize(10.5f);
        path.setPadding(dp(18), dp(2), dp(18), dp(8));
        root.addView(path, new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), 0, dp(16), dp(20));
        scroll.addView(list, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        File[] children = directory.listFiles();
        List<File> dirs = new ArrayList<>();
        List<File> files = new ArrayList<>();
        if (children != null) {
            for (File child : children) {
                if (child.getName().startsWith(".")) {
                    continue;
                }
                (child.isDirectory() ? dirs : files).add(child);
            }
        }
        java.util.Collections.sort(dirs, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        java.util.Collections.sort(files, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        List<File> all = new ArrayList<>();
        all.addAll(dirs);
        all.addAll(files);
        if (all.isEmpty()) {
            TextView empty = formHint("This directory is empty.");
            empty.setPadding(dp(8), dp(20), dp(8), dp(20));
            list.addView(empty, new LinearLayout.LayoutParams(MATCH, WRAP));
        } else {
            for (final File child : all) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(11), dp(4), dp(11));
                row.setClickable(true);
                row.setFocusable(true);
                ImageView icon = vectorIcon(child.isDirectory() ? "ic_folder" : "ic_file",
                        child.isDirectory() ? Color.rgb(226, 178, 84) : MUTED);
                row.addView(icon, iconSize(dp(21), dp(12)));
                TextView name = new TextView(this);
                name.setText(child.getName());
                name.setTextColor(TEXT);
                name.setTextSize(14f);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1f));
                if (child.isDirectory()) {
                    ImageView chevron = vectorIcon("ic_chevron_right", DIM);
                    row.addView(chevron, iconSize(dp(16), 0));
                    final String childPath = relativePath == null || relativePath.isEmpty()
                            ? child.getName() : relativePath + "/" + child.getName();
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dialog.dismiss();
                            showArtifacts(childPath);
                        }
                    });
                } else {
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            String childPath = relativePath == null || relativePath.isEmpty()
                                    ? child.getName() : relativePath + "/" + child.getName();
                            openEditor(childPath, engine.currentWorkspacePath());
                        }
                    });
                }
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(MATCH, WRAP);
                rowParams.setMargins(0, 1, 0, 1);
                list.addView(row, rowParams);
            }
        }

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(BG));
            window.setLayout(MATCH, MATCH);
        }
        dialog.show();
        if (window != null) {
            window.setLayout(MATCH, MATCH);
        }
    }

    private String bridgeSummary() {
        return prefs.bridgeAutoEnabled() ? "Automatic Response" : "Closed";
    }

    private void showBridgeSettings() {
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(16), dp(4), dp(16), dp(12));
        content.addView(AgentKit.hint(this,
                        "Agent The request is automatically processed and returned. Agent interface."
                                + "Other App Whether to automatically request the request is left to each App Control: Default manual action,"
                                + "App Query Query'AutoSend'. "),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        content.addView(AgentKit.toggleRow(this, "Enable Agent Bridge",
                        "No listening after closing. App Bridge requests", prefs.bridgeAutoEnabled(),
                        new AgentKit.ToggleHandler() {
                            @Override
                            public void onToggle(boolean checked) {
                                prefs.setBridgeAutoEnabled(checked);
                                if (checked || prefs.debugBridgeEnabled()) {
                                    BridgeAutoService.start(MainActivity.this);
                                } else {
                                    BridgeAutoService.stop(MainActivity.this);
                                }
                            }
                        }), new LinearLayout.LayoutParams(MATCH, WRAP));
        content.addView(AgentKit.toggleRow(this, "Debugging data bridge (in-house)",
                        "For debugging computers state.json with the inbound command entrance; only open when debugged and deleting tokens after closing/Status",
                        prefs.debugBridgeEnabled(), new AgentKit.ToggleHandler() {
                            @Override
                            public void onToggle(boolean checked) {
                                prefs.setDebugBridgeEnabled(checked);
                                if (checked) {
                                    DebugBridge.prepare(MainActivity.this);
                                    BridgeAutoService.start(MainActivity.this);
                                    Toast.makeText(MainActivity.this,
                                            "Debugging the data bridge has been opened:/sdcard/MobileAgent/agent-bridge/debug/state.json",
                                            Toast.LENGTH_LONG).show();
                                } else {
                                    DebugBridge.disable(MainActivity.this);
                                    if (!prefs.bridgeAutoEnabled()) {
                                        BridgeAutoService.stop(MainActivity.this);
                                    }
                                }
                            }
                        }), new LinearLayout.LayoutParams(MATCH, WRAP));
        content.addView(AgentKit.hint(this, "Debug entry statement:/sdcard/MobileAgent/DEBUG_API.md\n"
                        + "Read first state.json / Let's go. debug command;stopcharts only for visual acceptance"),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        java.util.Set<String> seen = prefs.bridgeSeenApps();
        if (!seen.isEmpty()) {
            StringBuilder builder = new StringBuilder("Recent use of bridges:\n");
            for (String app : seen) {
                builder.append("- ").append(app).append('\n');
            }
            content.addView(AgentKit.hint(this, builder.toString().trim()),
                    new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        AgentKit.page(this, "Agent Bridge", scroll);
    }

    private void showLocalConfig() {
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(16), dp(4), dp(16), dp(12));
        content.addView(AgentKit.hint(this,
                        "All settings are modified to write the following files automatically; file is filled with missing settings for the first start after overlaying installation (without unmounting).\n"
                                + "Model parameters in a session are saved with the dialogue file, and are also maintained after the overlay is installed.\n"
                                + "API Key, Installs passwords, debugs, etc., and keys are stored only in the application private storage and not in this document."),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        content.addView(AgentKit.hint(this,
                        "Autosave is open: write immediately after each change set, without having to save manually."),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        final TextView detail = AgentKit.hint(this, LocalConfig.detailText());
        detail.setTextIsSelectable(true);
        content.addView(detail, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button merge = AgentKit.secondary(this, "Complete Missing Settings");
        merge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int restored = LocalConfig.restoreMissing();
                detail.setText(LocalConfig.detailText());
                Toast.makeText(MainActivity.this,
                        restored > 0 ? "Complete from File " + restored + " Item" : "No missing settings",
                        Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams mergeParams = new LinearLayout.LayoutParams(MATCH, WRAP);
        mergeParams.setMargins(0, dp(12), 0, 0);
        actions.addView(merge, mergeParams);
        content.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        AgentKit.page(this, "Local Profile", scroll);
    }

    private void markSettingsDirty() {
        settingsDirty = true;
        if (settingsDialog == null || !settingsDialog.isShowing()
                || settingsDialog.getWindow() == null) {
            return;
        }
        View decor = settingsDialog.getWindow().getDecorView();
        if (decor != null && decor.hasWindowFocus()) {
            settingsDirty = false;
            settingsDialog.dismiss();
            showSettingsScreen();
        }
    }

    private void showSettingsScreen() {
        if (settingsDialog != null && settingsDialog.isShowing()) {
            settingsDialog.dismiss();
        }
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        FrameLayout header = new FrameLayout(this);
        header.setPadding(dp(10), dp(10), dp(10), dp(6));
        root.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));
        ImageView back = vectorButton("ic_back", TEXT, 40);
        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(dp(40), dp(40),
                Gravity.START | Gravity.CENTER_VERTICAL);
        header.addView(back, backParams);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        TextView title = new TextView(this);
        title.setText(LanguageManager.t(this, "Settings"));
        title.setTextColor(TEXT);
        title.setTextSize(16f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new FrameLayout.LayoutParams(MATCH, dp(48), Gravity.CENTER));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(2), dp(16), dp(26));
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        root.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        LinearLayout account = new LinearLayout(this);
        account.setOrientation(LinearLayout.HORIZONTAL);
        account.setGravity(Gravity.CENTER_VERTICAL);
        account.setBackground(rounded(CARD, 18));
        account.setPadding(dp(16), dp(14), dp(16), dp(14));
        content.addView(account, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout accountInfo = new LinearLayout(this);
        accountInfo.setOrientation(LinearLayout.VERTICAL);
        account.addView(accountInfo, new LinearLayout.LayoutParams(0, WRAP, 1f));
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        accountInfo.addView(nameRow, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView appName = new TextView(this);
        appName.setText("CRLM");
        appName.setTextColor(TEXT);
        appName.setTextSize(16f);
        appName.setTypeface(Typeface.DEFAULT_BOLD);
        nameRow.addView(appName, new LinearLayout.LayoutParams(WRAP, WRAP));
        TextView badge = new TextView(this);
        badge.setText("Local Agent");
        badge.setTextColor(SUCCESS);
        badge.setTextSize(9f);
        badge.setBackground(rounded(Color.rgb(18, 38, 28), 6));
        badge.setPadding(dp(7), dp(2), dp(7), dp(2));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        badgeParams.setMargins(dp(8), 0, 0, 0);
        nameRow.addView(badge, badgeParams);
        TextView accountSub = new TextView(this);
        accountSub.setText("Version " + versionName() + " · " + workspaceLabel(engine.currentWorkspacePath()));
        accountSub.setTextColor(MUTED);
        accountSub.setTextSize(10.5f);
        accountSub.setPadding(0, dp(4), 0, 0);
        accountInfo.addView(accountSub, new LinearLayout.LayoutParams(MATCH, WRAP));
        ImageView accountIcon = vectorIcon("ic_grid", MUTED);
        account.addView(accountIcon, iconSize(dp(20), 0));

        addSettingsSection(content, "Language");
        LinearLayout languageCard = settingsCard(content);
        addSettingsRow(languageCard, "ic_grid", "Language",
                LanguageManager.label(this), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showLanguageDialog();
                    }
                });

        addSettingsSection(content, "Model");
        LinearLayout modelCard = settingsCard(content);
        addSettingsRow(modelCard, "ic_settings", "Model services",
                modelServicesSummary(), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showModelService();
                    }
                });
        addSettingsRow(modelCard, "ic_sparkle", "Current Model",
                engine.currentConversation() == null ? "-"
                        : engine.currentConversation().model, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showModelSheet();
                    }
                });
        addSettingsRow(modelCard, "ic_grid", "Model capabilities",
                currentModelProfileSummary(), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Conversation conversation = engine.currentConversation();
                        if (conversation == null) {
                            return;
                        }
                        showModelCapabilities(conversation.baseUrl, conversation.model);
                    }
                });
        addSettingsRow(modelCard, "ic_file", "Session Settings", "Workspace and Parameters",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showConversationSettings();
                    }
                }, true);

        addSettingsSection(content, "Harness");
        LinearLayout harnessCard = settingsCard(content);
        settingsModeValue = addSettingsRow(harnessCard, "ic_alert", "Run Mode",
                modeLabel(engine.approvalMode()), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showModeSheet();
                    }
                });
        final TextView[] autoResumeValue = new TextView[1];
        autoResumeValue[0] = addSettingsRow(harnessCard, "ic_alert",
                "Autorecover after break",
                prefs.autoResumeInterrupted() ? "Open" : "Closed",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        prefs.setAutoResumeInterrupted(!prefs.autoResumeInterrupted());
                        boolean on = prefs.autoResumeInterrupted();
                        autoResumeValue[0].setText(on ? "Open" : "Closed");
                        Toast.makeText(MainActivity.this,
                                on ? "After break, try to automatically restore the uncompleted tasks"
                                        : "Show pause after break. Continue manually.",
                                Toast.LENGTH_SHORT).show();
                    }
                });
        final TextView[] liteValue = new TextView[1];
        liteValue[0] = addSettingsRow(harnessCard, "ic_alert",
                "Lite Mode (Provinces) token)",
                prefs.liteHarness() ? "Open" : "Closed",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        prefs.setLiteHarness(!prefs.liteHarness());
                        boolean on = prefs.liteHarness();
                        liteValue[0].setText(on ? "Open" : "Closed");
                        Toast.makeText(MainActivity.this,
                                on ? "Lite: 12k Context / 12 Wheel / Core tool, autoinjection and reset closed"
                                        : "Complete mode: restoration of memory/Skills/AutoResearch/Rewind and Extension",
                                Toast.LENGTH_SHORT).show();
                    }
                });
        final TextView[] autoToolsValue = new TextView[1];
        autoToolsValue[0] = addSettingsRow(harnessCard, "ic_grid",
                "AutoSimplify Tool",
                prefs.autoToolSelection() ? "Open" : "Closed",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        prefs.setAutoToolSelection(!prefs.autoToolSelection());
                        boolean on = prefs.autoToolSelection();
                        autoToolsValue[0].setText(on ? "Open" : "Closed");
                        Toast.makeText(MainActivity.this,
                                on ? "Sending related tools only by task, less token"
                                        : "Sending all the tools every time. Best compatible, but more expensive. token",
                                Toast.LENGTH_SHORT).show();
                    }
                });
        final TextView[] bridgeValue = new TextView[1];
        bridgeValue[0] = addSettingsRow(harnessCard, "ic_sparkle", "Agent Bridge",
                bridgeSummary(), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showBridgeSettings();
                        if (bridgeValue[0] != null) {
                            bridgeValue[0].setText(bridgeSummary());
                        }
                    }
                });
        final TextView[] smartValue = new TextView[1];
        smartValue[0] = addSettingsRow(harnessCard, "ic_grid", "Smart Code Retrieving",
                prefs.smartContextEnabled() ? "Open" : "Closed",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        prefs.setSmartContextEnabled(!prefs.smartContextEnabled());
                        boolean on = prefs.smartContextEnabled();
                        smartValue[0].setText(on ? "Open" : "Closed");
                        Toast.makeText(MainActivity.this,
                                on ? "The program automatically retrieves the relevant code clips at the beginning"
                                        : "Turn off auto-search, switch to repo_search Manually Search",
                                Toast.LENGTH_SHORT).show();
                    }
                });
        settingsWebValue = addSettingsRow(harnessCard, "ic_file", "Network Search/Capture",
                prefs.allowWebTools() ? "Open" : "Closed", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        prefs.setAllowWebTools(!prefs.allowWebTools());
                        boolean on = prefs.allowWebTools();
                        if (settingsWebValue != null) {
                            settingsWebValue.setText(on ? "Open" : "Closed");
                        }
                        Toast.makeText(MainActivity.this,
                                on ? "Network Search/Capture is on." : "Network Search/Fetching closed;specified web pages/Search missions are still automatically authorized.",
                                Toast.LENGTH_SHORT).show();
                    }
                });
        final TextView[] hookValue = new TextView[1];
        hookValue[0] = addSettingsRow(harnessCard, "ic_terminal", "Hooks(Before and after tools/♪ End of the game ♪",
                prefs.allowHooks() ? "Open" : "Closed",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        prefs.setAllowHooks(!prefs.allowHooks());
                        boolean on = prefs.allowHooks();
                        hookValue[0].setText(on ? "Open" : "Closed");
                        Toast.makeText(MainActivity.this,
                                on ? "Hooks Enabled: Configure .agent/hooks.json (a) After entry into force;Hook Here. App Authority execution shell"
                                        : "Hooks Closed",
                                Toast.LENGTH_LONG).show();
                    }
                });
        addSettingsRow(harnessCard, "ic_file", "Hooks Configure", ".agent/hooks.json",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openHooksConfig();
                    }
                }, true);
        addSettingsRow(harnessCard, "ic_grid", "Internal Tools",
                ToolDefs.tools().length() + " individual", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showCapabilities();
                    }
                });
        addSettingsRow(harnessCard, "ic_terminal", "Local Model",
                LocalAi.get(this).statusText(), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showLocalAi();
                    }
                }, true);

        addSettingsSection(content, "Capacity");
        LinearLayout abilityCard = settingsCard(content);
        addSettingsRow(abilityCard, "ic_sparkle", "Capabilities and memory", "Rules / Skills / Knowledge base",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showCapabilities();
                    }
                });
        addSettingsRow(abilityCard, "ic_archive", "Automation",
                AutomationStore.get(this).tasks().size() + " Tasks",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(MainActivity.this, AutomationActivity.class));
                    }
                }, true);
        addSettingsRow(abilityCard, "ic_grid", "MCP Services", "Tool access",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showMcpDialog();
                    }
                }, true);

        addSettingsSection(content, "Equipment");
        LinearLayout deviceCard = settingsCard(content);
        addSettingsRow(deviceCard, "ic_device", "Devices and Permissions",
                prefs.allowDeviceControl() ? "Control of equipment is in place." : "Equipment control not open",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showDevice();
                    }
                }, true);

        addSettingsSection(content, "Cache");
        LinearLayout cacheCard = settingsCard(content);
        addSettingsRow(cacheCard, "ic_archive", "Clearing the running log", "Release of provisional documents",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        clearRunLogs();
                    }
                }, true);

        addSettingsSection(content, "Configure");
        LinearLayout configCard = settingsCard(content);
        addSettingsRow(configCard, "ic_file", "Local Profile", LocalConfig.savedLabel(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showLocalConfig();
                    }
                }, true);

        addSettingsSection(content, "About");
        LinearLayout aboutCard = settingsCard(content);
        addSettingsRow(aboutCard, "ic_file", "Version", versionName(), null);
        addSettingsRow(aboutCard, "ic_folder", "Workspace File", "Browse the current directory",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showArtifacts("");
                    }
                }, true);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(BG));
            window.setLayout(MATCH, MATCH);
        }
        LanguageManager.apply(root);
        dialog.show();
        if (window != null) {
            window.setLayout(MATCH, MATCH);
        }
        settingsDialog = dialog;
        settingsDirty = false;
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface unused) {
                if (settingsDialog == dialog) {
                    settingsDialog = null;
                }
            }
        });
        final Dialog built = dialog;
        root.getViewTreeObserver().addOnWindowFocusChangeListener(
                new ViewTreeObserver.OnWindowFocusChangeListener() {
                    @Override
                    public void onWindowFocusChanged(boolean hasFocus) {
                        if (hasFocus && settingsDirty && settingsDialog == built) {
                            settingsDirty = false;
                            root.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (settingsDialog == built) {
                                        built.dismiss();
                                        showSettingsScreen();
                                    }
                                }
                            });
                        }
                    }
                });
    }

    private void showLanguageDialog() {
        final java.util.List<String> codes = LanguageManager.codes(this);
        final String[] items = new String[codes.size() + 1];
        for (int i = 0; i < codes.size(); i++) {
            items[i] = LanguageManager.displayName(this, codes.get(i));
        }
        items[items.length - 1] = "Add language with model...";
        new android.app.AlertDialog.Builder(this)
                .setTitle(LanguageManager.t(this, "Language"))
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which < codes.size()) {
                            LanguageManager.set(MainActivity.this, codes.get(which));
                            recreate();
                            return;
                        }
                        addLanguageWithModel();
                    }
                })
                .show();
    }

    private void addLanguageWithModel() {
        AgentKit.input(this, "Add language", "Language name, e.g. Japanese", "",
                "Translate", new AgentKit.InputHandler() {
                    @Override
                    public boolean onInput(String value, Dialog dialog) {
                        final String name = value == null ? "" : value.trim();
                        if (name.isEmpty()) {
                            return false;
                        }
                        final android.app.ProgressDialog progress =
                                new android.app.ProgressDialog(MainActivity.this);
                        progress.setTitle("Building language pack");
                        progress.setMessage("Translating UI strings...");
                        progress.setCancelable(false);
                        progress.show();
                        LanguagePackBuilder.build(MainActivity.this, name,
                                new LanguagePackBuilder.Progress() {
                                    @Override
                                    public void onProgress(String message) {
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                progress.setMessage(message);
                                            }
                                        });
                                    }
                                },
                                new LanguagePackBuilder.Callback() {
                                    @Override
                                    public void onResult(boolean ok, String code, String message) {
                                        runOnUiThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                progress.dismiss();
                                                Toast.makeText(MainActivity.this, message,
                                                        Toast.LENGTH_LONG).show();
                                                if (ok) {
                                                    recreate();
                                                }
                                            }
                                        });
                                    }
                                });
                        return true;
                    }
                });
    }

    private LinearLayout settingsCard(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, 16));
        card.setPadding(dp(14), dp(2), dp(14), dp(2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.setMargins(0, dp(2), 0, dp(3));
        parent.addView(card, params);
        return card;
    }

    private void addSettingsSection(LinearLayout parent, String title) {
        TextView label = new TextView(this);
        label.setText(LanguageManager.t(this, title));
        label.setTextColor(DIM);
        label.setTextSize(10.5f);
        label.setPadding(dp(6), dp(18), dp(6), dp(6));
        parent.addView(label, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private TextView addSettingsRow(LinearLayout card, String iconName, String label, String value,
                                View.OnClickListener listener) {
        return addSettingsRow(card, iconName, label, value, listener, false);
    }

    private TextView addSettingsRow(LinearLayout card, String iconName, String label, String value,
                                View.OnClickListener listener, boolean last) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(13), 0, dp(13));
        if (listener != null) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(listener);
        }
        ImageView icon = vectorIcon(iconName, MUTED);
        row.addView(icon, iconSize(dp(19), dp(12)));
        TextView labelView = new TextView(this);
        labelView.setText(LanguageManager.t(this, label));
        labelView.setTextColor(TEXT);
        labelView.setTextSize(13.5f);
        row.addView(labelView, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView valueView = new TextView(this);
        valueView.setText(LanguageManager.t(this, value));
        valueView.setTextColor(MUTED);
        valueView.setTextSize(10.5f);
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        valueView.setPadding(dp(8), 0, dp(8), 0);
        row.addView(valueView, new LinearLayout.LayoutParams(WRAP, WRAP));
        if (listener != null) {
            ImageView chevron = vectorIcon("ic_chevron_right", DIM);
            row.addView(chevron, iconSize(dp(16), 0));
        }
        card.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        if (!last) {
            View divider = new View(this);
            divider.setBackgroundColor(DIVIDER);
            card.addView(divider, new LinearLayout.LayoutParams(MATCH, 1));
        }
        return valueView;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.9.0";
        }
    }

    private void clearRunLogs() {
        AgentKit.confirm(this, "Cleaning up the running log?",
                "Delete only the current working area .agent/runs , the file, dialogue or source code is not deleted.",
                "Cleaning", true, new Runnable() {
                    @Override
                    public void run() {
                        int deleted = 0;
                        try {
                            File runs = workspace.resolve(".agent/runs");
                            File[] files = runs.listFiles();
                            if (files != null) {
                                for (File file : files) {
                                    if (file.delete()) {
                                        deleted++;
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }
                        Toast.makeText(MainActivity.this, "Cleared " + deleted + " Temporary document",
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void openHooksConfig() {
        try {
            File file = workspace.resolve(HookManager.CONFIG_PATH);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            if (!file.isFile()) {
                String template = "{\n"
                        + "  \"enabled\": true,\n"
                        + "  \"timeout_s\": 5,\n"
                        + "  \"pre_tool_use\": [],\n"
                        + "  \"post_tool_use\": [],\n"
                        + "  \"stop\": []\n"
                        + "}\n";
                java.io.FileOutputStream output = new java.io.FileOutputStream(file);
                try {
                    output.write(template.getBytes("UTF-8"));
                } finally {
                    output.close();
                }
            }
            openEditor(HookManager.CONFIG_PATH, engine.currentWorkspacePath());
        } catch (Exception e) {
            Toast.makeText(this, "Could not open Hooks Configure:"
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showGlobalSettings() {
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(16), dp(4), dp(16), dp(20));
        content.addView(AgentKit.hint(this,
                        "API Key Share all conversations; models, workspaces and parameters are configured independently in 'session settings'."),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        content.addView(AgentKit.label(this, "API Key"),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        final EditText keyInput = AgentKit.field(this, prefs.apiKey(), "Paste API Key", false);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (Build.VERSION.SDK_INT >= 26) {
            keyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        content.addView(keyInput, new LinearLayout.LayoutParams(MATCH, WRAP));
        content.addView(AgentKit.toggleRow(this, "Show API Key",
                        "Show only temporarily on this interface", false, new AgentKit.ToggleHandler() {
                            @Override
                            public void onToggle(boolean checked) {
                                int variation = checked
                                        ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                        : InputType.TYPE_TEXT_VARIATION_PASSWORD;
                                keyInput.setInputType(InputType.TYPE_CLASS_TEXT | variation);
                                keyInput.setSelection(keyInput.getText().length());
                            }
                        }),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        content.addView(AgentKit.hint(this,
                        "Key Only private directories are kept on this machine and do not write codes, logs or dialogue files."),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        LinearLayout footer = AgentKit.row(this);
        footer.setGravity(Gravity.END);
        footer.setPadding(dp(16), dp(8), dp(16), dp(16));
        Button clear = AgentKit.secondary(this, "Clear");
        footer.addView(clear, new LinearLayout.LayoutParams(WRAP, WRAP));
        Button save = AgentKit.primary(this, "Save");
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        saveParams.setMargins(dp(8), 0, 0, 0);
        footer.addView(save, saveParams);
        final Dialog dialog = AgentKit.page(this, "Global Settings", scroll, footer);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                keyInput.setText("");
                prefs.saveApiKey("");
                updateStatus();
                Toast.makeText(MainActivity.this, "API Key Cleared",
                        Toast.LENGTH_SHORT).show();
            }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.saveApiKey(keyInput.getText().toString().trim());
                dialog.dismiss();
                updateStatus();
                Toast.makeText(MainActivity.this, "API Key Saved",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String baseHost(String url) {
        try {
            java.net.URL parsed = new java.net.URL(url);
            String path = parsed.getPath() == null ? "" : parsed.getPath();
            if (path.endsWith("/chat/completions")) {
                path = path.substring(0, path.length() - "/chat/completions".length());
            }
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return parsed.getHost() + path;
        } catch (Exception e) {
            return url == null || url.trim().isEmpty() ? "-" : url;
        }
    }

    private String currentModelProfileSummary() {
        Conversation conversation = engine.currentConversation();
        if (conversation == null) {
            return "-";
        }
        return prefs.modelProfile(conversation.baseUrl, conversation.model).shortSummary();
    }

    private String modelServicesSummary() {
        List<ModelService> services = prefs.services();
        ModelService active = prefs.service(prefs.activeServiceId());
        return services.size() + " Services"
                + (active == null ? "" : " · " + active.displayName());
    }

    /** Gateway list: multiple services can coexist, each keeping its own key and models. */
    private void showModelService() {
        if (engine.isRunning()) {
            Toast.makeText(this, "In the course of the task, stop and modify the model service", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(18), dp(4), dp(18), dp(16));
        content.addView(formHint("You can keep multiple model services at the same time, each with its own address and Key; "
                        + "The dialogue is always interchangeable and not covered."),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        final Dialog[] holder = new Dialog[1];
        final List<ModelService> services = prefs.services();
        if (services.isEmpty()) {
            content.addView(formHint("No model service exists. Add a backfill address and Key, "
                            + "Dot 'Get Model' to pull the list."),
                    new LinearLayout.LayoutParams(MATCH, WRAP));
        } else {
            String activeId = prefs.activeServiceId();
            for (final ModelService service : services) {
                String prefix = service.id.equals(activeId) ? "Current · " : "";
                addActionRow(content, service.displayName(), prefix + service.host()
                                + " · " + ModelProtocols.shortLabel(service.protocol)
                                + " · " + service.keyState() + " · "
                                + prefs.catalogModels(service.baseUrl).size() + " Model",
                        new Runnable() {
                            @Override
                            public void run() {
                                if (holder[0] != null) {
                                    holder[0].dismiss();
                                }
                                showModelServiceEditor(service.id);
                            }
                        });
            }
        }
        addActionRow(content, "Add Model Service", "New Address With Key, Can be retained together with other services",
                new Runnable() {
                    @Override
                    public void run() {
                        if (holder[0] != null) {
                            holder[0].dismiss();
                        }
                        showModelServiceEditor("");
                    }
                });
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        holder[0] = AgentKit.page(this, "Model services", scroll);
    }

    private void showModelServiceEditor(final String serviceId) {
        if (engine.isRunning()) {
            Toast.makeText(this, "In the course of the task, stop and modify the model service", Toast.LENGTH_SHORT).show();
            return;
        }
        final ModelService existing = prefs.service(serviceId);
        final ModelService service = existing == null
                ? ModelService.create("", ModelCatalog.DEFAULT_BASE, "")
                : existing.copy();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(4), dp(18), dp(0));
        if (Build.VERSION.SDK_INT >= 26) {
            form.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        form.addView(formHint(existing == null
                        ? "Adds a model service. Address to /v1 Until now; only domain names are filled in automatically /v1. "
                        : "Modify the address or address of this service Key; The list of models below it will be kept."),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        form.addView(formLabel("Name (optional)"), new LinearLayout.LayoutParams(MATCH, WRAP));
        final EditText nameInput = textInput(service.name,
                "For example: newapi", InputType.TYPE_TEXT_VARIATION_NORMAL);
        form.addView(nameInput, new LinearLayout.LayoutParams(MATCH, WRAP));

        form.addView(formLabel("Interface protocol"), new LinearLayout.LayoutParams(MATCH, WRAP));
        final String[] protocolLabels = {"Automatic (by Model)", "OpenAI Chat Completions",
                "OpenAI Responses", "Anthropic Messages"};
        final String[] protocolValues = {ModelProtocols.AUTO, ModelProtocols.CHAT,
                ModelProtocols.RESPONSES, ModelProtocols.MESSAGES};
        final String[] protocolChoice = {ModelProtocols.normalize(service.protocol)};
        final AgentKit.ValueRow protocolRow = AgentKit.selectRow(this, "Agreements",
                ModelProtocols.label(protocolChoice[0]), null);
        protocolRow.view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChoiceSheet("Interface protocol", protocolLabels, protocolValues,
                        protocolChoice[0], protocolRow, protocolChoice,
                        new Runnable() {
                            @Override
                            public void run() {
                                service.protocol = ModelProtocols.normalize(protocolChoice[0]);
                            }
                        });
            }
        });
        form.addView(protocolRow.view, new LinearLayout.LayoutParams(MATCH, WRAP));
        form.addView(formHint("Automatic:claude Let's go. Anthropic Messages, gpt-5/6 Let's go. OpenAI Responses, "
                        + "The rest of you go. Chat Completions; The gateway will be backed back and the actual protocol will be remembered when it is not supported."),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        form.addView(formLabel("Interface Address"), new LinearLayout.LayoutParams(MATCH, WRAP));
        final EditText baseInput = textInput(service.baseUrl,
                ModelCatalog.DEFAULT_BASE, InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(baseInput, new LinearLayout.LayoutParams(MATCH, WRAP));

        form.addView(formLabel("API Key"), new LinearLayout.LayoutParams(MATCH, WRAP));
        final EditText keyInput = AgentKit.field(this, service.apiKey, "Paste API Key", false);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (Build.VERSION.SDK_INT >= 26) {
            keyInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        }
        form.addView(keyInput, new LinearLayout.LayoutParams(MATCH, WRAP));
        form.addView(AgentKit.toggleRow(this, "Show API Key",
                        "Show only temporarily on this interface", false, new AgentKit.ToggleHandler() {
                            @Override
                            public void onToggle(boolean checked) {
                                int variation = checked
                                        ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                        : InputType.TYPE_TEXT_VARIATION_PASSWORD;
                                keyInput.setInputType(InputType.TYPE_CLASS_TEXT | variation);
                                keyInput.setSelection(keyInput.getText().length());
                            }
                        }),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        final TextView status = new TextView(this);
        status.setTextSize(10.5f);
        status.setPadding(dp(2), dp(12), dp(2), dp(2));
        form.addView(status, new LinearLayout.LayoutParams(MATCH, WRAP));
        updateServiceStatus(status, service);

        final Runnable[] renderModelsRef = new Runnable[1];
        final Dialog[] dialogRef = new Dialog[1];
        LinearLayout modelHeader = AgentKit.row(this);
        TextView modelTitle = formLabel("Model");
        modelTitle.setPadding(0, 0, 0, 0);
        modelHeader.addView(modelTitle, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView refreshModels = new TextView(this);
        refreshModels.setText("Refresh");
        refreshModels.setTextColor(SUCCESS);
        refreshModels.setTextSize(11f);
        refreshModels.setTypeface(Typeface.DEFAULT_BOLD);
        refreshModels.setPadding(dp(14), dp(6), dp(4), dp(6));
        refreshModels.setClickable(true);
        refreshModels.setFocusable(true);
        refreshModels.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!saveServiceFromForm(service, nameInput, baseInput, keyInput, status)) {
                    return;
                }
                fetchModelsForService(service, status, renderModelsRef[0], dialogRef[0]);
            }
        });
        modelHeader.addView(refreshModels, new LinearLayout.LayoutParams(WRAP, WRAP));
        form.addView(modelHeader, new LinearLayout.LayoutParams(MATCH, WRAP));
        final LinearLayout modelList = new LinearLayout(this);
        modelList.setOrientation(LinearLayout.VERTICAL);
        form.addView(modelList, new LinearLayout.LayoutParams(MATCH, WRAP));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(form, new ScrollView.LayoutParams(MATCH, WRAP));

        LinearLayout footer = AgentKit.row(this);
        footer.setGravity(Gravity.END);
        footer.setPadding(dp(16), dp(8), dp(16), dp(16));
        Button delete = AgentKit.ghost(this, "Delete", ERROR);
        footer.addView(delete, new LinearLayout.LayoutParams(WRAP, WRAP));
        Button save = AgentKit.secondary(this, "Save");
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        saveParams.setMargins(dp(8), 0, 0, 0);
        footer.addView(save, saveParams);
        Button fetch = AgentKit.primary(this, "Get Models");
        LinearLayout.LayoutParams fetchParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        fetchParams.setMargins(dp(8), 0, 0, 0);
        footer.addView(fetch, fetchParams);
        final Dialog dialog = AgentKit.page(this, existing == null ? "Add Model Service" : "Edit Model Service",
                scroll, footer);
        dialogRef[0] = dialog;

        final Runnable renderModels = new Runnable() {
            @Override
            public void run() {
                modelList.removeAllViews();
                List<String> models = prefs.catalogModels(service.baseUrl);
                if (models.isEmpty()) {
                    modelList.addView(formHint("No list of models exists. Right-hand 'Brown' draws the latest list."),
                            new LinearLayout.LayoutParams(MATCH, WRAP));
                    return;
                }
                Conversation conversation = engine.currentConversation();
                String currentService = conversationServiceId(conversation);
                if (!service.id.isEmpty() && service.id.equals(currentService)) {
                    modelList.addView(formHint("A dot model allows for the current dialogue to be switched to;"
                                    + "Context and vision of each model/The ability to think can be adjusted in the 'model capacity.'"),
                            new LinearLayout.LayoutParams(MATCH, WRAP));
                }
                for (String id : models) {
                    addCatalogModelRow(modelList, service, id, conversation, dialog);
                }
            }
        };
        renderModelsRef[0] = renderModels;
        renderModels.run();

        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!saveServiceFromForm(service, nameInput, baseInput, keyInput, status)) {
                    return;
                }
                updateServiceStatus(status, service);
                renderModels.run();
                markSettingsDirty();
                Toast.makeText(MainActivity.this, "Model service saved",
                        Toast.LENGTH_SHORT).show();
            }
        });

        fetch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!saveServiceFromForm(service, nameInput, baseInput, keyInput, status)) {
                    return;
                }
                fetchModelsForService(service, status, renderModels, dialog);
            }
        });

        delete.setVisibility(existing != null && prefs.services().size() > 1
                ? View.VISIBLE : View.GONE);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AgentKit.confirm(MainActivity.this, "Delete Model Service",
                        "Delete" + service.displayName() + "'and Key. "
                                + "The saved dialogue will not be deleted.", "Delete", true,
                        new Runnable() {
                            @Override
                            public void run() {
                                prefs.deleteService(service.id);
                                markSettingsDirty();
                                dialog.dismiss();
                                ModelService fallback = prefs.services().isEmpty()
                                        ? null : prefs.services().get(0);
                                Conversation conversation = engine.currentConversation();
                                if (fallback != null && conversation != null
                                        && sameBase(service.baseUrl, conversation.baseUrl)) {
                                    String model = chooseModelForService(fallback.baseUrl,
                                            conversation);
                                    if (!model.isEmpty()) {
                                        applyServiceModel(fallback, model);
                                    }
                                }
                                showModelService();
                            }
                        });
            }
        });
    }

    private boolean saveServiceFromForm(ModelService service, EditText nameInput,
                                        EditText baseInput, EditText keyInput,
                                        TextView status) {
        String base;
        try {
            base = ModelCatalog.normalizeBase(baseInput.getText().toString());
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }
        service.name = nameInput.getText().toString().trim();
        service.baseUrl = base;
        service.apiKey = keyInput.getText().toString().trim();
        baseInput.setText(base);
        prefs.clearProtocolCacheAll();
        prefs.saveService(service);
        if (prefs.activeServiceId().isEmpty()
                || service.id.equals(prefs.activeServiceId())) {
            prefs.setActiveServiceId(service.id);
        }
        Conversation conversation = engine.currentConversation();
        if (conversation != null && !engine.isRunning()
                && (service.id.equals(conversationServiceId(conversation))
                || sameBase(service.baseUrl, conversation.baseUrl))) {
            engine.updateConversationSettings(conversation.workspace, service.baseUrl,
                    conversation.model, conversation.temperature, conversation.maxTokens,
                    conversation.thinking, conversation.contextTokens,
                    conversation.maxToolRounds, conversation.agentProfile, service.id);
        }
        updateServiceStatus(status, service);
        return true;
    }

    private void fetchModelsForService(final ModelService service, final TextView status,
                                       final Runnable onDone, final Dialog dialog) {
        final String key = service.apiKey == null || service.apiKey.trim().isEmpty()
                ? prefs.apiKey() : service.apiKey.trim();
        if (key.isEmpty()) {
            status.setTextColor(ERROR);
            status.setText("Please fill in first. API Key");
            return;
        }
        status.setTextColor(MUTED);
        status.setText("Connecting model services and fetching model lists...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ModelCatalog.FetchResult result =
                        ModelCatalog.fetch(service.baseUrl, key);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (!result.ok()) {
                            status.setTextColor(ERROR);
                            status.setText(result.error);
                            return;
                        }
                        prefs.saveCatalog(service.baseUrl, result.models);
                        updateServiceStatus(status, service);
                        status.setTextColor(SUCCESS);
                        status.setText("Retrieved " + result.models.size() + " Model"
                                + (result.hidden > 0
                                ? ", Hidden " + result.hidden + " A known unusable model" : ""));
                        if (onDone != null) {
                            onDone.run();
                        }
                        Conversation conversation = engine.currentConversation();
                        boolean inUse = service.id.equals(prefs.activeServiceId())
                                || (conversation != null
                                && sameBase(service.baseUrl, conversation.baseUrl));
                        if (inUse && !engine.isRunning()) {
                            String model = chooseModelForService(service.baseUrl, conversation);
                            if (!model.isEmpty()) {
                                applyServiceModel(service, model);
                            }
                        }
                        Toast.makeText(MainActivity.this,
                                "Model list updated:" + result.models.size() + " individual",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private void updateServiceStatus(TextView status, ModelService service) {
        List<String> models = prefs.catalogModels(service.baseUrl);
        long fetchedAt = prefs.catalogFetchedAt(service.baseUrl);
        if (models.isEmpty()) {
            status.setTextColor(MUTED);
            status.setText("No model list yet retrieved");
            return;
        }
        String time = fetchedAt <= 0 ? "" : " · "
                + new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(new java.util.Date(fetchedAt));
        status.setTextColor(SUCCESS);
        status.setText("Cache " + models.size() + " Model" + time);
    }

    private String chooseModelForService(String base, Conversation conversation) {
        List<String> models = prefs.catalogModels(base);
        if (models.isEmpty()) {
            return "";
        }
        if (conversation != null && models.contains(conversation.model)) {
            return conversation.model;
        }
        if (models.contains(prefs.model())) {
            return prefs.model();
        }
        String[] preferred = {"deepseek-v4-pro", "qwen3.8-max", "glm-5.3"};
        for (String candidate : preferred) {
            if (models.contains(candidate)) {
                return candidate;
            }
        }
        return models.get(0);
    }

    private boolean sameBase(String left, String right) {
        try {
            return ModelCatalog.normalizeBase(left).equals(ModelCatalog.normalizeBase(right));
        } catch (Exception e) {
            return left != null && left.equals(right);
        }
    }

    private String conversationServiceId(Conversation conversation) {
        if (conversation == null) {
            return "";
        }
        ModelService service = prefs.service(conversation.modelService);
        if (service == null) {
            service = prefs.serviceForBase(conversation.baseUrl);
        }
        return service == null ? "" : service.id;
    }

    private void applyServiceModel(ModelService service, String model) {
        if (service == null || model == null || model.trim().isEmpty()) {
            return;
        }
        String id = model.trim();
        prefs.saveService(service);
        prefs.setActiveServiceId(service.id);
        prefs.save(service.baseUrl, id,
                service.apiKey == null || service.apiKey.isEmpty()
                        ? prefs.apiKey() : service.apiKey);
        Conversation conversation = engine.currentConversation();
        if (conversation == null || engine.isRunning()) {
            return;
        }
        ModelProfile profile = prefs.modelProfile(service.baseUrl, id);
        String thinking = profile.thinking ? AppPrefs.THINKING_AUTO : AppPrefs.THINKING_OFF;
        int rounds = Math.max(8, Math.min(24, conversation.maxToolRounds));
        if (engine.updateConversationSettings(conversation.workspace, service.baseUrl, id,
                conversation.temperature, profile.maxOutputTokens, thinking,
                profile.contextTokens, rounds, conversation.agentProfile, service.id)) {
            engine.setModelSource(Conversation.SOURCE_CLOUD);
            prefs.saveModelParams(conversation.temperature, profile.maxOutputTokens,
                    thinking, profile.contextTokens, rounds);
            renderAll();
            updateStatus();
            markSettingsDirty();
        }
    }

    private void showModelCapabilities(final String base, final String model) {
        if (engine.isRunning()) {
            Toast.makeText(this, "In the course of the mission, stop and modify the model capability", Toast.LENGTH_SHORT).show();
            return;
        }
        final ModelProfile profile = prefs.modelProfile(base, model);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(4), dp(18), dp(0));
        form.addView(formHint("These are just the things that you can do. " + model + " Effective. Context and output ceilings are exceeded"
                        + "When models are real, the gateway directly reports errors."),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        final int[] contextPresets = {64000, 128000, 200000, 256000, 400000, 1000000};
        final String[] contextLabels = {"64K", "128K", "200K", "256K", "400K", "1M"};
        final List<String> contextLabelList = new ArrayList<String>();
        final List<String> contextValueList = new ArrayList<String>();
        for (int i = 0; i < contextPresets.length; i++) {
            contextLabelList.add(contextLabels[i]);
            contextValueList.add(String.valueOf(contextPresets[i]));
        }
        if (!contextValueList.contains(String.valueOf(profile.contextTokens))) {
            contextLabelList.add(0, profile.contextLabel() + "(Current)");
            contextValueList.add(0, String.valueOf(profile.contextTokens));
        }
        final String[] contextValues = new String[1];
        contextValues[0] = String.valueOf(profile.contextTokens);
        final AgentKit.ValueRow contextRow = AgentKit.selectRow(this, "Context Window",
                profile.contextLabel(), null);
        contextRow.view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChoiceSheet("Context Window",
                        contextLabelList.toArray(new String[0]),
                        contextValueList.toArray(new String[0]),
                        contextValues[0], contextRow, contextValues);
            }
        });
        form.addView(contextRow.view, new LinearLayout.LayoutParams(MATCH, WRAP));

        final int[] outputPresets = {4096, 8192, 16384, 32768, 65536};
        final String[] outputLabels = {"4K", "8K", "16K", "32K", "64K"};
        final List<String> outputLabelList = new ArrayList<String>();
        final List<String> outputValueList = new ArrayList<String>();
        for (int i = 0; i < outputPresets.length; i++) {
            outputLabelList.add(outputLabels[i]);
            outputValueList.add(String.valueOf(outputPresets[i]));
        }
        if (!outputValueList.contains(String.valueOf(profile.maxOutputTokens))) {
            outputLabelList.add(0, profile.outputLabel() + "(Current)");
            outputValueList.add(0, String.valueOf(profile.maxOutputTokens));
        }
        final String[] outputValues = new String[1];
        outputValues[0] = String.valueOf(profile.maxOutputTokens);
        final AgentKit.ValueRow outputRow = AgentKit.selectRow(this, "Max output",
                profile.outputLabel(), null);
        outputRow.view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChoiceSheet("Max output tokens",
                        outputLabelList.toArray(new String[0]),
                        outputValueList.toArray(new String[0]),
                        outputValues[0], outputRow, outputValues);
            }
        });
        form.addView(outputRow.view, new LinearLayout.LayoutParams(MATCH, WRAP));

        form.addView(formLabel("Interface protocol"), new LinearLayout.LayoutParams(MATCH, WRAP));
        final String[] capsProtocolLabels = {"Following Service (Automated)", "OpenAI Chat Completions",
                "OpenAI Responses", "Anthropic Messages"};
        final String[] capsProtocolValues = {ModelProtocols.AUTO, ModelProtocols.CHAT,
                ModelProtocols.RESPONSES, ModelProtocols.MESSAGES};
        final String[] capsProtocolChoice = {ModelProtocols.normalize(profile.protocol)};
        final AgentKit.ValueRow capsProtocolRow = AgentKit.selectRow(this, "Agreements",
                ModelProtocols.label(capsProtocolChoice[0]), null);
        capsProtocolRow.view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChoiceSheet("Interface protocol", capsProtocolLabels, capsProtocolValues,
                        capsProtocolChoice[0], capsProtocolRow, capsProtocolChoice);
            }
        });
        form.addView(capsProtocolRow.view, new LinearLayout.LayoutParams(MATCH, WRAP));
        String effective = ModelProtocols.label(ModelProtocols.resolve(prefs, base, model).protocol);
        form.addView(formHint("Entered into force:" + effective
                        + ". Automatically select the model name for following up services; enforce the agreement after manual selection."),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        final boolean[] tools = {profile.tools};
        form.addView(AgentKit.toggleRow(this, "Tool Call",
                        "The post-closure model is only for dialogue, and no tool definition is received, which prevents the execution of the mission",
                        tools[0], new AgentKit.ToggleHandler() {
                            @Override
                            public void onToggle(boolean checked) {
                                tools[0] = checked;
                            }
                        }),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        final boolean[] vision = {profile.vision};
        form.addView(AgentKit.toggleRow(this, "Visual input",
                        "Models can read images attached to the dialogue",
                        vision[0], new AgentKit.ToggleHandler() {
                            @Override
                            public void onToggle(boolean checked) {
                                vision[0] = checked;
                            }
                        }),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        final boolean[] thinking = {profile.thinking};
        form.addView(AgentKit.toggleRow(this, "Thinking ability.",
                        "Model supports the reasoning process; no reflection parameters sent after closing",
                        thinking[0], new AgentKit.ToggleHandler() {
                            @Override
                            public void onToggle(boolean checked) {
                                thinking[0] = checked;
                            }
                        }),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        final String[] effortLabels = {"Low low", "Medium medium", "High high", "Highest xhigh"};
        final String[] effortChoices = {"low", "medium", "high", "xhigh"};
        final String[] effortValues = {profile.effort};
        String effortLabel = effortLabels[2];
        for (int i = 0; i < effortChoices.length; i++) {
            if (effortChoices[i].equals(effortValues[0])) {
                effortLabel = effortLabels[i];
                break;
            }
        }
        final AgentKit.ValueRow effortRow = AgentKit.selectRow(this, "Think deep",
                effortLabel, null);
        effortRow.view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChoiceSheet("Think deep", effortLabels, effortChoices,
                        effortValues[0], effortRow, effortValues);
            }
        });
        form.addView(effortRow.view, new LinearLayout.LayoutParams(MATCH, WRAP));
        form.addView(formHint("The only way to get a 'thinking ability' and to talk about thinking is to think deep."
                        + "The idea is to send the thought depth to the gateway."),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(form, new ScrollView.LayoutParams(MATCH, WRAP));
        LinearLayout footer = AgentKit.row(this);
        footer.setGravity(Gravity.END);
        footer.setPadding(dp(16), dp(8), dp(16), dp(16));
        Button reset = AgentKit.secondary(this, "Restore Default");
        footer.addView(reset, new LinearLayout.LayoutParams(WRAP, WRAP));
        Button save = AgentKit.primary(this, "Save");
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        saveParams.setMargins(dp(8), 0, 0, 0);
        footer.addView(save, saveParams);
        final Dialog dialog = AgentKit.page(this, "Model capabilities", scroll, footer);

        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.clearModelProfile(base, model);
                dialog.dismiss();
                showModelCapabilities(base, model);
            }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ModelProfile next = new ModelProfile(model);
                next.contextTokens = ModelProfile.clampInt(
                        parseInt(contextValues[0], profile.contextTokens), 8000, 1000000);
                next.maxOutputTokens = ModelProfile.clampInt(
                        parseInt(outputValues[0], profile.maxOutputTokens), 512, 65536);
                next.tools = tools[0];
                next.vision = vision[0];
                next.thinking = thinking[0];
                next.effort = ModelProfile.normalizeEffort(effortValues[0]);
                next.protocol = ModelProtocols.normalize(capsProtocolChoice[0]);
                prefs.clearProtocolCache(base, model);
                prefs.saveModelProfile(base, next);
                Conversation conversation = engine.currentConversation();
                if (conversation != null && !engine.isRunning()) {
                    String thinking = next.thinking
                            ? AppPrefs.THINKING_AUTO : AppPrefs.THINKING_OFF;
                    int rounds = Math.max(8, Math.min(24, conversation.maxToolRounds));
                    if (engine.updateConversationSettings(conversation.workspace,
                            base, model, conversation.temperature,
                            next.maxOutputTokens, thinking, next.contextTokens,
                            rounds, conversation.agentProfile)) {
                        prefs.saveModelParams(conversation.temperature,
                                next.maxOutputTokens, thinking, next.contextTokens, rounds);
                    }
                }
                dialog.dismiss();
                renderAll();
                updateStatus();
                markSettingsDirty();
                Toast.makeText(MainActivity.this, "Model capability saved",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void showConversationSettings() {
        if (engine.isRunning()) {
            Toast.makeText(this, "Task is being performed, stop and change session settings", Toast.LENGTH_SHORT).show();
            return;
        }
        final Conversation current = engine.currentConversation();
        if (current == null) {
            return;
        }
        Capabilities caps = new Capabilities(engine.baseWorkspace());
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(4), dp(18), 0);
        if (Build.VERSION.SDK_INT >= 26) {
            form.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        form.addView(formHint("These settings affect only the current dialogue;API Key Common in global settings."),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        form.addView(formLabel("Workspace"), new LinearLayout.LayoutParams(MATCH, WRAP));
        final String[] workspaceChoice = {current.workspace};
        final AgentKit.ValueRow workspaceRow = AgentKit.selectRow(this, "Working Directory",
                workspaceLabel(workspaceChoice[0]), null);
        workspaceRow.view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWorkspacePicker(workspaceChoice, workspaceRow.valueView);
            }
        });
        form.addView(workspaceRow.view, new LinearLayout.LayoutParams(MATCH, WRAP));
        form.addView(formHint("Documentation tools,Termux and build both root in this directory; optional projects/mobile-agent The plasma directory."),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        form.addView(formLabel("Agent"), new LinearLayout.LayoutParams(MATCH, WRAP));
        final List<String> agentNames = new ArrayList<>();
        agentNames.add("Default");
        agentNames.addAll(caps.listAgentNames());
        final String[] agentValues = new String[agentNames.size()];
        agentValues[0] = "";
        for (int i = 1; i < agentNames.size(); i++) {
            agentValues[i] = agentNames.get(i);
        }
        final String[] agentChoice = {current.agentProfile == null ? "" : current.agentProfile};
        final AgentKit.ValueRow agentRow = AgentKit.selectRow(this, "Current Agent",
                agentChoice[0].isEmpty() ? "Default" : agentChoice[0], null);
        agentRow.view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChoiceSheet("Selection Agent", agentNames.toArray(new String[0]),
                        agentValues, agentChoice[0], agentRow, agentChoice);
            }
        });
        form.addView(agentRow.view, new LinearLayout.LayoutParams(MATCH, WRAP));

        form.addView(formLabel("Local Pre-processing"), new LinearLayout.LayoutParams(MATCH, WRAP));
        final String[] preprocessLabels = {"Close", "Rules only", "Rule + Small local model"};
        final String[] preprocessValues = {Conversation.PREPROCESS_OFF,
                Conversation.PREPROCESS_RULES, Conversation.PREPROCESS_MODEL};
        final String[] preprocessChoice = {current.preprocessMode};
        String preprocessLabel = preprocessLabels[1];
        for (int i = 0; i < preprocessValues.length; i++) {
            if (preprocessValues[i].equals(preprocessChoice[0])) {
                preprocessLabel = preprocessLabels[i];
                break;
            }
        }
        final AgentKit.ValueRow preprocessRow = AgentKit.selectRow(this,
                "Preprocess before sending", preprocessLabel, null);
        preprocessRow.view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChoiceSheet("Local Pre-processing", preprocessLabels, preprocessValues,
                        preprocessChoice[0], preprocessRow, preprocessChoice,
                        new Runnable() {
                            @Override
                            public void run() {
                                engine.setPreprocessMode(preprocessChoice[0]);
                                if (Conversation.PREPROCESS_MODEL.equals(
                                        preprocessChoice[0])) {
                                    preloadLocalModel();
                                }
                            }
                        });
            }
        });
        form.addView(preprocessRow.view, new LinearLayout.LayoutParams(MATCH, WRAP));
        form.addView(formHint("Rule Zero token, instantaneous completion;model model first loaded approximate 0.5GB, "
                        + "The judgment takes seconds, then it's completely offline."),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        final LocalAi localAi = LocalAi.get(this);
        final AgentKit.ValueRow localModelRow = AgentKit.selectRow(this,
                "Local Model", localAi.statusText(), null);
        localModelRow.view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLocalAi();
            }
        });
        form.addView(localModelRow.view, new LinearLayout.LayoutParams(MATCH, WRAP));

        form.addView(formLabel("Interface Address"), new LinearLayout.LayoutParams(MATCH, WRAP));
        final EditText baseInput = textInput(current.baseUrl,
                "https://api.example.com", InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(baseInput, new LinearLayout.LayoutParams(MATCH, WRAP));

        form.addView(formLabel("Model Name"), new LinearLayout.LayoutParams(MATCH, WRAP));
        final EditText modelInput = textInput(current.model,
                "example-model", InputType.TYPE_TEXT_VARIATION_NORMAL);
        form.addView(modelInput, new LinearLayout.LayoutParams(MATCH, WRAP));

        form.addView(formLabel("Temperature"), new LinearLayout.LayoutParams(MATCH, WRAP));
        final EditText temperatureInput = numberInput(
                String.valueOf(current.temperature), "0 - 2", true);
        form.addView(temperatureInput, new LinearLayout.LayoutParams(MATCH, WRAP));

        form.addView(formLabel("Max output tokens"), new LinearLayout.LayoutParams(MATCH, WRAP));
        final EditText maxTokensInput = numberInput(
                String.valueOf(prefs.liteHarness()
                        ? Math.min(current.maxTokens, 4096) : current.maxTokens),
                "2048 - 65536", false);
        form.addView(maxTokensInput, new LinearLayout.LayoutParams(MATCH, WRAP));

        form.addView(formLabel("Think deep"), new LinearLayout.LayoutParams(MATCH, WRAP));
        final String[] thinkingLabels = {"Close (Faster)", "Automatic (on task)", "Deep thinking."};
        final String[] thinkingValues = {AppPrefs.THINKING_OFF,
                AppPrefs.THINKING_AUTO, AppPrefs.THINKING_ON};
        final String[] thinkingChoice = {current.thinking == null
                ? AppPrefs.THINKING_OFF : current.thinking};
        String thinkingLabel = thinkingLabels[0];
        for (int i = 0; i < thinkingValues.length; i++) {
            if (thinkingValues[i].equals(thinkingChoice[0])) {
                thinkingLabel = thinkingLabels[i];
                break;
            }
        }
        final AgentKit.ValueRow thinkingRow = AgentKit.selectRow(this, "Thinking mode",
                thinkingLabel, null);
        thinkingRow.view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChoiceSheet("Think deep", thinkingLabels, thinkingValues,
                        thinkingChoice[0], thinkingRow, thinkingChoice);
            }
        });
        form.addView(thinkingRow.view, new LinearLayout.LayoutParams(MATCH, WRAP));
        form.addView(formHint("Lite 'Auto' on closed processing in mode; Deep Thinking always opens and sends reasoning_effort=high. "),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        form.addView(formLabel("Context budget (%)token)"), new LinearLayout.LayoutParams(MATCH, WRAP));
        final EditText contextInput = numberInput(
                String.valueOf(prefs.liteHarness()
                        ? Math.min(current.contextTokens, 12000) : current.contextTokens),
                "8000 - 1000000", false);
        form.addView(contextInput, new LinearLayout.LayoutParams(MATCH, WRAP));
        form.addView(formHint("Press the one sent to the model token Estimates;Lite The most active mode 12k, "
                        + "The old tool output will automatically omit and will not delete messages that have been saved from this dialogue."),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        form.addView(formLabel("Tool rotation budget"), new LinearLayout.LayoutParams(MATCH, WRAP));
        final EditText roundsInput = numberInput(
                String.valueOf(prefs.liteHarness()
                        ? Math.min(current.maxToolRounds, 12) : current.maxToolRounds),
                "8 - 24", false);
        form.addView(roundsInput, new LinearLayout.LayoutParams(MATCH, WRAP));
        form.addView(formHint("Lite Default 12 The next round is suspended when the budget is reached and you 'continue' to execute the next batch."
                        + "Run hard maximum once 24 Round or 10 Minutes."),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        form.addView(formLabel("Tool Authorization"), new LinearLayout.LayoutParams(MATCH, WRAP));
        final AgentKit.ValueRow allowedRow = AgentKit.selectRow(this, "Always Allowed Tools",
                engine.alwaysAllowedTools().size() + " individual", null);
        allowedRow.view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAllowedTools();
            }
        });
        form.addView(allowedRow.view, new LinearLayout.LayoutParams(MATCH, WRAP));
        form.addView(formHint("The tool that you choose to 'always allow' when asking for approval is recorded here and can be removed at any time."),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(form, new ScrollView.LayoutParams(MATCH, WRAP));
        LinearLayout footer = AgentKit.row(this);
        footer.setGravity(Gravity.END);
        footer.setPadding(dp(16), dp(8), dp(16), dp(16));
        Button save = AgentKit.primary(this, "Save");
        footer.addView(save, new LinearLayout.LayoutParams(WRAP, WRAP));
        final Dialog dialog = AgentKit.page(this, "Session Settings", scroll, footer);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String base = baseInput.getText().toString().trim();
                String model = modelInput.getText().toString().trim();
                try {
                    base = ModelCatalog.normalizeBase(base);
                } catch (IllegalArgumentException e) {
                    Toast.makeText(MainActivity.this, e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                    baseInput.requestFocus();
                    return;
                }
                if (model.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Please fill in the model name.",
                            Toast.LENGTH_SHORT).show();
                    modelInput.requestFocus();
                    return;
                }
                float temperature;
                int maxTokens;
                int contextTokens;
                int maxRounds;
                try {
                    temperature = Float.parseFloat(
                            temperatureInput.getText().toString().trim());
                } catch (NumberFormatException e) {
                    Toast.makeText(MainActivity.this, "Please fill in the number.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (temperature < 0f || temperature > 2f) {
                    Toast.makeText(MainActivity.this, "The temperature range is... 0 - 2",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    maxTokens = Integer.parseInt(
                            maxTokensInput.getText().toString().trim());
                } catch (NumberFormatException e) {
                    Toast.makeText(MainActivity.this, "Max output tokens Please fill in the integer number.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (maxTokens < 2048 || maxTokens > 65536) {
                    Toast.makeText(MainActivity.this, "tokens The range is... 2048 - 65536",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    contextTokens = Integer.parseInt(
                            contextInput.getText().toString().trim());
                } catch (NumberFormatException e) {
                    Toast.makeText(MainActivity.this, "Please fill in the integer in the context budget",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (contextTokens < 8000 || contextTokens > 1000000) {
                    Toast.makeText(MainActivity.this,
                            "The context budget is: 8000 - 1000000 tokens",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    maxRounds = Integer.parseInt(
                            roundsInput.getText().toString().trim());
                } catch (NumberFormatException e) {
                    Toast.makeText(MainActivity.this, "Type of tool",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (maxRounds < 8 || maxRounds > 24) {
                    Toast.makeText(MainActivity.this, "The tool rotation range is 8 - 24",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                String thinking = thinkingChoice[0];
                String agent = agentChoice[0] == null ? "" : agentChoice[0];
                ModelService matched = prefs.serviceForBase(base);
                String serviceId = matched == null ? current.modelService : matched.id;
                if (matched != null) {
                    prefs.setActiveServiceId(matched.id);
                }
                if (engine.updateConversationSettings(workspaceChoice[0],
                        base, model, temperature, maxTokens, thinking,
                        contextTokens, maxRounds, agent, serviceId)) {
                    workspace = engine.workspace();
                    dialog.dismiss();
                    renderAll();
                    markSettingsDirty();
                    Toast.makeText(MainActivity.this, "Session Settings Saved",
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "Save failed, check parameters",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void showWorkspacePicker(final String[] choice, final TextView valueView) {
        List<String> dirs = engine.baseWorkspace().listWorkspaceDirs(2, 100);
        dirs.remove("toolchain");
        String current = choice[0] == null ? "" : choice[0].trim();
        if (!current.isEmpty() && !dirs.contains(current)) {
            dirs.add(0, current);
        }
        final List<String> options = dirs;
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(16), 0, dp(16), 0);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = AgentKit.column(this);
        scroll.addView(list, new ScrollView.LayoutParams(MATCH, WRAP));
        content.addView(scroll, new LinearLayout.LayoutParams(MATCH, dp(420)));
        LinearLayout.LayoutParams scrollParams =
                new LinearLayout.LayoutParams(MATCH, dp(420));
        scroll.setLayoutParams(scrollParams);
        final Dialog[] holder = new Dialog[1];
        for (int i = 0; i < options.size(); i++) {
            final String option = options.get(i);
            LinearLayout row = AgentKit.row(this);
            row.setPadding(dp(6), dp(12), dp(6), dp(12));
            row.setBackground(rounded(option.equals(current) ? ICON_BG : Color.TRANSPARENT, 10));
            row.setClickable(true);
            row.setFocusable(true);
            ImageView icon = vectorIcon("ic_folder", MUTED);
            row.addView(icon, iconSize(dp(19), dp(10)));
            TextView name = new TextView(this);
            name.setText(workspaceLabel(option));
            name.setTextColor(TEXT);
            name.setTextSize(13f);
            row.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1f));
            if (option.equals(current)) {
                row.addView(vectorIcon("ic_check", TEXT), iconSize(dp(18), 0));
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    choice[0] = option;
                    valueView.setText(workspaceLabel(option));
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                }
            });
            list.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        if (options.isEmpty()) {
            list.addView(AgentKit.hint(this, "No optional workspace directory"),
                    new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        holder[0] = AgentKit.sheet(this, "Select a Workspace", content);
    }

    private void showChoiceSheet(String title, String[] labels, String[] values,
                                 String currentValue, final AgentKit.ValueRow row,
                                 final String[] result) {
        showChoiceSheet(title, labels, values, currentValue, row, result, null);
    }

    private void showChoiceSheet(String title, String[] labels, String[] values,
                                 String currentValue, final AgentKit.ValueRow row,
                                 final String[] result, final Runnable after) {
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(16), 0, dp(16), 0);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = AgentKit.column(this);
        scroll.addView(list, new ScrollView.LayoutParams(MATCH, WRAP));
        content.addView(scroll, new LinearLayout.LayoutParams(MATCH, dp(360)));
        final Dialog[] holder = new Dialog[1];
        for (int i = 0; i < labels.length; i++) {
            final String label = labels[i];
            final String value = values[i];
            LinearLayout item = AgentKit.row(this);
            item.setPadding(dp(6), dp(13), dp(6), dp(13));
            item.setClickable(true);
            item.setFocusable(true);
            TextView name = new TextView(this);
            name.setText(label);
            name.setTextColor(TEXT);
            name.setTextSize(13f);
            item.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1f));
            if (value != null && value.equals(currentValue)
                    || value == null && (currentValue == null || currentValue.isEmpty())) {
                item.addView(vectorIcon("ic_check", TEXT), iconSize(dp(18), 0));
            }
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    result[0] = value;
                    if (row != null) {
                        row.setValue(label);
                    }
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                    if (after != null) {
                        after.run();
                    }
                }
            });
            list.addView(item, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        holder[0] = AgentKit.sheet(this, title, content);
    }

    private void showAllowedTools() {
        final List<String> allowed = engine.alwaysAllowedTools();
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(18), 0, dp(18), 0);
        content.addView(AgentKit.hint(this,
                        "These tools will not be allowed to pop up for clearance in the current dialogue."),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        final Dialog[] holder = new Dialog[1];
        Button edit = AgentKit.primary(this, "Edit allowed tools");
        edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                showToolPicker();
            }
        });
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        editParams.setMargins(0, dp(10), 0, dp(6));
        content.addView(edit, editParams);
        if (allowed.isEmpty()) {
            content.addView(AgentKit.hint(this, "There are no always allowed tools."),
                    new LinearLayout.LayoutParams(MATCH, WRAP));
        } else {
            for (final String name : allowed) {
                LinearLayout row = AgentKit.row(this);
                row.setBackground(rounded(CARD, 13));
                row.setPadding(dp(12), dp(10), dp(8), dp(10));
                row.addView(vectorIcon(ToolPresentation.icon(name), MUTED), iconSize(dp(18), dp(10)));
                TextView label = new TextView(this);
                label.setText(ToolPresentation.label(name) + "  " + name);
                label.setTextColor(TEXT);
                label.setTextSize(12f);
                row.addView(label, new LinearLayout.LayoutParams(0, WRAP, 1f));
                Button remove = AgentKit.ghost(this, "Remove", ERROR);
                remove.setTextSize(10.5f);
                remove.setPadding(dp(8), dp(4), dp(8), dp(4));
                remove.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        engine.removeAlwaysAllowedTool(name);
                        if (holder[0] != null) {
                            holder[0].dismiss();
                        }
                        showAllowedTools();
                    }
                });
                row.addView(remove, new LinearLayout.LayoutParams(WRAP, WRAP));
                LinearLayout.LayoutParams rowParams =
                        new LinearLayout.LayoutParams(MATCH, WRAP);
                rowParams.setMargins(0, dp(6), 0, 0);
                content.addView(row, rowParams);
            }
            Button clear = AgentKit.danger(this, "Clear All");
            clear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    engine.clearAlwaysAllowedTools();
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                    Toast.makeText(MainActivity.this, "Cleared Tool Authorization",
                            Toast.LENGTH_SHORT).show();
                }
            });
            LinearLayout.LayoutParams clearParams =
                    new LinearLayout.LayoutParams(WRAP, WRAP);
            clearParams.setMargins(0, dp(14), 0, 0);
            content.addView(clear, clearParams);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        holder[0] = AgentKit.sheet(this, "Tool Authorization", scroll, 480);
    }

    /** Check this tool to skip approval permanently in this dialogue; no Agent Switches on the tool range. */
    private void showToolPicker() {
        final Set<String> selected = new LinkedHashSet<>(engine.alwaysAllowedTools());
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(18), 0, dp(18), 0);
        content.addView(AgentKit.hint(this,
                        "This tool will not be used for approval in this dialogue after the check. Agent The tool itself does not contain the tool, but it is not the only tool that is used to protect the human being."
                                + "Please come to the 'Capacity and Memory.' → Current Agent'Modify tools. "),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = AgentKit.column(this);
        for (String name : availableToolNames()) {
            android.widget.CheckBox box = new android.widget.CheckBox(this);
            box.setText(ToolPresentation.label(name) + "  " + name);
            box.setTextColor(TEXT);
            box.setTextSize(12f);
            box.setChecked(selected.contains(name));
            final String toolName = name;
            box.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                             boolean isChecked) {
                    if (isChecked) {
                        selected.add(toolName);
                    } else {
                        selected.remove(toolName);
                    }
                }
            });
            list.addView(box, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        scroll.addView(list, new ScrollView.LayoutParams(MATCH, WRAP));
        content.addView(scroll, new LinearLayout.LayoutParams(MATCH, dp(440)));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        Button save = AgentKit.primary(this, "Save Authorization");
        final Dialog[] holder = new Dialog[1];
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                engine.setAlwaysAllowedTools(new ArrayList<>(selected));
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                Toast.makeText(MainActivity.this, "Tool Authorization Saved", Toast.LENGTH_SHORT).show();
                showAllowedTools();
            }
        });
        actions.addView(save, new LinearLayout.LayoutParams(0, WRAP, 1f));
        content.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));
        holder[0] = AgentKit.sheet(this, "Select always allowed tools", content, 520);
    }

    private List<String> availableToolNames() {
        List<String> names = new ArrayList<>();
        JSONArray schemas = ToolDefs.tools();
        for (int i = 0; i < schemas.length(); i++) {
            JSONObject item = schemas.optJSONObject(i);
            JSONObject function = item == null ? null : item.optJSONObject("function");
            String name = function == null ? "" : function.optString("name", "");
            if (!name.isEmpty() && !name.startsWith("mcp_") && !names.contains(name)) {
                names.add(name);
            }
        }
        java.util.Collections.sort(names);
        return names;
    }

    private void showDevice() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(4), dp(18), 0);

        boolean connected = AgentAccessibilityService.getInstance() != null;
        TextView accessStatus = new TextView(this);
        accessStatus.setText(connected ? "Accessibility: connected" : "Accessibility: not operational / Not connected");
        accessStatus.setTextColor(connected ? Color.rgb(126, 224, 192) : ERROR);
        accessStatus.setTextSize(12.5f);
        accessStatus.setTypeface(Typeface.DEFAULT_BOLD);
        accessStatus.setPadding(0, dp(8), 0, dp(4));
        form.addView(accessStatus, new LinearLayout.LayoutParams(MATCH, WRAP));

        Button accessibility = AgentKit.secondary(this, "Open system accessibility settings");
        accessibility.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Could not open accessibility settings", Toast.LENGTH_SHORT).show();
                }
            }
        });
        form.addView(accessibility, new LinearLayout.LayoutParams(WRAP, WRAP));

        TextView accessHint = formHint("Need to enable in the system settingsCRLM The Internet is a tool for the development of the Internet."
                + "Otherwise, you cannot read interfaces, click or enter.");
        form.addView(accessHint, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout deviceToggle = AgentKit.toggleRow(this, "Allow Agent Operation Other App",
                "Opens, clicks and enters other applications after opening", prefs.allowDeviceControl(),
                new AgentKit.ToggleHandler() {
                    @Override
                    public void onToggle(boolean checked) {
                        prefs.setAllowDeviceControl(checked);
                        updateStatus();
                        Toast.makeText(MainActivity.this,
                                checked ? "Device control is on, please ensure accessibility is in place"
                                        : "Device control closed",
                                Toast.LENGTH_SHORT).show();
                    }
                });
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(MATCH, WRAP);
        toggleParams.setMargins(0, dp(12), 0, 0);
        form.addView(deviceToggle, toggleParams);

        TextView deviceHint = formHint("The blogger adds:Agent You can open, click and enter other applications."
                + "Please keep your mobile phone under guard during execution; stop buttons are in the mission notification.");
        form.addView(deviceHint, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView installTitle = new TextView(this);
        installTitle.setText("Installing Help Passwords");
        installTitle.setTextColor(TEXT);
        installTitle.setTextSize(12.5f);
        installTitle.setTypeface(Typeface.DEFAULT_BOLD);
        installTitle.setPadding(0, dp(15), 0, dp(3));
        form.addView(installTitle, new LinearLayout.LayoutParams(MATCH, WRAP));
        DeviceSecretStore secretStore = new DeviceSecretStore(this);
        form.addView(formHint(secretStore.hasSecret()
                        ? "Encrypted saving. Installed APK When password is required, you will only fill in the system installer password box automatically."
                        : "No settings. You can use the following page once txt import;specify automatically removes it after import."),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        Button installAssist = AgentKit.secondary(this, "Installing Help Passwords...");
        installAssist.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showInstallSecretPage();
            }
        });
        LinearLayout.LayoutParams installAssistParams =
                new LinearLayout.LayoutParams(WRAP, WRAP);
        installAssistParams.setMargins(0, dp(6), 0, 0);
        form.addView(installAssist, installAssistParams);

        TextView captureTitle = new TextView(this);
        captureTitle.setText("Screen Read (Campus)");
        captureTitle.setTextColor(TEXT);
        captureTitle.setTextSize(12.5f);
        captureTitle.setTypeface(Typeface.DEFAULT_BOLD);
        captureTitle.setPadding(0, dp(15), 0, dp(3));
        form.addView(captureTitle, new LinearLayout.LayoutParams(MATCH, WRAP));

        boolean captureActive = ScreenCaptureService.isActive();
        form.addView(formHint(captureActive
                        ? "It's on. The system will show screen notifications.Agent Only a frame is taken when the task is needed."
                        : "Close. After opening, Agent This is how you can intercept the current screen for the model in the task."),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        Button captureButton = AgentKit.secondary(this,
                captureActive ? "Stop Screen Read" : "Open Screen Read...");
        captureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ScreenCaptureService.isActive()) {
                    ScreenCaptureService.stop(MainActivity.this);
                    Toast.makeText(MainActivity.this, "Stopping Screen Reading", Toast.LENGTH_SHORT).show();
                } else {
                    requestScreenCapture();
                }
            }
        });
        LinearLayout.LayoutParams captureParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        captureParams.setMargins(0, dp(6), 0, 0);
        form.addView(captureButton, captureParams);
        form.addView(formHint("The system will require a recording authorization when it is activated; notice of the screen will be available for stoppage at any time."
                        + "To avoid the authorization to play every step, the authorization is valid in one session."),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView storageTitle = new TextView(this);
        storageTitle.setText("Storage Permissions");
        storageTitle.setTextColor(TEXT);
        storageTitle.setTextSize(12.5f);
        storageTitle.setTypeface(Typeface.DEFAULT_BOLD);
        storageTitle.setPadding(0, dp(15), 0, dp(3));
        form.addView(storageTitle, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView storageStatus = formHint(workspace.hasStoragePermission()
                ? "Authorized, work area:" + workspace.label()
                : "Unauthorized, currently using the applied internal directory:" + workspace.label());
        form.addView(storageStatus, new LinearLayout.LayoutParams(MATCH, WRAP));

        if (!workspace.hasStoragePermission()) {
            Button grant = AgentKit.secondary(this, "Grant Storage Permissions");
            grant.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    requestStoragePermission();
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(WRAP, WRAP);
            params.setMargins(0, dp(6), 0, 0);
            form.addView(grant, params);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(form, new ScrollView.LayoutParams(MATCH, WRAP));
        AgentKit.page(this, "Devices and Permissions", scroll);
    }

    private void saveInstallSecret(DeviceSecretStore store, EditText input,
                                   Dialog[] holder, boolean enableAutomation) {
        String value = input.getText().toString();
        if (value.isEmpty()) {
            Toast.makeText(this, "Please enter the password first.", Toast.LENGTH_SHORT).show();
            input.requestFocus();
            return;
        }
        if (!store.save(value)) {
            Toast.makeText(this, "Save failed:"
                            + (store.lastError().isEmpty() ? "Keystore Not Available"
                            : store.lastError()),
                    Toast.LENGTH_LONG).show();
            return;
        }
        input.setText("");
        if (enableAutomation) {
            prefs.setAllowInstallAutomation(true);
        }
        hideKeyboard();
        if (holder[0] != null) {
            holder[0].dismiss();
        }
        Toast.makeText(this, enableAutomation
                        ? "Password is encrypted and automatically filled in"
                        : "Password is encrypted",
                Toast.LENGTH_LONG).show();
        showInstallSecretPage();
    }

    private void showInstallSecretPage() {
        final DeviceSecretStore store = new DeviceSecretStore(this);
        final File file = new File(engine.baseWorkspace().baseRoot(),
                ".agent/device-password.txt");
        final Dialog[] holder = new Dialog[1];
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(18), dp(4), dp(18), dp(16));
        content.addView(AgentKit.hint(this,
                        "For locks that the system installer occasionally requests/Install a password. The password is only stored in this machine and encrypted."
                                + "No dialogue, no hints, no logs or any model request will be entered."),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView status = AgentKit.title(this,
                store.hasSecret() ? "Status: Encrypted saved" : "Status: Not set", 13f);
        status.setTextColor(store.hasSecret() ? SUCCESS : MUTED);
        status.setPadding(0, dp(12), 0, 0);
        content.addView(status, new LinearLayout.LayoutParams(MATCH, WRAP));

        content.addView(AgentKit.section(this, "Direct Settings"),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        final EditText secretInput = AgentKit.field(this, "",
                "Enter the cell phone lock/Install password", false);
        secretInput.setSingleLine(true);
        secretInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (Build.VERSION.SDK_INT >= 26) {
            secretInput.setImportantForAutofill(
                    View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        content.addView(secretInput, new LinearLayout.LayoutParams(MATCH, WRAP));
        content.addView(AgentKit.hint(this,
                        "input box does not return; empty immediately after saving. Password is used Android Keystore Encryption,"
                                + "Only used in the password box for the system installer."),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout directActions = AgentKit.row(this);
        directActions.setPadding(0, dp(8), 0, 0);
        Button saveOpen = AgentKit.primary(this, "Save and open auto-filling");
        Button saveOnly = AgentKit.secondary(this, "Save Only");
        directActions.addView(saveOpen, new LinearLayout.LayoutParams(WRAP, WRAP));
        LinearLayout.LayoutParams saveOnlyParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        saveOnlyParams.setMargins(dp(8), 0, 0, 0);
        directActions.addView(saveOnly, saveOnlyParams);
        content.addView(directActions, new LinearLayout.LayoutParams(MATCH, WRAP));

        content.addView(AgentKit.section(this, "or import from a one-time file"),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView path = AgentKit.hint(this, "Optional file:\n" + file.getAbsolutePath()
                + "\nimporting after writing password;after success App The explicit document will be deleted.");
        path.setTextIsSelectable(true);
        content.addView(path, new LinearLayout.LayoutParams(MATCH, WRAP));

        Button template = AgentKit.secondary(this, "Create password file template");
        Button openFile = AgentKit.secondary(this, "Open File Edit");
        Button importFile = AgentKit.secondary(this, "Import and Encrypt From File");
        Button clear = AgentKit.secondary(this, "Clear Saved Password");
        LinearLayout actions = AgentKit.row(this);
        actions.setPadding(0, dp(8), 0, 0);
        actions.addView(template, new LinearLayout.LayoutParams(WRAP, WRAP));
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        openParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(openFile, openParams);
        content.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout fileActions = AgentKit.row(this);
        fileActions.setPadding(0, dp(8), 0, 0);
        fileActions.addView(importFile, new LinearLayout.LayoutParams(WRAP, WRAP));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        clearParams.setMargins(dp(8), 0, 0, 0);
        fileActions.addView(clear, clearParams);
        content.addView(fileActions, new LinearLayout.LayoutParams(MATCH, WRAP));

        content.addView(AgentKit.section(this, "Autofill"),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout toggle = AgentKit.toggleRow(this, "Allow auto-filling installation passwords",
                "Use only in the password box for system installers; default closes", prefs.allowInstallAutomation(),
                new AgentKit.ToggleHandler() {
                    @Override
                    public void onToggle(boolean checked) {
                        if (checked && !store.hasSecret()) {
                            prefs.setAllowInstallAutomation(false);
                            Toast.makeText(MainActivity.this,
                                    "Please import and encrypt the password first", Toast.LENGTH_LONG).show();
                            return;
                        }
                        prefs.setAllowInstallAutomation(checked);
                        Toast.makeText(MainActivity.this,
                                checked ? "Install password automatically completed"
                                        : "Install password to automatically fill closed",
                                Toast.LENGTH_SHORT).show();
                    }
                });
        content.addView(toggle, new LinearLayout.LayoutParams(MATCH, WRAP));
        Button arm = AgentKit.secondary(this, "Prepare for next installation (2 min)");
        LinearLayout.LayoutParams armParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        armParams.setMargins(0, dp(8), 0, 0);
        content.addView(arm, armParams);
        arm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!store.hasSecret()) {
                    Toast.makeText(MainActivity.this, "Please import and encrypt the password first",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (!prefs.allowInstallAutomation()) {
                    Toast.makeText(MainActivity.this, "Please open the 'Authorize Automatic Installation Password' first.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                boolean started = InstallAssistant.start(MainActivity.this, store, 120000L);
                Toast.makeText(MainActivity.this, started
                                ? "Stand by. 2 minutes;now available for computer execution ADB Install"
                                : "Installation support startup failed, check accessibility",
                        Toast.LENGTH_LONG).show();
            }
        });
        content.addView(AgentKit.hint(this,
                        "Limitation: Only the installation package name and password box are identified, submitted only once and attempts are repeated;"
                                + "Huawei Account passwords, security keyboards or lock interfaces may not be automatically filled in."
                                + "'Prepare for the next installation of the computer. ADB or manually installed;App Your own. install_apk It'll be on standby."
                                + "Import the specified file as soon as possible, after import App Will delete it."),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        holder[0] = AgentKit.page(this, "Installing Help Passwords", scroll);
        saveOpen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveInstallSecret(store, secretInput, holder, true);
            }
        });
        saveOnly.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveInstallSecret(store, secretInput, holder, false);
            }
        });
        openFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DeviceSecretStore.writeTemplate(file);
                hideKeyboard();
                openEditor(".agent/device-password.txt", "");
            }
        });
        template.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean ok = DeviceSecretStore.writeTemplate(file);
                Toast.makeText(MainActivity.this, ok
                                ? "Password file prepared:\n" + file.getAbsolutePath()
                                : "Could not create password file, check storage permissions",
                        Toast.LENGTH_LONG).show();
            }
        });
        importFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean ok = store.importFile(file);
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                if (ok) {
                    Toast.makeText(MainActivity.this,
                            "Encrypted password saved and specified files deleted", Toast.LENGTH_LONG).show();
                } else {
                    String detail = store.lastError();
                    Toast.makeText(MainActivity.this,
                            "Import failed: file does not exist, empty or content invalid"
                                    + (detail.isEmpty() ? "" : "(" + detail + ")"),
                            Toast.LENGTH_LONG).show();
                }
                showInstallSecretPage();
            }
        });
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AgentKit.confirm(MainActivity.this, "Clear the installation password?",
                        "The installed password that is encrypted will be deleted and automatically completed will be closed.", "Clear", true,
                        new Runnable() {
                            @Override
                            public void run() {
                                store.clear();
                                prefs.setAllowInstallAutomation(false);
                                if (holder[0] != null) {
                                    holder[0].dismiss();
                                }
                                Toast.makeText(MainActivity.this,
                                        "Cleared installed password", Toast.LENGTH_SHORT).show();
                                showInstallSecretPage();
                            }
                        });
            }
        });
    }

    private LocalAiPage localAiPageController;

    private void showLocalAi() {
        if (localAiPageController == null) {
            localAiPageController = new LocalAiPage(this, new LocalAiPage.Host() {
                @Override
                public void showMenu(View anchor, String title, String[] labels,
                                     String[] icons, int[] colors, Runnable[] actions) {
                    showAgentMenu(anchor, title, labels, icons, colors, actions);
                }

                @Override
                public void showMenu(View anchor, String title, String[] labels,
                                     String[] icons, int[] colors, Runnable[] actions,
                                     boolean wide) {
                    showAgentMenu(anchor, title, labels, icons, colors, actions, wide);
                }

                @Override
                public void toast(CharSequence text) {
                    Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void confirm(String title, String message, String action,
                                    boolean danger, Runnable run) {
                    AgentKit.confirm(MainActivity.this, title, message, action, danger, run);
                }

                @Override
                public void importModel() {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    try {
                        startActivityForResult(intent, REQUEST_MODEL_IMPORT);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Could not open file selection",
                                Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public int dp(float value) {
                    return MainActivity.this.dp(value);
                }

                @Override
                public GradientDrawable rounded(int color, float radiusDp) {
                    return MainActivity.this.rounded(color, radiusDp);
                }

                @Override
                public ImageView icon(String name, int color) {
                    return vectorIcon(name, color);
                }

                @Override
                public LinearLayout.LayoutParams iconSize(int size, int rightMargin) {
                    return MainActivity.this.iconSize(size, rightMargin);
                }
            });
        }
        localAiPageController.show();
    }

    private void showMemory() {
        final Capabilities caps = new Capabilities(engine.baseWorkspace());
        final String memory = caps.loadMemory();
        final Dialog[] holder = new Dialog[1];

        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(16), dp(6), dp(16), dp(26));
        content.addView(AgentKit.hint(this,
                "Long-term memory is kept in the working area .agent/memory.md, It will be automatically brought in with each task."
                        + "Only keep a stable preference, project fact and working method, and not a key or a private original."),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        int count = 0;
        String[] lines = memory.split("\n", -1);
        for (String line : lines) {
            String clean = line.trim();
            if (!clean.isEmpty() && !clean.startsWith("#")) {
                count++;
            }
        }
        LinearLayout summary = AgentKit.card(this);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(MATCH, WRAP);
        summaryParams.setMargins(0, dp(12), 0, 0);
        summary.addView(AgentKit.title(this, count + " A long-term memory.", 13f),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        summary.addView(AgentKit.hint(this, "Scope of the operation: the wholeCRLM, All dialogues shared"),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        content.addView(summary, summaryParams);

        LinearLayout actions = AgentKit.row(this);
        actions.setPadding(0, dp(12), 0, 0);
        Button add = AgentKit.primary(this, "Add memory");
        actions.addView(add, new LinearLayout.LayoutParams(WRAP, WRAP));
        final Button extract = AgentKit.secondary(this, "Auto-refining");
        LinearLayout.LayoutParams extractParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        extractParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(extract, extractParams);
        Button edit = AgentKit.secondary(this, "Edit File");
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        editParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(edit, editParams);
        content.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));

        content.addView(AgentKit.section(this, "Memory Entry"),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        boolean hasEntry = false;
        for (final String line : lines) {
            final String clean = line.trim();
            if (clean.isEmpty() || clean.startsWith("#")) {
                continue;
            }
            hasEntry = true;
            LinearLayout row = AgentKit.row(this);
            row.setBackground(rounded(CARD, 13));
            row.setPadding(dp(12), dp(10), dp(8), dp(10));
            TextView text = new TextView(this);
            text.setText(clean);
            text.setTextColor(TEXT);
            text.setTextSize(11.5f);
            text.setLineSpacing(dp(2), 1f);
            text.setMaxLines(4);
            text.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(text, new LinearLayout.LayoutParams(0, WRAP, 1f));
            Button delete = AgentKit.ghost(this, "Delete", ERROR);
            delete.setTextSize(10.5f);
            delete.setPadding(dp(8), dp(4), dp(8), dp(4));
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (caps.removeMemoryLine(clean)) {
                        Toast.makeText(MainActivity.this, "Removed this memory",
                                Toast.LENGTH_SHORT).show();
                        if (holder[0] != null) {
                            holder[0].dismiss();
                        }
                        showMemory();
                    } else {
                        Toast.makeText(MainActivity.this, "Failed to delete",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });
            row.addView(delete, new LinearLayout.LayoutParams(WRAP, WRAP));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(MATCH, WRAP);
            rowParams.setMargins(0, dp(2), 0, dp(2));
            content.addView(row, rowParams);
        }
        if (!hasEntry) {
            content.addView(AgentKit.hint(this,
                    "There is no long-term memory. You can add it manually or automatically extract it from the current conversation."),
                    new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        holder[0] = AgentKit.page(this, "Long-term memory", scroll);

        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AgentKit.input(MainActivity.this, "Add memory",
                        "A stable preference or project fact...", "", "Save",
                        new AgentKit.InputHandler() {
                            @Override
                            public boolean onInput(String value, Dialog dialog) {
                                if (value.isEmpty()) {
                                    Toast.makeText(MainActivity.this, "Please fill in.",
                                            Toast.LENGTH_SHORT).show();
                                    return false;
                                }
                                boolean added = caps.appendMemory(value);
                                Toast.makeText(MainActivity.this,
                                        added ? "♪ written in long-term memory ♪" : "This memory exists.",
                                        Toast.LENGTH_SHORT).show();
                                if (added) {
                                    dialog.dismiss();
                                    if (holder[0] != null) {
                                        holder[0].dismiss();
                                    }
                                    showMemory();
                                }
                                return false;
                            }
                        });
            }
        });
        edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBaseEditor(Capabilities.MEMORY_PATH);
            }
        });
        extract.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                extract.setEnabled(false);
                extract.setText("In refining...");
                engine.suggestMemories(new AgentEngine.MemoryCallback() {
                    @Override
                    public void onMemories(List<String> suggestions, String error) {
                        extract.setEnabled(true);
                        extract.setText("Auto-refining");
                        if (error != null && !error.isEmpty()) {
                            Toast.makeText(MainActivity.this, error,
                                    Toast.LENGTH_SHORT).show();
                        }
                        if (suggestions == null || suggestions.isEmpty()) {
                            return;
                        }
                        showMemorySuggestions(suggestions, holder[0]);
                    }
                });
            }
        });
    }

    private void showMemorySuggestions(final List<String> suggestions, final Dialog parent) {
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(18), 0, dp(18), 0);
        content.addView(AgentKit.hint(this,
                "The following are extracted from the current dialogue by the model and are then confirmed and then included in the long-term memory."),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        for (String suggestion : suggestions) {
            LinearLayout card = AgentKit.card(this);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(MATCH, WRAP);
            cardParams.setMargins(0, dp(8), 0, 0);
            TextView text = new TextView(this);
            text.setText(suggestion);
            text.setTextColor(TEXT);
            text.setTextSize(12f);
            text.setLineSpacing(dp(2), 1f);
            card.addView(text, new LinearLayout.LayoutParams(MATCH, WRAP));
            content.addView(card, cardParams);
        }
        LinearLayout actions = AgentKit.row(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(16), 0, 0);
        final Dialog[] holder = new Dialog[1];
        Button cancel = AgentKit.secondary(this, "Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(WRAP, WRAP));
        Button write = AgentKit.primary(this, "Write All");
        LinearLayout.LayoutParams writeParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        writeParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(write, writeParams);
        write.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int added = engine.appendMemories(suggestions);
                Toast.makeText(MainActivity.this, "Written " + added + " A memory.",
                        Toast.LENGTH_SHORT).show();
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                if (parent != null) {
                    parent.dismiss();
                }
                showMemory();
            }
        });
        content.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));
        holder[0] = AgentKit.sheet(this, "The memory of the recommendations", content);
    }

    private void showSessionReview() {
        final List<AgentEngine.Entry> entries = engine.getEntries();
        int user = 0;
        int assistant = 0;
        int tools = 0;
        int errors = 0;
        for (AgentEngine.Entry entry : entries) {
            if (entry.type == AgentEngine.Entry.USER) {
                user++;
            } else if (entry.type == AgentEngine.Entry.ASSISTANT) {
                assistant++;
            } else if (entry.type == AgentEngine.Entry.TOOL) {
                tools++;
            } else if (entry.type == AgentEngine.Entry.ERROR) {
                errors++;
            }
        }
        int done = 0;
        int total = 0;
        JSONArray plan = engine.getPlan();
        for (int i = 0; i < plan.length(); i++) {
            JSONObject step = plan.optJSONObject(i);
            if (step != null) {
                total++;
                if ("completed".equals(step.optString("status", ""))) {
                    done++;
                }
            }
        }
        AgentEngine.ContextInfo context = engine.contextInfo();

        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(16), dp(6), dp(16), dp(26));
        LinearLayout stats = AgentKit.card(this);
        stats.addView(AgentKit.title(this, "Session", 13f),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        stats.addView(AgentKit.hint(this,
                user + " Question · " + assistant + " Article · " + tools + " Sub-tool Call · "
                        + errors + " A mistake. · Planned " + done + "/" + total
                        + " · Context " + context.percent + "%"),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        content.addView(stats, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout actions = AgentKit.row(this);
        actions.setPadding(0, dp(12), 0, 0);
        final Button generate = AgentKit.primary(this, "Generate a doubledisk");
        actions.addView(generate, new LinearLayout.LayoutParams(WRAP, WRAP));
        Button history = AgentKit.secondary(this, "History Rewind");
        LinearLayout.LayoutParams historyParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        historyParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(history, historyParams);
        content.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));
        content.addView(AgentKit.hint(this,
                "Rewind will be saved to .agent/reviews/, These include objectives, completion and evidence, risks, incompleteness and next steps."),
                new LinearLayout.LayoutParams(MATCH, WRAP));

        final LinearLayout resultBox = AgentKit.column(this);
        content.addView(resultBox, new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
        final Dialog[] holder = new Dialog[1];
        holder[0] = AgentKit.page(this, "Session Rewind", scroll);

        generate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                generate.setEnabled(false);
                generate.setText("Generating...");
                resultBox.removeAllViews();
                final TextView status = AgentKit.hint(MainActivity.this,
                        "Reading session logs and generating a double disk...");
                resultBox.addView(status, new LinearLayout.LayoutParams(MATCH, WRAP));
                engine.reviewSession(new AgentEngine.ReviewCallback() {
                    @Override
                    public void onReview(String review, String path, String error) {
                        generate.setEnabled(true);
                        generate.setText("Regenerated");
                        if (error != null) {
                            status.setText(error);
                            return;
                        }
                        resultBox.removeAllViews();
                        resultBox.addView(AgentKit.section(MainActivity.this, "Rewind result"),
                                new LinearLayout.LayoutParams(MATCH, WRAP));
                        TextView body = new TextView(MainActivity.this);
                        body.setText(UiText.markdown(review));
                        body.setTextColor(TEXT);
                        body.setTextSize(12.5f);
                        body.setLineSpacing(dp(3), 1f);
                        body.setPadding(dp(14), dp(12), dp(14), dp(12));
                        body.setBackground(rounded(CARD, 14));
                        resultBox.addView(body, new LinearLayout.LayoutParams(MATCH, WRAP));
                        TextView saved = AgentKit.hint(MainActivity.this,
                                "Saved to " + path);
                        LinearLayout.LayoutParams savedParams =
                                new LinearLayout.LayoutParams(MATCH, WRAP);
                        savedParams.setMargins(0, dp(8), 0, 0);
                        resultBox.addView(saved, savedParams);
                        Button open = AgentKit.secondary(MainActivity.this, "Open a Recurring File");
                        open.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                openBaseEditor(path);
                            }
                        });
                        LinearLayout.LayoutParams openParams =
                                new LinearLayout.LayoutParams(WRAP, WRAP);
                        openParams.setMargins(0, dp(10), 0, 0);
                        resultBox.addView(open, openParams);
                        Button evolve = AgentKit.secondary(MainActivity.this, "Deposition as skills");
                        evolve.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                evolve.setEnabled(false);
                                evolve.setText("In refining...");
                                engine.suggestSkillDraft(new AgentEngine.SkillDraftCallback() {
                                    @Override
                                    public void onDraft(String name, String description,
                                                        String body, String error) {
                                        evolve.setEnabled(true);
                                        evolve.setText("Deposition as skills");
                                        if (error != null) {
                                            Toast.makeText(MainActivity.this, error,
                                                    Toast.LENGTH_LONG).show();
                                            return;
                                        }
                                        showSkillDraft(name, description, body);
                                    }
                                });
                            }
                        });
                        LinearLayout.LayoutParams evolveParams =
                                new LinearLayout.LayoutParams(WRAP, WRAP);
                        evolveParams.setMargins(dp(8), dp(10), 0, 0);
                        resultBox.addView(evolve, evolveParams);
                    }
                });
            }
        });
        history.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                showArtifacts(".agent/reviews");
            }
        });
    }

    private void showSkillDraft(String name, String description, String body) {
        final Capabilities caps = new Capabilities(engine.baseWorkspace());
        LinearLayout form = AgentKit.column(this);
        form.setPadding(dp(18), 0, dp(18), 0);
        final EditText nameInput = AgentKit.field(this, name, "Name of skill", false);
        final EditText descInput = AgentKit.field(this, description, "A description.", false);
        final EditText bodyInput = AgentKit.field(this, body, "Skills steps", true);
        bodyInput.setSelection(0);
        form.addView(AgentKit.label(this, "Name"),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        form.addView(nameInput, new LinearLayout.LayoutParams(MATCH, WRAP));
        form.addView(AgentKit.label(this, "Description"),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        form.addView(descInput, new LinearLayout.LayoutParams(MATCH, WRAP));
        form.addView(AgentKit.label(this, "Steps"),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        form.addView(bodyInput, new LinearLayout.LayoutParams(MATCH, WRAP));
        form.addView(AgentKit.hint(this,
                        "Skills will be saved to .agent/skills/<name>/SKILL.md, Yes. Skills ."),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(form, new ScrollView.LayoutParams(MATCH, WRAP));
        LinearLayout content = AgentKit.column(this);
        content.addView(scroll, new LinearLayout.LayoutParams(MATCH, dp(420)));
        LinearLayout actions = AgentKit.row(this);
        actions.setPadding(dp(18), dp(12), dp(18), 0);
        final Dialog[] holder = new Dialog[1];
        Button cancel = AgentKit.secondary(this, "Cancel");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
            }
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(WRAP, WRAP));
        Button create = AgentKit.primary(this, "Create skills");
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        createParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(create, createParams);
        create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String skillName = nameInput.getText().toString().trim();
                String description = descInput.getText().toString().trim();
                String text = bodyInput.getText().toString().trim();
                if (skillName.isEmpty() || text.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Name and step cannot be empty",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                String path = caps.createSkillWithBody(skillName, description, text);
                if (path == null) {
                    Toast.makeText(MainActivity.this, "Create failed. Skills may already exist.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (holder[0] != null) {
                    holder[0].dismiss();
                }
                Toast.makeText(MainActivity.this, "Skills created:" + skillName,
                        Toast.LENGTH_SHORT).show();
                openBaseEditor(path);
            }
        });
        content.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));
        holder[0] = AgentKit.sheet(this, "Deposition as skills", content);
    }

    private FilePages filePages;

    private void openEditor(String relativePath, String workspacePath) {
        Intent intent = new Intent(this, EditorActivity.class);
        intent.putExtra("path", relativePath);
        intent.putExtra("workspace", workspacePath);
        startActivity(intent);
    }

    private void openBaseEditor(String relativePath) {
        openEditor(relativePath, "");
    }


    private void showFiles() {
        if (filePages == null) {
            filePages = new FilePages(this, workspace, new FilePages.Host() {
                @Override
                public boolean canBrowse() {
                    return viewingRunningTask;
                }

                @Override
                public void toast(CharSequence text) {
                    Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void confirm(String title, String message, String action,
                                    boolean danger, Runnable run) {
                    AgentKit.confirm(MainActivity.this, title, message, action, danger, run);
                }

                @Override
                public String currentWorkspacePath() {
                    return engine.currentWorkspacePath();
                }

                @Override
                public void openEditor(String path, String workspacePath) {
                    Intent intent = new Intent(MainActivity.this, EditorActivity.class);
                    intent.putExtra("path", path);
                    intent.putExtra("workspace", workspacePath);
                    startActivity(intent);
                }

                @Override
                public int dp(float value) {
                    return MainActivity.this.dp(value);
                }

                @Override
                public ImageView icon(String name, int color) {
                    return vectorIcon(name, color);
                }

                @Override
                public LinearLayout.LayoutParams iconSize(int size, int rightMargin) {
                    return MainActivity.this.iconSize(size, rightMargin);
                }
            });
        }
        filePages.show();
    }

    private CapabilitiesPage capabilitiesPage;

    private CapabilitiesPage capabilities() {
        if (capabilitiesPage == null) {
            capabilitiesPage = new CapabilitiesPage(this, engine, new CapabilitiesPage.Host() {
                @Override
                public void toast(CharSequence text) {
                    Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void confirm(String title, String message, String action,
                                    boolean danger, Runnable run) {
                    AgentKit.confirm(MainActivity.this, title, message, action, danger, run);
                }

                @Override
                public int dp(float value) {
                    return MainActivity.this.dp(value);
                }

                @Override
                public GradientDrawable rounded(int color, float radiusDp) {
                    return MainActivity.this.rounded(color, radiusDp);
                }

                @Override
                public ImageView icon(String name, int color) {
                    return vectorIcon(name, color);
                }

                @Override
                public LinearLayout.LayoutParams iconSize(int size, int rightMargin) {
                    return MainActivity.this.iconSize(size, rightMargin);
                }

                @Override
                public void showChoiceSheet(String title, String[] labels, String[] values,
                                            String currentValue, AgentKit.ValueRow row,
                                            String[] result) {
                    MainActivity.this.showChoiceSheet(title, labels, values, currentValue, row, result);
                }

                @Override
                public void showChoiceSheet(String title, String[] labels, String[] values,
                                            String currentValue, AgentKit.ValueRow row,
                                            String[] result, Runnable after) {
                    MainActivity.this.showChoiceSheet(title, labels, values, currentValue, row, result, after);
                }

                @Override
                public void openBaseEditor(String path) {
                    MainActivity.this.openBaseEditor(path);
                }

                @Override
                public void showMemory() {
                    MainActivity.this.showMemory();
                }

                @Override
                public void showAllowedTools() {
                    MainActivity.this.showAllowedTools();
                }
            });
        }
        return capabilitiesPage;
    }

    private void showCapabilities() {
        capabilities().show();
    }

    private void showMcpDialog() {
        capabilities().showMcp();
    }

    private String modelFileName(Uri uri) {
        String name = "imported.gguf";
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) {
                        name = new File(value.trim()).getName();
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return name;
    }

    private void showSelfCheck() {
        final Dialog wait = showModelWait("Agent Self-inspection", "Checking workspace, models, privileges and construction environment...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String report;
                try {
                    report = engine.selfCheck();
                } catch (Throwable t) {
                    report = "Self-checking failed:" + (t.getMessage() == null
                            ? t.getClass().getSimpleName() : t.getMessage());
                }
                final String result = report;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (wait.isShowing()) {
                            wait.dismiss();
                        }
                        LinearLayout content = AgentKit.column(MainActivity.this);
                        content.setPadding(dp(16), dp(6), dp(16), dp(20));
                        TextView text = new TextView(MainActivity.this);
                        text.setText(result);
                        text.setTextColor(TEXT);
                        text.setTextSize(11.5f);
                        text.setTypeface(Typeface.MONOSPACE);
                        text.setLineSpacing(dp(2), 1f);
                        text.setTextIsSelectable(true);
                        text.setPadding(dp(12), dp(10), dp(12), dp(10));
                        text.setBackground(rounded(CARD_TOOL, 12));
                        content.addView(text, new LinearLayout.LayoutParams(MATCH, WRAP));
                        ScrollView scroll = new ScrollView(MainActivity.this);
                        scroll.setVerticalScrollBarEnabled(false);
                        scroll.addView(content, new ScrollView.LayoutParams(MATCH, WRAP));
                        AgentKit.page(MainActivity.this, "Agent Self-inspection", scroll);
                    }
                });
            }
        }, "self-check").start();
    }

    private Dialog showModelWait(String title, String message) {
        LinearLayout content = AgentKit.column(this);
        content.setPadding(dp(18), 0, dp(18), 0);
        content.addView(AgentKit.hint(this, message),
                new LinearLayout.LayoutParams(MATCH, WRAP));
        return AgentKit.sheet(this, title, content);
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                updateStatus();
                return;
            }
            try {
                startActivity(new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(
                            Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignored) {
                    Toast.makeText(this, "Could not open all file access settings",
                            Toast.LENGTH_SHORT).show();
                }
            }
            return;
        }
        if (Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            updateStatus();
            return;
        }
        requestedStorage = true;
        requestPermissions(new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, REQUEST_STORAGE);
    }

    private void requestScreenCapture() {
        MediaProjectionManager manager = (MediaProjectionManager)
                getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "Device does not support screen reading", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
        } catch (Exception e) {
            Toast.makeText(this, "Could not close temporary folder: %s", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_IMAGES) {
            Log.i("AgentAttach", "onActivityResult code=" + resultCode
                    + " data=" + (data == null ? "null" : data.toString()));
            if (resultCode == RESULT_OK) {
                handlePickedImages(data);
            }
            return;
        }
        if (requestCode == REQUEST_PICK_FILES) {
            if (resultCode == RESULT_OK) {
                handlePickedFiles(data);
            }
            return;
        }
        if (requestCode == REQUEST_MODEL_IMPORT) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                showLocalAi();
                return;
            }
            Uri uri = data.getData();
            String name = modelFileName(uri);
            try {
                InputStream input = getContentResolver().openInputStream(uri);
                if (input == null) {
                    Toast.makeText(this, "Could not read selected file", Toast.LENGTH_LONG).show();
                    showLocalAi();
                    return;
                }
                final Dialog wait = showModelWait("Import Model",
                        "Copying " + name + ", Please hold. App At the front desk.");
                LocalAi.get(this).copyModel(input, name, new LocalAi.CopyCallback() {
                    @Override
                    public void onDone(File file, String error) {
                        wait.dismiss();
                        if (error != null) {
                            Toast.makeText(MainActivity.this, error,
                                    Toast.LENGTH_LONG).show();
                        } else if (file != null) {
                            Toast.makeText(MainActivity.this,
                                    "Model imported:" + file.getName(), Toast.LENGTH_LONG).show();
                        }
                        showLocalAi();
                    }
                });
            } catch (Exception e) {
                Toast.makeText(this, "Import failed:"
                        + (e.getMessage() == null ? e.getClass().getSimpleName()
                        : e.getMessage()), Toast.LENGTH_LONG).show();
                showLocalAi();
            }
            return;
        }
        if (requestCode != REQUEST_SCREEN_CAPTURE) {
            return;
        }
        if (resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, ScreenCaptureService.class);
            service.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(ScreenCaptureService.EXTRA_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            Toast.makeText(this, "Screen reading is on;Agent Butch screens when needed", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Unopen screen reading", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA) {
            boolean cameraGranted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (cameraGranted) {
                enterCameraMode();
            } else {
                Toast.makeText(this, "We need camera access to take pictures.",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode != REQUEST_STORAGE) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Toast.makeText(this, granted ? "Storage privileges granted, work area:" + workspace.label()
                : "No storage privileges granted, using the applied internal directory", Toast.LENGTH_LONG).show();
        if (granted) {
            SourceBootstrap.ensureAsync(this);
            BootstrapInstaller.ensureAsync(this, () -> Rootfs.startInBackground(this));
        }
        updateStatus();
        renderAll();
    }

    // ==================== Picture Attachment ====================

    private void enterScreenshotWatchMode() {
        if (ScreenshotWatchService.isRunning()) {
            updateScreenshotModeBar();
            Toast.makeText(this, "Screenshot mode is on: use a system screenshot.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        ScreenshotWatchService.start(this);
        if (screenshotModeBar != null) {
            screenshotModeBar.setVisibility(View.VISIBLE);
        }
        Toast.makeText(this, "Screenshot mode is on: block screens for other applications, and screenshots automatically add to the dialogue",
                Toast.LENGTH_LONG).show();
    }

    private void updateScreenshotModeBar() {
        if (screenshotModeBar == null) {
            return;
        }
        boolean visible = ScreenshotWatchService.isRunning()
                && !drawerOpen && viewingRunningTask;
        screenshotModeBar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void registerScreenshotReceiver() {
        if (screenshotReceiverRegistered) {
            return;
        }
        try {
            IntentFilter filter = new IntentFilter(ScreenshotWatchService.ACTION_SCREENSHOT_ADDED);
            filter.addAction(ScreenshotWatchService.ACTION_STOPPED);
            registerReceiver(screenshotReceiver, filter);
            screenshotReceiverRegistered = true;
        } catch (Exception ignored) {
        }
    }

    private void unregisterScreenshotReceiver() {
        if (!screenshotReceiverRegistered) {
            return;
        }
        try {
            unregisterReceiver(screenshotReceiver);
        } catch (Exception ignored) {
        }
        screenshotReceiverRegistered = false;
    }

    private void loadPendingScreenshots() {
        final List<String> paths = prefs.takePendingScreenshots();
        if (paths.isEmpty()) {
            return;
        }
        final int room = Math.max(0, MAX_ATTACHMENT_COUNT - pendingImages.size());
        if (room <= 0) {
            for (String path : paths) {
                try {
                    new File(path).delete();
                } catch (Exception ignored) {
                }
            }
            Toast.makeText(this, "The attachment is full, no interdictions added", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ArrayList<AgentEngine.ImageAttachment> added = new ArrayList<>();
                for (String path : paths) {
                    if (added.size() >= room) {
                        try {
                            new File(path).delete();
                        } catch (Exception ignored) {
                        }
                        continue;
                    }
                    try {
                        File file = new File(path);
                        if (!file.isFile() || file.length() <= 0
                                || file.length() > 4L * 1024L * 1024L) {
                            continue;
                        }
                        byte[] bytes = new byte[(int) file.length()];
                        java.io.FileInputStream input = new java.io.FileInputStream(file);
                        try {
                            int offset = 0;
                            while (offset < bytes.length) {
                                int read = input.read(bytes, offset, bytes.length - offset);
                                if (read < 0) {
                                    break;
                                }
                                offset += read;
                            }
                        } finally {
                            input.close();
                        }
                        added.add(new AgentEngine.ImageAttachment(file.getAbsolutePath(),
                                bytes, "image/jpeg"));
                    } catch (Throwable ignored) {
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!added.isEmpty()) {
                            pendingImages.addAll(added);
                            renderAttachmentStrip();
                            Toast.makeText(MainActivity.this,
                                    "Yes. " + added.size() + " An amplitude",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }, "screenshot-attach").start();
    }

    private void enterCameraMode() {
        if (cameraMode) {
            return;
        }
        if (pendingImages.size() >= MAX_ATTACHMENT_COUNT) {
            Toast.makeText(this, "Up to Attach " + MAX_ATTACHMENT_COUNT + " Picture",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(this, "No cameras available for the current device", Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
            return;
        }
        showCameraPopup();
    }

    private void showCameraPopup() {
        if (cameraPopup != null && cameraPopup.isShowing()) {
            return;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackground(rounded(CARD, 16));
        content.setPadding(dp(6), dp(6), dp(6), dp(6));

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackground(rounded(Color.rgb(18, 18, 18), 11));
        cameraSurface = new SurfaceView(this);
        previewFrame.addView(cameraSurface, new FrameLayout.LayoutParams(MATCH, MATCH));

        TextView hint = new TextView(this);
        hint.setText("Points + Take pictures.");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(9.5f);
        hint.setBackground(rounded(Color.argb(150, 0, 0, 0), 8));
        hint.setPadding(dp(6), dp(3), dp(6), dp(3));
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(WRAP, WRAP,
                Gravity.BOTTOM | Gravity.START);
        hintParams.setMargins(dp(6), 0, 0, dp(6));
        previewFrame.addView(hint, hintParams);

        TextView close = new TextView(this);
        close.setText("×");
        close.setTextColor(TEXT);
        close.setTextSize(21f);
        close.setGravity(Gravity.CENTER);
        close.setBackground(rounded(Color.argb(160, 0, 0, 0), 15));
        close.setClickable(true);
        close.setFocusable(true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeCameraPreview();
            }
        });
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                dp(30), dp(30), Gravity.TOP | Gravity.END);
        closeParams.setMargins(0, dp(5), dp(5), 0);
        previewFrame.addView(close, closeParams);
        content.addView(previewFrame, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        cameraPopup = new PopupWindow(content, dp(210), dp(280), false);
        cameraPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        cameraPopup.setOutsideTouchable(false);
        cameraPopup.setElevation(dp(10));
        cameraPopup.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override
            public void onDismiss() {
                closeCameraPreview();
            }
        });
        cameraMode = true;
        cameraSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                openCamera(holder);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
            }
        });
        cameraPopup.showAtLocation(screenRoot, Gravity.BOTTOM | Gravity.START,
                dp(12), dp(82));
    }

    private void openCamera(SurfaceHolder holder) {
        if (camera != null) {
            return;
        }
        try {
            int cameraId = 0;
            boolean foundBack = false;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    cameraId = i;
                    foundBack = true;
                    break;
                }
            }
            if (!foundBack && Camera.getNumberOfCameras() == 0) {
                throw new IllegalStateException("No cameras.");
            }
            camera = Camera.open(cameraId);
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size previewSize = choosePreviewSize(parameters.getSupportedPreviewSizes());
            if (previewSize != null) {
                parameters.setPreviewSize(previewSize.width, previewSize.height);
            }
            Camera.Size pictureSize = choosePictureSize(parameters.getSupportedPictureSizes());
            if (pictureSize != null) {
                parameters.setPictureSize(pictureSize.width, pictureSize.height);
            }
            try {
                parameters.setRotation(90);
            } catch (Exception ignored) {
            }
            camera.setParameters(parameters);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(holder);
            camera.startPreview();
            cameraReady = true;
        } catch (Throwable error) {
            cameraReady = false;
            String message = error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage();
            Toast.makeText(this, "Can not open message" + message, Toast.LENGTH_LONG).show();
            closeCameraPreview();
        }
    }

    private static Camera.Size choosePreviewSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        Camera.Size best = sizes.get(0);
        int bestDiff = Integer.MAX_VALUE;
        for (Camera.Size size : sizes) {
            if (size.width > 1280 || size.height > 960) {
                continue;
            }
            int diff = Math.abs(size.width - 640) + Math.abs(size.height - 480);
            if (diff < bestDiff) {
                best = size;
                bestDiff = diff;
            }
        }
        return best;
    }

    private static Camera.Size choosePictureSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        Camera.Size best = sizes.get(0);
        long bestDiff = Long.MAX_VALUE;
        for (Camera.Size size : sizes) {
            long pixels = (long) size.width * size.height;
            long diff = Math.abs(pixels - 1280L * 960L);
            if (size.width <= 1920 && size.height <= 1440 && diff < bestDiff) {
                best = size;
                bestDiff = diff;
            }
        }
        return best;
    }

    private void captureCameraPhoto() {
        if (!cameraMode) {
            return;
        }
        if (pendingImages.size() >= MAX_ATTACHMENT_COUNT) {
            Toast.makeText(this, "Up to Attach " + MAX_ATTACHMENT_COUNT + " Picture",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!cameraReady || camera == null || cameraCaptureInProgress) {
            Toast.makeText(this, "The camera is still ready. One moment, please.", Toast.LENGTH_SHORT).show();
            return;
        }
        cameraCaptureInProgress = true;
        cameraReady = false;
        try {
            camera.takePicture(null, null, new Camera.PictureCallback() {
                @Override
                public void onPictureTaken(final byte[] data, Camera source) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!cameraMode) {
                                cameraCaptureInProgress = false;
                                return;
                            }
                            if (data != null && data.length > 0) {
                                addCameraImage(data);
                            } else {
                                Toast.makeText(MainActivity.this, "No photos.",
                                        Toast.LENGTH_SHORT).show();
                            }
                            cameraCaptureInProgress = false;
                            restartCameraPreview();
                        }
                    });
                }
            });
        } catch (Throwable error) {
            cameraCaptureInProgress = false;
            restartCameraPreview();
            Toast.makeText(this, "Photo failed:" + error.getClass().getSimpleName(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void restartCameraPreview() {
        if (!cameraMode || camera == null || cameraCaptureInProgress) {
            return;
        }
        try {
            camera.startPreview();
            cameraReady = true;
        } catch (Throwable error) {
            closeCameraPreview();
        }
    }

    private void addCameraImage(final byte[] jpeg) {
        if (pendingImages.size() >= MAX_ATTACHMENT_COUNT) {
            Toast.makeText(this, "Up to Attach " + MAX_ATTACHMENT_COUNT + " Picture",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final int slot = pendingImages.size() + 1;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final AgentEngine.ImageAttachment[] attachmentHolder =
                        new AgentEngine.ImageAttachment[1];
                String failure = null;
                try {
                    attachmentHolder[0] = ImageUtils.buildAttachment(MainActivity.this, jpeg, slot,
                            new File(getFilesDir(), "attachments"));
                    if (attachmentHolder[0] == null) {
                        failure = "Could not close temporary folder: %s";
                    }
                } catch (Throwable error) {
                    failure = error.getMessage() == null
                            ? error.getClass().getSimpleName() : error.getMessage();
                }
                final String failureText = failure;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (attachmentHolder[0] != null) {
                            pendingImages.add(attachmentHolder[0]);
                            renderAttachmentStrip();
                            Toast.makeText(MainActivity.this,
                                    "Added to Dialogue" + pendingImages.size() + "/"
                                            + MAX_ATTACHMENT_COUNT + ")",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "Photo processing failed:" + failureText, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "camera-image").start();
    }

    private void closeCameraPreview() {
        if (cameraClosing) {
            return;
        }
        cameraClosing = true;
        cameraMode = false;
        cameraCaptureInProgress = false;
        cameraReady = false;
        try {
            if (camera != null) {
                try {
                    camera.stopPreview();
                } catch (Exception ignored) {
                }
                camera.release();
            }
        } catch (Exception ignored) {
        } finally {
            camera = null;
        }
        PopupWindow popup = cameraPopup;
        cameraPopup = null;
        if (popup != null) {
            try {
                popup.dismiss();
            } catch (Exception ignored) {
            }
        }
        cameraSurface = null;
        cameraClosing = false;
    }


    private void pickImagesFromGallery() {
        Intent gallery = new Intent(Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        gallery.setType("image/*");
        gallery.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            if (gallery.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(gallery, REQUEST_PICK_IMAGES);
                return;
            }
        } catch (Exception ignored) {
        }
        Intent content = new Intent(Intent.ACTION_GET_CONTENT);
        content.addCategory(Intent.CATEGORY_OPENABLE);
        content.setType("image/*");
        content.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(content, REQUEST_PICK_IMAGES);
            return;
        } catch (Exception ignored) {
        }
        Intent document = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        document.addCategory(Intent.CATEGORY_OPENABLE);
        document.setType("image/*");
        document.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(document, REQUEST_PICK_IMAGES);
        } catch (Exception e) {
            Toast.makeText(this, "No picture selection available", Toast.LENGTH_SHORT).show();
        }
    }

    private void handlePickedImages(Intent data) {
        Log.i("AgentAttach", "pick result data=" + (data == null ? "null" : data.toString()));
        ArrayList<Uri> uris = new ArrayList<>();
        if (data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    ClipData.Item item = clip.getItemAt(i);
                    if (item != null && item.getUri() != null) {
                        uris.add(item.getUri());
                    }
                }
            }
            if (uris.isEmpty() && data.getData() != null) {
                uris.add(data.getData());
            }
        }
        if (uris.isEmpty()) {
            Log.w("AgentAttach", "picker returned no uri");
            Toast.makeText(this, "No pictures. Try again.", Toast.LENGTH_SHORT).show();
            return;
        }
        int room = MAX_ATTACHMENT_COUNT - pendingImages.size();
        if (room <= 0) {
            Toast.makeText(this, "Up to Attach " + MAX_ATTACHMENT_COUNT + " Picture",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (uris.size() > room) {
            uris = new ArrayList<>(uris.subList(0, room));
            Toast.makeText(this, "Up to Attach " + MAX_ATTACHMENT_COUNT + " Picture",
                    Toast.LENGTH_SHORT).show();
        }
        final ArrayList<Uri> picked = uris;
        final int baseSlot = pendingImages.size();
        final Dialog wait = showModelWait("Attach Picture", "Reading and compressing pictures...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ArrayList<AgentEngine.ImageAttachment> added = new ArrayList<>();
                String failure = null;
                for (int i = 0; i < picked.size(); i++) {
                    try {
                        AgentEngine.ImageAttachment attachment =
                                ImageUtils.buildAttachment(MainActivity.this, picked.get(i), baseSlot + i + 1,
                                        new File(getFilesDir(), "attachments"));
                        if (attachment != null) {
                            added.add(attachment);
                        } else {
                            failure = "Could not read " + picked.get(i);
                        }
                    } catch (Throwable error) {
                        failure = error.getMessage() == null
                                ? error.getClass().getSimpleName() : error.getMessage();
                        Log.w("AgentAttach", "decode failed uri=" + picked.get(i), error);
                    }
                }
                final String failureText = failure;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (wait != null && wait.isShowing()) {
                            wait.dismiss();
                        }
                        if (!added.isEmpty()) {
                            pendingImages.addAll(added);
                            renderAttachmentStrip();
                            Toast.makeText(MainActivity.this,
                                    failureText == null
                                            ? "Added " + added.size() + " Picture"
                                            : "Added " + added.size() + " Zhang, partial failure:" + failureText,
                                    failureText == null ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                        } else if (failureText != null) {
                            Toast.makeText(MainActivity.this, "Image processing failed:" + failureText,
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this, "Could not close temporary folder: %s",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }, "pick-images").start();
    }

    private void pickFilesFromSystem() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQUEST_PICK_FILES);
            return;
        } catch (Exception ignored) {
        }
        Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
        fallback.addCategory(Intent.CATEGORY_OPENABLE);
        fallback.setType("*/*");
        fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(fallback, REQUEST_PICK_FILES);
        } catch (Exception e) {
            Toast.makeText(this, "No File Selector Available", Toast.LENGTH_SHORT).show();
        }
    }

    private void handlePickedFiles(Intent data) {
        ArrayList<Uri> uris = new ArrayList<>();
        if (data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    ClipData.Item item = clip.getItemAt(i);
                    if (item != null && item.getUri() != null) {
                        uris.add(item.getUri());
                    }
                }
            }
            if (uris.isEmpty() && data.getData() != null) {
                uris.add(data.getData());
            }
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "No files. Try again.", Toast.LENGTH_SHORT).show();
            return;
        }
        final int imageRoom = Math.max(0, MAX_ATTACHMENT_COUNT - pendingImages.size());
        final int fileRoom = Math.max(0, MAX_FILE_COUNT - pendingFiles.size());
        if (imageRoom == 0 && fileRoom == 0) {
            Toast.makeText(this, "The number of annexes reached the maximum", Toast.LENGTH_SHORT).show();
            return;
        }
        final ArrayList<Uri> picked = uris;
        final int baseImage = pendingImages.size();
        final int baseFile = pendingFiles.size();
        final Dialog wait = showModelWait("Read Files", "Copying selected files to workspace...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ArrayList<AgentEngine.ImageAttachment> images = new ArrayList<>();
                final ArrayList<AgentEngine.FileAttachment> files = new ArrayList<>();
                final ArrayList<String> failures = new ArrayList<>();
                for (int i = 0; i < picked.size(); i++) {
                    Uri uri = picked.get(i);
                    String mime = null;
                    try {
                        mime = getContentResolver().getType(uri);
                    } catch (Throwable ignored) {
                    }
                    boolean isImage = mime != null && mime.startsWith("image/");
                    try {
                        if (isImage && baseImage + images.size() < MAX_ATTACHMENT_COUNT) {
                            AgentEngine.ImageAttachment attachment = ImageUtils.buildAttachment(
                                    MainActivity.this, uri, baseImage + images.size() + 1,
                                    new File(getFilesDir(), "attachments"));
                            if (attachment != null) {
                                images.add(attachment);
                            } else {
                                failures.add("Could not read pictures");
                            }
                        } else if (!isImage && baseFile + files.size() < MAX_FILE_COUNT) {
                            files.add(copySystemFile(uri, baseFile + files.size() + 1));
                        } else {
                            failures.add("The number of annexes reached the maximum");
                        }
                    } catch (Throwable error) {
                        String message = error.getMessage() == null
                                ? error.getClass().getSimpleName() : error.getMessage();
                        failures.add(message);
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (wait != null && wait.isShowing()) {
                            wait.dismiss();
                        }
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (!images.isEmpty()) {
                            pendingImages.addAll(images);
                        }
                        if (!files.isEmpty()) {
                            pendingFiles.addAll(files);
                        }
                        renderAttachmentStrip();
                        int added = images.size() + files.size();
                        if (added > 0 && failures.isEmpty()) {
                            Toast.makeText(MainActivity.this,
                                    "Added " + added + " Annex", Toast.LENGTH_SHORT).show();
                        } else if (added > 0) {
                            Toast.makeText(MainActivity.this,
                                    "Added " + added + " One, partial failure:" + failures.get(0),
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "File reading failed:" + (failures.isEmpty() ? "Unknown cause" : failures.get(0)),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "pick-files").start();
    }

    private AgentEngine.FileAttachment copySystemFile(Uri uri, int slot) throws Exception {
        String original = "file";
        long declared = -1L;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    String value = cursor.getString(nameIndex);
                    if (value != null && !value.trim().isEmpty()) {
                        original = new File(value.trim()).getName();
                    }
                }
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    declared = cursor.getLong(sizeIndex);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        if (declared > MAX_FILE_BYTES) {
            throw new java.io.IOException("File Over 50MB: " + original);
        }
        String clean = original.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (clean.isEmpty() || ".".equals(clean) || "..".equals(clean)) {
            clean = "file";
        }
        if (clean.length() > 100) {
            String ext = "";
            int dot = clean.lastIndexOf('.');
            if (dot > 0 && clean.length() - dot <= 12) {
                ext = clean.substring(dot);
            }
            clean = clean.substring(0, Math.max(1, 100 - ext.length())) + ext;
        }
        File dir = workspace.resolve(".agent/uploads");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new java.io.IOException("Could not close temporary folder: %s");
        }
        File target = new File(dir, System.currentTimeMillis() + "_" + slot + "_" + clean);
        for (int n = 1; target.exists(); n++) {
            target = new File(dir, System.currentTimeMillis() + "_" + slot + "_" + n + "_" + clean);
        }
        File part = new File(dir, target.getName() + ".part");
        InputStream input = getContentResolver().openInputStream(uri);
        if (input == null) {
            throw new java.io.IOException("Could not read selected file");
        }
        long total = 0L;
        try {
            java.io.FileOutputStream output = new java.io.FileOutputStream(part);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    total += read;
                    if (total > MAX_FILE_BYTES) {
                        throw new java.io.IOException("File Over 50MB: " + original);
                    }
                    output.write(buffer, 0, read);
                }
                output.flush();
                try {
                    output.getFD().sync();
                } catch (Throwable ignored) {
                }
            } finally {
                output.close();
            }
            if (!part.renameTo(target)) {
                throw new java.io.IOException("Failed to save file");
            }
        } catch (Exception error) {
            part.delete();
            target.delete();
            throw error;
        } finally {
            try {
                input.close();
            } catch (Throwable ignored) {
            }
        }
        String mime = null;
        try {
            mime = getContentResolver().getType(uri);
        } catch (Throwable ignored) {
        }
        return new AgentEngine.FileAttachment(workspace.relativePath(target),
                original, total, mime == null ? "" : mime);
    }

    private static String displayUploadName(String path) {
        String name = path == null ? "" : path;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.replaceFirst("^\\d+_\\d+_", "");
    }

    private void renderAttachmentStrip() {
        if (attachStrip == null) {
            return;
        }
        attachStrip.removeAllViews();
        if (attachScroll != null) {
            attachScroll.setVisibility(pendingImages.isEmpty() ? View.GONE : View.VISIBLE);
        }
        for (int i = 0; i < pendingImages.size(); i++) {
            final AgentEngine.ImageAttachment attachment = pendingImages.get(i);
            FrameLayout holder = new FrameLayout(this);
            LinearLayout.LayoutParams holderParams =
                    new LinearLayout.LayoutParams(dp(64), dp(64));
            holderParams.rightMargin = dp(8);
            holder.setLayoutParams(holderParams);

            ImageView thumb = new ImageView(this);
            Bitmap preview = ImageUtils.loadThumbnail(attachment.path, 192);
            if (preview != null) {
                thumb.setImageBitmap(preview);
            }
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(rounded(CARD_SOFT, 12));
            thumb.setClipToOutline(true);
            thumb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAttachmentPreview(attachment.path);
                }
            });
            holder.addView(thumb, new FrameLayout.LayoutParams(MATCH, MATCH));

            TextView remove = new TextView(this);
            remove.setText("×");
            remove.setTextColor(TEXT);
            remove.setTextSize(11f);
            remove.setGravity(Gravity.CENTER);
            remove.setBackground(rounded(Color.rgb(48, 48, 48), 9));
            remove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pendingImages.remove(attachment);
                    renderAttachmentStrip();
                }
            });
            FrameLayout.LayoutParams removeParams =
                    new FrameLayout.LayoutParams(dp(18), dp(18));
            removeParams.gravity = Gravity.TOP | Gravity.END;
            removeParams.topMargin = dp(2);
            removeParams.rightMargin = dp(2);
            holder.addView(remove, removeParams);
            attachStrip.addView(holder);
        }
        if (!pendingImages.isEmpty()) {
            TextView count = new TextView(this);
            count.setText(pendingImages.size() + "/" + MAX_ATTACHMENT_COUNT);
            count.setTextColor(DIM);
            count.setTextSize(11f);
            count.setGravity(Gravity.BOTTOM);
            count.setPadding(0, dp(44), dp(4), 0);
            attachStrip.addView(count, new LinearLayout.LayoutParams(WRAP, dp(64)));
        }
        renderFileStrip();
    }

    private void renderFileStrip() {
        if (fileStrip == null) {
            return;
        }
        fileStrip.removeAllViews();
        if (fileScroll != null) {
            fileScroll.setVisibility(pendingFiles.isEmpty() ? View.GONE : View.VISIBLE);
        }
        for (int i = 0; i < pendingFiles.size(); i++) {
            final AgentEngine.FileAttachment file = pendingFiles.get(i);
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setBackground(rounded(CARD_SOFT, 10));
            chip.setPadding(dp(10), dp(5), dp(10), dp(5));
            chip.setClickable(true);
            chip.setFocusable(true);
            LinearLayout.LayoutParams chipParams =
                    new LinearLayout.LayoutParams(WRAP, WRAP);
            chipParams.rightMargin = dp(8);
            chip.setLayoutParams(chipParams);
            TextView name = new TextView(this);
            name.setText(displayUploadName(file.path));
            name.setTextColor(TEXT);
            name.setTextSize(11f);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            name.setMaxWidth(dp(150));
            chip.addView(name, new LinearLayout.LayoutParams(MATCH, WRAP));
            TextView meta = new TextView(this);
            meta.setText(UiText.formatSize(file.size) + " · Click to Remove");
            meta.setTextColor(DIM);
            meta.setTextSize(9f);
            chip.addView(meta, new LinearLayout.LayoutParams(MATCH, WRAP));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pendingFiles.remove(file);
                    renderAttachmentStrip();
                }
            });
            fileStrip.addView(chip);
        }
    }

    private void showAttachmentPreview(String path) {
        Bitmap bitmap = ImageUtils.loadThumbnail(path, 1400);
        if (bitmap == null) {
            Toast.makeText(this, "Picture is no longer available", Toast.LENGTH_SHORT).show();
            return;
        }
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        ImageView image = new ImageView(this);
        image.setImageBitmap(bitmap);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        TextView close = new TextView(this);
        close.setText("Close");
        close.setTextColor(TEXT);
        close.setTextSize(13f);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(8), dp(12), dp(8), dp(12));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        root.addView(close, new LinearLayout.LayoutParams(MATCH, WRAP));
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.setLayout(MATCH, MATCH);
        }
        dialog.show();
    }

    private void clearPendingAttachments() {
        if (pendingImages.isEmpty() && pendingFiles.isEmpty()) {
            return;
        }
        pendingImages.clear();
        pendingFiles.clear();
        renderAttachmentStrip();
    }

    // ==================== Build product(s)APK)Management ====================

    private void showApkManager() {
        new ApkPages(this, workspace, new ApkPages.Host() {
            @Override
            public void hideKeyboard() {
                MainActivity.this.hideKeyboard();
            }

            @Override
            public void showMenu(View anchor, String title, String[] labels, String[] icons,
                                 int[] colors, Runnable[] actions) {
                showAgentMenu(anchor, title, labels, icons, colors, actions);
            }

            @Override
            public void toast(CharSequence text) {
                Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void confirm(String title, String message, String action,
                                boolean danger, Runnable run) {
                AgentKit.confirm(MainActivity.this, title, message, action, danger, run);
            }

            @Override
            public int dp(float value) {
                return MainActivity.this.dp(value);
            }

            @Override
            public ImageView icon(String name, int color) {
                return vectorIcon(name, color);
            }

            @Override
            public LinearLayout.LayoutParams iconSize(int size, int rightMargin) {
                return MainActivity.this.iconSize(size, rightMargin);
            }
        }).show();
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }




    private EditText numberInput(String value, String hint, boolean decimal) {
        EditText field = textInput(value, hint, InputType.TYPE_TEXT_VARIATION_NORMAL);
        field.setInputType(InputType.TYPE_CLASS_NUMBER
                | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        field.setText(value);
        field.setSelection(field.getText().length());
        return field;
    }

    private EditText textInput(String value, String hint, int variation) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(14f);
        field.setTextColor(TEXT);
        field.setHintTextColor(DIM);
        field.setHint(hint);
        field.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
            field.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            if (Build.VERSION.SDK_INT >= 26) {
                field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            }
        }
        field.setText(value);
        field.setSelection(field.getText().length());
        field.setBackground(rounded(CARD_SOFT, 12));
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        return field;
    }

    private TextView formLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(TEXT);
        label.setTextSize(12.5f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(0, dp(13), 0, dp(3));
        return label;
    }

    private TextView formHint(String text) {
        TextView hint = new TextView(this);
        hint.setText(text);
        hint.setTextColor(DIM);
        hint.setTextSize(10f);
        hint.setLineSpacing(dp(2), 1f);
        hint.setPadding(0, dp(4), 0, 0);
        return hint;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * The same layout that follows the bottom of the scroll: the same higher content rolls straight through the bottom, and the same way, the same way that the top of the page is in the back.
     * Avoid'First, we're wrong. Then we're wrong. post Pull back.'Make visual beats.
     */
    static final class PinScrollView extends ScrollView {
        private boolean pinBottom;
        private int pendingScrollY = -1;

        PinScrollView(android.content.Context context) {
            super(context);
        }

        /** Marks follow the bottom continuously: always bottom when new content enters. */
        void followBottom(boolean follow) {
            pinBottom = follow;
            if (follow) {
                pendingScrollY = -1;
            }
            requestLayout();
            scheduleFlush();
        }

        /** Next frame to return to the scroll position y, Use to keep your eyes open while users are turning their history. */
        void restoreScroll(int y) {
            pinBottom = false;
            pendingScrollY = Math.max(0, y);
            scheduleFlush();
        }

        private void scheduleFlush() {
            post(new Runnable() {
                @Override
                public void run() {
                    flushScroll();
                }
            });
        }

        void flushScroll() {
            if (pinBottom) {
                applyBottom();
                return;
            }
            if (pendingScrollY < 0) {
                return;
            }
            int y = pendingScrollY;
            pendingScrollY = -1;
            if (getScrollY() != y) {
                scrollTo(0, y);
            }
        }

        private void applyBottom() {
            View child = getChildCount() > 0 ? getChildAt(0) : null;
            if (child == null) {
                return;
            }
            int viewport = getHeight() - getPaddingTop() - getPaddingBottom();
            int target = Math.max(0, child.getHeight() - viewport);
            if (getScrollY() != target) {
                scrollTo(0, target);
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            // super.onLayout , the scroll range is updated and synchronized to take effect before the frame is drawn
            flushScroll();
        }
    }

    /**
     * List packagings for reusable subviews: Press when flow refreshs tag Re-use the view of the previous frame.
     * Only recreates the line of real change in content, avoiding full-screen blinking and rolling.
     */
    static final class FeedList extends LinearLayout {
        private final List<View> poolViews = new ArrayList<>();
        private final List<Object> poolKeys = new ArrayList<>();
        private boolean rendering;

        FeedList(android.content.Context context) {
            super(context);
        }

        void beginRender(boolean allowReuse) {
            poolViews.clear();
            poolKeys.clear();
            if (allowReuse) {
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    poolViews.add(child);
                    poolKeys.add(child.getTag());
                }
            }
            removeAllViews();
            rendering = allowReuse;
        }

        void endRender() {
            rendering = false;
            poolViews.clear();
            poolKeys.clear();
        }

        @Override
        public void addView(View child, int index, ViewGroup.LayoutParams params) {
            if (rendering) {
                Object key = child.getTag();
                if (key != null) {
                    for (int i = 0; i < poolKeys.size(); i++) {
                        if (key.equals(poolKeys.get(i))) {
                            View reused = poolViews.remove(i);
                            poolKeys.remove(i);
                            super.addView(reused, index, params);
                            return;
                        }
                    }
                }
            }
            super.addView(child, index, params);
        }
    }
}
