package org.droidplanner.android;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import com.ox3dr.services.android.lib.drone.connection.ConnectionParameter;
import com.ox3dr.services.android.lib.drone.connection.ConnectionResult;
import com.ox3dr.services.android.lib.drone.connection.ConnectionType;
import com.ox3dr.services.android.lib.drone.event.Event;
import com.ox3dr.services.android.lib.drone.mission.Mission;
import com.ox3dr.services.android.lib.model.ITLogApi;

import org.droidplanner.android.activities.helpers.BluetoothDevicesActivity;
import org.droidplanner.android.api.Drone;
import org.droidplanner.android.api.ServiceListener;
import org.droidplanner.android.api.ServiceManager;
import org.droidplanner.android.notifications.NotificationHandler;
import org.droidplanner.android.proxy.mission.MissionProxy;
import org.droidplanner.android.utils.analytics.GAUtils;
import org.droidplanner.android.utils.file.IO.ExceptionWriter;
import org.droidplanner.android.utils.prefs.DroidPlannerPrefs;

import java.util.ArrayList;
import java.util.List;

public class DroidPlannerApp extends Application implements ServiceListener {

	private static final long DELAY_TO_DISCONNECTION = 30000l; // ms

	private static final String CLAZZ_NAME = DroidPlannerApp.class.getName();
	private static final String TAG = DroidPlannerApp.class.getSimpleName();

	public static final String ACTION_TOGGLE_DRONE_CONNECTION = CLAZZ_NAME
			+ ".ACTION_TOGGLE_DRONE_CONNECTION";
	public static final String EXTRA_ESTABLISH_CONNECTION = "extra_establish_connection";

    private final static IntentFilter droneEventFilter = new IntentFilter();
    static {
        droneEventFilter.addAction(Event.EVENT_CONNECTED);
        droneEventFilter.addAction(Event.EVENT_DISCONNECTED);
    }

