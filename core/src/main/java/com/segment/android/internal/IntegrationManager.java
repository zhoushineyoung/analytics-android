package com.segment.android.internal;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import com.segment.android.Segment;
import com.segment.android.internal.integrations.AbstractIntegrationAdapter;
import com.segment.android.internal.integrations.AmplitudeIntegrationAdapter;
import com.segment.android.internal.integrations.BugsnagIntegrationAdapter;
import com.segment.android.internal.integrations.CountlyIntegrationAdapter;
import com.segment.android.internal.integrations.CrittercismIntegrationAdapter;
import com.segment.android.internal.integrations.FlurryIntegrationAdapter;
import com.segment.android.internal.integrations.GoogleAnalyticsIntegrationAdapter;
import com.segment.android.internal.integrations.InvalidConfigurationException;
import com.segment.android.internal.integrations.LocalyticsIntegrationAdapter;
import com.segment.android.internal.integrations.MixpanelIntegrationAdapter;
import com.segment.android.internal.integrations.QuantcastIntegrationAdapter;
import com.segment.android.internal.integrations.TapstreamIntegrationAdapter;
import com.segment.android.internal.payload.AliasPayload;
import com.segment.android.internal.payload.BasePayload;
import com.segment.android.internal.payload.GroupPayload;
import com.segment.android.internal.payload.IdentifyPayload;
import com.segment.android.internal.payload.ScreenPayload;
import com.segment.android.internal.payload.TrackPayload;
import com.segment.android.json.JsonMap;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static com.segment.android.internal.Utils.getSharedPreferences;
import static com.segment.android.internal.Utils.isConnected;

/**
 * Manages bundled integrations. This class will maintain it's own queue for events to account for
 * the latency between receiving the first event, fetching remote settings and enabling the
 * integrations. Once we enable all integrations - we'll replay any events in the queue. This
 * should only affect the first app install, subsequent launches will be use a cached value from
 * disk.
 */
public class IntegrationManager {
  private static final String PROJECT_SETTINGS_CACHE_KEY = "project-settings";

  static final int REQUEST_FETCH_SETTINGS = 1;
  static final int REQUEST_INIT = 2;
  static final int REQUEST_LIFECYCLE_EVENT = 3;
  static final int REQUEST_ANALYTICS_EVENT = 4;
  static final int REQUEST_FLUSH = 5;

  private static final String INTEGRATION_MANAGER_THREAD_NAME =
      Utils.THREAD_PREFIX + "IntegrationManager";
  // A set of integrations available on the device
  private final Set<AbstractIntegrationAdapter> bundledIntegrations =
      new HashSet<AbstractIntegrationAdapter>();
  // A map of integrations that were found on the device, so that we disable them for servers
  private Map<String, Boolean> serverIntegrations = new LinkedHashMap<String, Boolean>();
  private Queue<IntegrationOperation> operationQueue = new ArrayDeque<IntegrationOperation>();
  final AtomicBoolean initialized = new AtomicBoolean();

  public enum ActivityLifecycleEvent {
    CREATED, STARTED, RESUMED, PAUSED, STOPPED, SAVE_INSTANCE, DESTROYED
  }

  static class ActivityLifecyclePayload {
    final ActivityLifecycleEvent type;
    final WeakReference<Activity> activityWeakReference;
    final Bundle bundle;

    ActivityLifecyclePayload(ActivityLifecycleEvent type, Activity activity, Bundle bundle) {
      this.type = type;
      this.activityWeakReference = new WeakReference<Activity>(activity);
      this.bundle = bundle;
    }
  }

  public static IntegrationManager create(Context context, SegmentHTTPApi segmentHTTPApi,
      Stats stats) {
    StringCache projectSettingsCache =
        new StringCache(getSharedPreferences(context), PROJECT_SETTINGS_CACHE_KEY);
    return new IntegrationManager(context, segmentHTTPApi, projectSettingsCache, stats);
  }

  final Context context;
  final SegmentHTTPApi segmentHTTPApi;
  final HandlerThread integrationManagerThread;
  final Handler handler;
  final Stats stats;
  final StringCache projectSettingsCache;

  private IntegrationManager(Context context, SegmentHTTPApi segmentHTTPApi,
      StringCache projectSettingsCache, Stats stats) {
    this.context = context;
    this.segmentHTTPApi = segmentHTTPApi;
    this.stats = stats;
    integrationManagerThread =
        new HandlerThread(INTEGRATION_MANAGER_THREAD_NAME, THREAD_PRIORITY_BACKGROUND);
    integrationManagerThread.start();
    handler = new IntegrationManagerHandler(integrationManagerThread.getLooper(), this);

    loadIntegrations();

    this.projectSettingsCache = projectSettingsCache;
    ProjectSettings projectSettings = ProjectSettings.load(projectSettingsCache);
    if (projectSettings == null) {
      dispatchFetch();
    } else {
      dispatchInit(projectSettings);
      if (projectSettings.timestamp() + 10800000L < System.currentTimeMillis()) {
        Logger.v("Stale settings");
        dispatchFetch();
      }
    }
  }

  void loadIntegrations() {
    initialized.set(false);
    // Look up all the integrations available on the device. This is done early so that we can
    // disable sending to these integrations from the server and properly fill the payloads.
    addToBundledIntegrations(new AmplitudeIntegrationAdapter());
    addToBundledIntegrations(new BugsnagIntegrationAdapter());
    addToBundledIntegrations(new CountlyIntegrationAdapter());
    addToBundledIntegrations(new CrittercismIntegrationAdapter());
    addToBundledIntegrations(new FlurryIntegrationAdapter());
    addToBundledIntegrations(new GoogleAnalyticsIntegrationAdapter());
    addToBundledIntegrations(new LocalyticsIntegrationAdapter());
    addToBundledIntegrations(new MixpanelIntegrationAdapter());
    addToBundledIntegrations(new QuantcastIntegrationAdapter());
    addToBundledIntegrations(new TapstreamIntegrationAdapter());
  }

  void addToBundledIntegrations(AbstractIntegrationAdapter abstractIntegrationAdapter) {
    try {
      Class.forName(abstractIntegrationAdapter.className());
      bundledIntegrations.add(abstractIntegrationAdapter);
      serverIntegrations.put(abstractIntegrationAdapter.key(), false);
      Logger.v("Loaded integration %s", abstractIntegrationAdapter.key());
    } catch (ClassNotFoundException e) {
      Logger.v("Integration %s not bundled", abstractIntegrationAdapter.key());
    }
  }

  void dispatchFetch() {
    Logger.v("Fetching integration settings from server");
    handler.sendMessage(handler.obtainMessage(REQUEST_FETCH_SETTINGS));
  }

  void performFetch() {
    try {
      if (isConnected(context)) {
        dispatchInit(segmentHTTPApi.fetchSettings());
      }
    } catch (IOException e) {
      Logger.e(e, "Failed to fetch settings. Retrying.");
      dispatchFetch();
    }
  }

  void dispatchInit(ProjectSettings projectSettings) {
    handler.sendMessage(handler.obtainMessage(REQUEST_INIT, projectSettings));
  }

  void performInit(ProjectSettings projectSettings) {
    projectSettingsCache.set(projectSettings.toString());
    if (initialized.get()) {
      Logger.d("Integrations already initialized. Skipping.");
      return;
    }
    Logger.v("Initializing integrations with settings %s", projectSettings);
    Iterator<AbstractIntegrationAdapter> iterator = bundledIntegrations.iterator();
    while (iterator.hasNext()) {
      AbstractIntegrationAdapter integration = iterator.next();
      if (projectSettings.containsKey(integration.key())) {
        JsonMap settings = new JsonMap(projectSettings.getJsonMap(integration.key()));
        try {
          integration.initialize(context, settings);
          Logger.v("Initialized integration %s", integration.key());
        } catch (InvalidConfigurationException e) {
          iterator.remove();
          Logger.e(e, "could not initialize integration " + integration.key());
        }
      } else {
        iterator.remove();
        Logger.v("%s integration not enabled in project settings.", integration.key());
      }
    }
    initialized.set(true);
    replay();
  }