	private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			final String action = intent.getAction();
			if (ACTION_TOGGLE_DRONE_CONNECTION.equals(action)) {
                Drone drone = serviceMgr.getDrone();
				boolean connect = intent.getBooleanExtra(EXTRA_ESTABLISH_CONNECTION,
						!drone.isConnected());

				if (connect)
					connectToDrone();
				else
					disconnectFromDrone();
			}
            else if (Event.EVENT_CONNECTED.equals(action)) {
                handler.removeCallbacks(disconnectionTask);
            }
            else if (Event.EVENT_DISCONNECTED.equals(action)) {
                shouldWeTerminate();
            }
            else if(Event.EVENT_MISSION_DRONIE_CREATED.equals(action)
                    || Event.EVENT_MISSION_UPDATE.equals(action)
                    || Event.EVENT_MISSION_RECEIVED.equals(action)){
                missionProxy.load(getDrone().getMission());
            }
		}
	};

    @Override
    public void onServiceConnected() {
        notificationHandler = new NotificationHandler(getApplicationContext(), serviceMgr.getDrone());
        lbm.registerReceiver(broadcastReceiver, droneEventFilter);
        notifyApiConnected();
    }

    @Override
    public void onServiceDisconnected() {
        disconnect();
    }

    public interface ApiListener {
		void onApiConnected();

		void onApiDisconnected();
	}

	private final Runnable disconnectionTask = new Runnable() {
		@Override
		public void run() {
			disconnect();
		}
	};

	private final Handler handler = new Handler();
	private final List<ApiListener> apiListeners = new ArrayList<ApiListener>();

	private final Thread.UncaughtExceptionHandler dpExceptionHandler = new Thread.UncaughtExceptionHandler() {
		@Override
		public void uncaughtException(Thread thread, Throwable ex) {
			new ExceptionWriter(ex).saveStackTraceToSD();
			exceptionHandler.uncaughtException(thread, ex);
		}
	};

	private Thread.UncaughtExceptionHandler exceptionHandler;

    private ServiceManager serviceMgr;

    private MissionProxy missionProxy;
    private DroidPlannerPrefs dpPrefs;
	private NotificationHandler notificationHandler;
    private LocalBroadcastManager lbm;

	@Override
	public void onCreate() {
		super.onCreate();
		final Context context = getApplicationContext();

        dpPrefs = new DroidPlannerPrefs(context);
        lbm = LocalBroadcastManager.getInstance(context);

        serviceMgr = new ServiceManager(context);
        missionProxy = new MissionProxy(context, serviceMgr.getDrone());

		exceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler(dpExceptionHandler);

		GAUtils.initGATracker(this);
		GAUtils.startNewSession(context);

		registerReceiver(broadcastReceiver, new IntentFilter(ACTION_TOGGLE_DRONE_CONNECTION));
	}

	public void addApiListener(ApiListener listener) {
		if (listener == null)
			return;

        boolean isServiceConnected = serviceMgr.isServiceConnected();
		if (isServiceConnected)
			listener.onApiConnected();

        if(apiListeners.isEmpty() || !isServiceConnected) {
            handler.removeCallbacks(disconnectionTask);
            serviceMgr.connect(this);
        }

        apiListeners.add(listener);
	}

	public void removeApiListener(ApiListener listener) {
		if (listener != null) {
			apiListeners.remove(listener);
			listener.onApiDisconnected();
		}

		shouldWeTerminate();
	}

	private void shouldWeTerminate() {
		if (apiListeners.isEmpty() && !serviceMgr.getDrone().isConnected()) {
			// Wait 30s, then disconnect the service binding.
			handler.postDelayed(disconnectionTask, DELAY_TO_DISCONNECTION);
		}
	}

	private void notifyApiConnected() {
		if (apiListeners.isEmpty())
			return;

		for (ApiListener listener : apiListeners)
			listener.onApiConnected();
	}

	private void notifyApiDisconnected() {
		if (apiListeners.isEmpty())
			return;

		for (ApiListener listener : apiListeners)
			listener.onApiDisconnected();
	}

	public void disconnect() {
		notifyApiDisconnected();
		notificationHandler.terminate();
        lbm.unregisterReceiver(broadcastReceiver);
	}

    public void connectToDrone(){
        final Drone drone = getDrone();

        drone.updateConnectionParameter(retrieveConnectionParameters());
        if(!drone.isConnected()) {
            drone.connect();
        }
    }

    public static void connectToDrone(Context context){
        context.sendBroadcast(new Intent(DroidPlannerApp.ACTION_TOGGLE_DRONE_CONNECTION)
                .putExtra(DroidPlannerApp.EXTRA_ESTABLISH_CONNECTION, true));
    }

    public static void disconnectFromDrone(Context context){
        context.sendBroadcast(new Intent(DroidPlannerApp.ACTION_TOGGLE_DRONE_CONNECTION)
                .putExtra(DroidPlannerApp.EXTRA_ESTABLISH_CONNECTION, false));
    }

    public void disconnectFromDrone(){
        final Drone drone = getDrone();

        if(drone.isConnected())
            drone.disconnect();
    }

    public Drone getDrone(){
        return serviceMgr.getDrone();
    }

    public ITLogApi getTlogApi(){
        return serviceMgr.getTlogApi();
    }

    public MissionProxy getMissionProxy(){
        return this.missionProxy;
    }

    private ConnectionParameter retrieveConnectionParameters() {
        final int connectionType = dpPrefs.getConnectionParameterType();
        Bundle extraParams = new Bundle();

        ConnectionParameter connParams;
        switch (connectionType) {
            case ConnectionType.TYPE_USB:
                extraParams.putInt(ConnectionType.EXTRA_USB_BAUD_RATE, dpPrefs.getUsbBaudRate());
                connParams = new ConnectionParameter(connectionType, extraParams);
                break;

            case ConnectionType.TYPE_UDP:
                extraParams.putInt(ConnectionType.EXTRA_UDP_SERVER_PORT, dpPrefs.getUdpServerPort());
                connParams = new ConnectionParameter(connectionType, extraParams);
                break;

            case ConnectionType.TYPE_TCP:
                extraParams.putString(ConnectionType.EXTRA_TCP_SERVER_IP, dpPrefs.getTcpServerIp());
                extraParams.putInt(ConnectionType.EXTRA_TCP_SERVER_PORT, dpPrefs.getTcpServerPort());
                connParams = new ConnectionParameter(connectionType, extraParams);
                break;

            case ConnectionType.TYPE_BLUETOOTH:
                String btAddress = dpPrefs.getBluetoothDeviceAddress();
                if (TextUtils.isEmpty(btAddress)) {
                    connParams = null;
                    startActivity(new Intent(getApplicationContext(),
                            BluetoothDevicesActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));

                } else {
                    extraParams.putString(ConnectionType.EXTRA_BLUETOOTH_ADDRESS, btAddress);
                    connParams = new ConnectionParameter(connectionType, extraParams);
                }
                break;

            default:
                Log.e(TAG, "Unrecognized connection type: " + connectionType);
                connParams = null;
                break;
        }

        return connParams;
    }
}