  // Activity Lifecycle Events
  public void dispatchLifecycleEvent(ActivityLifecycleEvent event, Activity activity,
      Bundle bundle) {
    handler.sendMessage(handler.obtainMessage(REQUEST_LIFECYCLE_EVENT,
        new ActivityLifecyclePayload(event, activity, bundle)));
  }

  void performEnqueue(final ActivityLifecyclePayload payload) {
    switch (payload.type) {
      case CREATED:
        if (payload.activityWeakReference.get() != null) {
          enqueue(new IntegrationOperation() {
            @Override public void run(AbstractIntegrationAdapter integration) {
              integration.onActivityCreated(payload.activityWeakReference.get(), payload.bundle);
            }
          });
        }
        break;
      case STARTED:
        if (payload.activityWeakReference.get() != null) {
          enqueue(new IntegrationOperation() {
            @Override public void run(AbstractIntegrationAdapter integration) {
              integration.onActivityStarted(payload.activityWeakReference.get());
            }
          });
        }
        break;
      case RESUMED:
        if (payload.activityWeakReference.get() != null) {
          enqueue(new IntegrationOperation() {
            @Override public void run(AbstractIntegrationAdapter integration) {
              integration.onActivityResumed(payload.activityWeakReference.get());
            }
          });
        }
        break;
      case PAUSED:
        if (payload.activityWeakReference.get() != null) {
          enqueue(new IntegrationOperation() {
            @Override public void run(AbstractIntegrationAdapter integration) {
              integration.onActivityPaused(payload.activityWeakReference.get());
            }
          });
        }
        break;
      case STOPPED:
        if (payload.activityWeakReference.get() != null) {
          enqueue(new IntegrationOperation() {
            @Override public void run(AbstractIntegrationAdapter integration) {
              integration.onActivityStopped(payload.activityWeakReference.get());
            }
          });
        }
        break;
      case SAVE_INSTANCE:
        if (payload.activityWeakReference.get() != null) {
          enqueue(new IntegrationOperation() {
            @Override public void run(AbstractIntegrationAdapter integration) {
              integration.onActivitySaveInstanceState(payload.activityWeakReference.get(),
                  payload.bundle);
            }
          });
        }
        break;
      case DESTROYED:
        if (payload.activityWeakReference.get() != null) {
          enqueue(new IntegrationOperation() {
            @Override public void run(AbstractIntegrationAdapter integration) {
              integration.onActivityDestroyed(payload.activityWeakReference.get());
            }
          });
        }
        break;
      default:
        throw new IllegalArgumentException("Unknown payload type!" + payload.type);
    }
  }

  public void dispatchAnalyticsEvent(BasePayload payload) {
    handler.sendMessage(handler.obtainMessage(REQUEST_ANALYTICS_EVENT, payload));
  }

  void performEnqueue(final BasePayload payload) {
    switch (payload.type()) {
      case alias:
        enqueue(new IntegrationOperation() {
          @Override public void run(AbstractIntegrationAdapter integration) {
            if (isBundledIntegrationEnabledForPayload(payload, integration)) {
              integration.alias((AliasPayload) payload);
            }
          }
        });
        break;
      case group:
        enqueue(new IntegrationOperation() {
          @Override public void run(AbstractIntegrationAdapter integration) {
            if (isBundledIntegrationEnabledForPayload(payload, integration)) {
              integration.group((GroupPayload) payload);
            }
          }
        });
        break;
      case identify:
        enqueue(new IntegrationOperation() {
          @Override public void run(AbstractIntegrationAdapter integration) {
            if (isBundledIntegrationEnabledForPayload(payload, integration)) {

              integration.identify((IdentifyPayload) payload);
            }
          }
        });
        break;
      case page:
      case screen:
        enqueue(new IntegrationOperation() {
          @Override public void run(AbstractIntegrationAdapter integration) {
            if (isBundledIntegrationEnabledForPayload(payload, integration)) {
              integration.screen((ScreenPayload) payload);
            }
          }
        });
        break;
      case track:
        enqueue(new IntegrationOperation() {
          @Override public void run(AbstractIntegrationAdapter integration) {
            if (isBundledIntegrationEnabledForPayload(payload, integration)) {
              integration.track((TrackPayload) payload);
            }
          }
        });
        break;
      default:
        throw new IllegalArgumentException("Unknown payload type!" + payload.type());
    }
  }

  public void dispatchFlush() {
    handler.sendMessage(handler.obtainMessage(REQUEST_FLUSH));
  }

  void performFlush() {
    enqueue(new IntegrationOperation() {
      @Override public void run(AbstractIntegrationAdapter integration) {
        integration.flush();
      }
    });
  }

  private interface IntegrationOperation {
    // todo: enumerate operations to avoid inn
    void run(AbstractIntegrationAdapter integration);
  }

  void enqueue(IntegrationOperation operation) {
    if (!initialized.get()) {
      Logger.v("Integrations not yet initialized! Queuing operation.");
      operationQueue.add(operation);
    } else {
      run(operation);
    }
  }

  private void run(IntegrationOperation operation) {
    for (AbstractIntegrationAdapter integration : bundledIntegrations) {
      long startTime = System.currentTimeMillis();
      operation.run(integration);
      long endTime = System.currentTimeMillis();
      long duration = endTime - startTime;
      Logger.v("Integration %s took %s ms to run operation", integration.key(), duration);
      stats.dispatchIntegrationOperation(duration);
    }
  }

  void replay() {
    Logger.v("Replaying %s events.", operationQueue.size());
    stats.dispatchReplay();
    while (operationQueue.size() > 0) {
      IntegrationOperation operation = operationQueue.peek();
      run(operation);
      operationQueue.remove();
    }
  }

  private boolean isBundledIntegrationEnabledForPayload(BasePayload payload,
      AbstractIntegrationAdapter integration) {
    Boolean enabled = true;
    // look in the payload.context.integrations to see which Bundled integrations should be
    // disabled. payload.integrations is reserved for the server, where all bundled integrations
    // have been  set to false
    JsonMap integrations = payload.context().getIntegrations();
    if (!JsonMap.isNullOrEmpty(integrations)) {
      String key = integration.key();
      if (integrations.containsKey(key)) {
        enabled = integrations.getBoolean(key);
      } else if (integrations.containsKey("All")) {
        enabled = integrations.getBoolean("All");
      } else if (integrations.containsKey("all")) {
        enabled = integrations.getBoolean("all");
      }
    }
    return enabled;
  }

  public Map<String, Boolean> bundledIntegrations() {
    return serverIntegrations;
  }

  private static class IntegrationManagerHandler extends Handler {
    private final IntegrationManager integrationManager;

    public IntegrationManagerHandler(Looper looper, IntegrationManager integrationManager) {
      super(looper);
      this.integrationManager = integrationManager;
    }

    @Override public void handleMessage(final Message msg) {
      switch (msg.what) {
        case REQUEST_FETCH_SETTINGS:
          integrationManager.performFetch();
          break;
        case REQUEST_INIT:
          ProjectSettings settings = (ProjectSettings) msg.obj;
          integrationManager.performInit(settings);
          break;
        case REQUEST_LIFECYCLE_EVENT:
          ActivityLifecyclePayload activityLifecyclePayload = (ActivityLifecyclePayload) msg.obj;
          integrationManager.performEnqueue(activityLifecyclePayload);
          break;
        case REQUEST_ANALYTICS_EVENT:
          BasePayload basePayload = (BasePayload) msg.obj;
          integrationManager.performEnqueue(basePayload);
          break;
        case REQUEST_FLUSH:
          integrationManager.performFlush();
          break;
        default:
          Segment.HANDLER.post(new Runnable() {
            @Override public void run() {
              throw new AssertionError("Unhandled dispatcher message." + msg.what);
            }
          });
      }
    }
  }
}