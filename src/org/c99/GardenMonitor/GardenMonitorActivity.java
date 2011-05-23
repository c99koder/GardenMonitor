package org.c99.GardenMonitor;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.List;

import com.android.future.usb.UsbAccessory;
import com.android.future.usb.UsbManager;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

public class GardenMonitorActivity extends Activity implements SurfaceHolder.Callback {
	private static final String TAG = "GardenController";
	private static final String ACTION_USB_PERMISSION = "org.c99.action.USB_PERMISSION";

	private UsbManager mUsbManager;
	private PendingIntent mPermissionIntent;
	private boolean mPermissionRequestPending;

	private TextView mTemperatureView;
	private TextView mHumidityView;
	private ProgressBar mWaterLevelView;
	
	UsbAccessory mAccessory;
	ParcelFileDescriptor mFileDescriptor;
	FileInputStream mInputStream;
	FileOutputStream mOutputStream;
	Camera camera;
	
	int mLastTemp = 0;
	int mLastHumid = 0;
	int mLastWaterLevel = 0;
	String mLastImageFilename;
	private boolean mDoorOpenDueToLowWater = false;
	
	PendingIntent mTakePhotoIntent;
	
	PowerManager.WakeLock mWakeLock;
	WifiManager.WifiLock mWifiLock;
	
	private static final int MESSAGE_TEMPERATURE = 1;
	private static final int MESSAGE_HUMIDITY = 2;
	private static final int MESSAGE_WATERLEVEL = 3;
	private static final byte COMMAND_OPEN_DOOR = 0x01;
	private static final byte COMMAND_CLOSE_DOOR = 0x02;

	protected class TelemetryPacket {
		private int value;

		public TelemetryPacket(int value) {
			this.value = value;
		}

		public int getValue() {
			return value;
		}
	}
	
	private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (ACTION_USB_PERMISSION.equals(action)) {
				synchronized (this) {
					UsbAccessory accessory = UsbManager.getAccessory(intent);
					if (intent.getBooleanExtra(
							UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
						openAccessory(accessory);
					} else {
						Log.d(TAG, "permission denied for accessory "
								+ accessory);
					}
					mPermissionRequestPending = false;
				}
			} else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
				UsbAccessory accessory = UsbManager.getAccessory(intent);
				if (accessory != null && accessory.equals(mAccessory)) {
					closeAccessory();
				}
			} else if (action.equals("org.c99.GardenController.TAKE_PHOTO")) {
				mHandler.post(mTakePictureTask);
			}
		}
	};

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		
		mUsbManager = UsbManager.getInstance(this);
		mPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(
				ACTION_USB_PERMISSION), 0);
		IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
		filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
		filter.addAction("org.c99.GardenController.TAKE_PHOTO");
		registerReceiver(mReceiver, filter);

		if (getLastNonConfigurationInstance() != null) {
			mAccessory = (UsbAccessory) getLastNonConfigurationInstance();
			openAccessory(mAccessory);
		} else {
			setContentView(R.layout.no_device);
		}
		
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

		WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		mWifiLock = wm.createWifiLock(TAG);
		
		mTakePhotoIntent = PendingIntent.getBroadcast(this, 0, new Intent("org.c99.GardenController.TAKE_PHOTO"), 0);
		
		AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
		am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), AlarmManager.INTERVAL_HOUR, mTakePhotoIntent);
	}

	@Override
	public Object onRetainNonConfigurationInstance() {
		if (mAccessory != null) {
			return mAccessory;
		} else {
			return super.onRetainNonConfigurationInstance();
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		
		mHandler.postDelayed(mHeartbeatTask, 100);
		
		mWakeLock.acquire();
		mWifiLock.acquire();
		
		if (mInputStream != null && mOutputStream != null) {
			return;
		}

		UsbAccessory[] accessories = mUsbManager.getAccessoryList();
		UsbAccessory accessory = (accessories == null ? null : accessories[0]);
		if (accessory != null) {
			if (mUsbManager.hasPermission(accessory)) {
				openAccessory(accessory);
			} else {
				synchronized (mReceiver) {
					if (!mPermissionRequestPending) {
						mUsbManager.requestPermission(accessory,
								mPermissionIntent);
						mPermissionRequestPending = true;
					}
				}
			}
		} else {
			Log.d(TAG, "mAccessory is null");
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		mWakeLock.release();
		mWifiLock.release();
	}

	@Override
	public void onDestroy() {
		closeAccessory();
		unregisterReceiver(mReceiver);
		AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
		am.cancel(mTakePhotoIntent);
		super.onDestroy();
	}

	private void setupContentView() {
		setContentView(R.layout.main);
		
		mTemperatureView = (TextView)findViewById(R.id.temperature);
		mHumidityView = (TextView)findViewById(R.id.humidity);
		mWaterLevelView = (ProgressBar)findViewById(R.id.water);
		mWaterLevelView.setMax(100);
		
		Button snapshotButton = (Button)findViewById(R.id.snapshot);
		snapshotButton.setOnClickListener(new OnClickListener() {
			public void onClick(View arg0) {
				mHandler.post(mTakePictureTask);
			}
		});
		Button closeButton = (Button)findViewById(R.id.close);
		closeButton.setOnClickListener(new OnClickListener() {
			public void onClick(View arg0) {
				mHandler.post(mCloseDoorTask);
			}
		});
		
		SurfaceView sv = (SurfaceView)findViewById(R.id.cameraView);
		sv.getHolder().addCallback(this);
		sv.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
	}
	
	private void openAccessory(UsbAccessory accessory) {
		mFileDescriptor = mUsbManager.openAccessory(accessory);
		if (mFileDescriptor != null) {
			mAccessory = accessory;
			FileDescriptor fd = mFileDescriptor.getFileDescriptor();
			mInputStream = new FileInputStream(fd);
			mOutputStream = new FileOutputStream(fd);
			Thread thread = new Thread(null, mUsbRunnable, "GardenController-USB");
			thread.start();
			Log.d(TAG, "accessory opened");
			setupContentView();
		} else {
			Log.d(TAG, "accessory open fail");
		}
	}

	private void closeAccessory() {
		try {
			if (mFileDescriptor != null) {
				mFileDescriptor.close();
			}
		} catch (IOException e) {
		} finally {
			mFileDescriptor = null;
			mAccessory = null;
		}
		setContentView(R.layout.no_device);
	}

	private int composeInt(byte hi, byte lo) {
		int val = (int) hi & 0xff;
		val *= 256;
		val += (int) lo & 0xff;
		return val;
	}

	private Runnable mUsbRunnable = new Runnable() {
		   public void run() {
				int ret = 0;
				byte[] buffer = new byte[16384];
				int i;
		
				while (ret >= 0) {
					try {
						ret = mInputStream.read(buffer);
					} catch (IOException e) {
						break;
					}
		
					i = 0;
					while (i < ret) {
						int len = ret - i;
		
						switch (buffer[i]) {
						case 0x1:
							if (len >= 3) {
								Message m = Message.obtain(mHandler,MESSAGE_TEMPERATURE);
								m.obj = new TelemetryPacket(composeInt(buffer[i + 1],buffer[i + 2]));
								mHandler.sendMessage(m);
							}
							i += 3;
							break;
						case 0x2:
							if (len >= 3) {
								Message m = Message.obtain(mHandler,MESSAGE_HUMIDITY);
								m.obj = new TelemetryPacket(composeInt(buffer[i + 1],buffer[i + 2]));
								mHandler.sendMessage(m);
							}
							i += 3;
							break;
						case 0x3:
							if (len >= 3) {
								Message m = Message.obtain(mHandler,MESSAGE_WATERLEVEL);
								m.obj = new TelemetryPacket(composeInt(buffer[i + 1],buffer[i + 2]));
								mHandler.sendMessage(m);
							}
							i += 3;
							break;
		
						default:
							Log.d(TAG, "unknown msg: " + buffer[i]);
							i = len;
							break;
						}
					}
		
				}
		   }
	};

	Handler mHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			TelemetryPacket p = (TelemetryPacket) msg.obj;
			switch (msg.what) {
			case MESSAGE_TEMPERATURE:
				mTemperatureView.setText(p.value + "¡F");
				if(mLastTemp != p.value) {
					sendXplBroadcast("xpl-trig", "*", "sensor.basic", "device=thermo1\ntype=temp\ncurrent=" + p.value + "\nunits=f");
					mLastTemp = p.value;
				}
				break;
			case MESSAGE_HUMIDITY:
				mHumidityView.setText(p.value + "%");
				if(mLastHumid != p.value) {
					sendXplBroadcast("xpl-trig", "*", "sensor.basic", "device=humid1\ntype=humidity\ncurrent=" + p.value + "\nunits=%");
					mLastHumid = p.value;
				}
				break;
			case MESSAGE_WATERLEVEL:
				mWaterLevelView.setProgress(p.value);
				if(mLastWaterLevel != p.value) {
					sendXplBroadcast("xpl-trig", "*", "sensor.basic", "device=force1\ntype=waterlevel\ncurrent=" + p.value + "\nunits=%");
					mLastWaterLevel = p.value;
					if(p.value <= 40 && p.value > 1 && !mDoorOpenDueToLowWater) {
						mDoorOpenDueToLowWater = true;
						mHandler.post(mOpenDoorTask);
					} else if(mDoorOpenDueToLowWater && p.value == 100) {
						mDoorOpenDueToLowWater = false;
						mHandler.postDelayed(mCloseDoorTask, 10000);
					}
				}
				break;
			}
		}
	};

	public void sendCommand(byte command, byte target, int value) {
		byte[] buffer = new byte[3];
		if (value > 255)
			value = 255;

		buffer[0] = command;
		buffer[1] = target;
		buffer[2] = (byte) value;
		if (mOutputStream != null && buffer[1] != -1) {
			try {
				mOutputStream.write(buffer);
			} catch (IOException e) {
				Log.e(TAG, "write failed", e);
			}
		}
	}
	
	public void sendXplBroadcast(String type, String target, String schema, String body) {
	    try {
	    	String data = "xpl-stat\n{\nhop=1\nsource=c99org-garden." + mAccessory.getSerial() + "\ntarget="+target+"\n}\n"+schema+"\n{\n"+ body + "\n}\n";
	    	Log.d(TAG, "outgoing XPL broadcast: " + data);
	    	
			WifiManager wifi = (WifiManager)getSystemService(Context.WIFI_SERVICE);
		    DhcpInfo dhcp = wifi.getDhcpInfo();
		    // handle null somehow
	
		    int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
		    byte[] quads = new byte[4];
		    for (int k = 0; k < 4; k++)
		      quads[k] = (byte) ((broadcast >> k * 8) & 0xFF);
			InetAddress broadcastAddress = InetAddress.getByAddress(quads);
			
			DatagramSocket socket = new DatagramSocket(3865);
			socket.setReuseAddress(true);
			socket.setBroadcast(true);
			DatagramPacket packet = new DatagramPacket(data.getBytes(), data.length(), broadcastAddress, 3865);
			socket.send(packet);
			socket.close();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	/*
	 * Alas, it seems the Nexus One cannot recieve UDB broadcasts
	 * 
	 * private Runnable mXplRunnable = new Runnable() {
		   public void run() {
			   byte[] buf = new byte[1024];
			   try {
				   Log.d(TAG, "+++ XPL MONITOR START +++");
				   xplSocket = new DatagramSocket(3865);
				   xplSocket.setBroadcast(true);
	
				   while(!xplSocket.isClosed()) {
					   try {
						   DatagramPacket packet = new DatagramPacket(buf, buf.length);
						   xplSocket.receive(packet);
						   
						   String data = new String(buf, buf.length);
						   Log.d(TAG, "Incoming XPL broadcast: " + data);
						} catch (Exception e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
				   }
				} catch (Exception e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
			   Log.d(TAG, "+++ XPL MONITOR END +++");
		   }
	};*/
	
	private Runnable mHeartbeatTask = new Runnable() {
		   public void run() {
			   mHandler.removeCallbacks(mHeartbeatTask);
			   WifiManager wifi = (WifiManager)getSystemService(Context.WIFI_SERVICE);
			   DhcpInfo dhcp = wifi.getDhcpInfo();
			   int[] quads = new int[4];
			   for (int k = 0; k < 4; k++) {
			      quads[k] = (int) ((dhcp.ipAddress >> k * 8) & 0xFF);
			   }
			   
			   sendXplBroadcast("xpl-stat", "*", "hbeat.app", "interval=1\nport=3865\nremote-ip="+quads[0]+"."+quads[1]+"."+quads[2]+"."+quads[3]);
			   mHandler.postDelayed(mHeartbeatTask, 60000);
		   }
	};

	private Runnable mOpenDoorTask = new Runnable() {
		   public void run() {
			   mHandler.removeCallbacks(mOpenDoorTask);
			   sendCommand(COMMAND_OPEN_DOOR,(byte)0,0);
		   }
	};

	private Runnable mCloseDoorTask = new Runnable() {
		   public void run() {
			   mHandler.removeCallbacks(mCloseDoorTask);
			   sendCommand(COMMAND_CLOSE_DOOR,(byte)0,0);
		   }
	};
	
	private Runnable mTakePictureTask = new Runnable() {
	   public void run() {
		   mHandler.removeCallbacks(mTakePictureTask);
		   camera.autoFocus(new Camera.AutoFocusCallback() {
				public void onAutoFocus(boolean success, Camera camera) {
					   camera.takePicture(null, null, mPictureCallback);
				}
		   });
	   }
	};
	
	private Camera.PictureCallback mPictureCallback = new Camera.PictureCallback() {
		
		public void onPictureTaken(byte[] data, Camera camera) {
			try {
				camera.startPreview();
				File sdCard = Environment.getExternalStorageDirectory();
				File dir = new File (sdCard.getAbsolutePath() + "/gardenpics");
				dir.mkdirs();
				mLastImageFilename = System.currentTimeMillis() + ".jpg";
				File file = new File(dir, mLastImageFilename);
	
				FileOutputStream f = new FileOutputStream(file);
				f.write(data);
				f.close();
				Thread thread = new Thread(null, mUploadImageTask, "GardenController-Uploader");
				thread.start();
			} catch (Exception e) {
				
			}
		}
	};

	private static String readerToString(BufferedReader reader) throws IOException {
		StringBuilder sb = new StringBuilder();
		String line;
		while ((line = reader.readLine()) != null) {
			sb.append(line).append('\n');
		}
		return sb.toString();
	}
	
	private Runnable mUploadImageTask = new Runnable() {
		public void run() {
			HttpURLConnection connection = null;
			DataOutputStream outputStream = null;
			BufferedReader reader = null;
			String response = "";

			String pathToOurFile = Environment.getExternalStorageDirectory().getAbsolutePath() + "/gardenpics/" + mLastImageFilename;
			String urlServer = "http://192.168.11.2/~sam/garden/upload.php";
			String lineEnd = "\r\n";
			String twoHyphens = "--";
			String boundary =  "*****";

			Log.i(TAG, "Uploading " + pathToOurFile + " to " + urlServer);
			
			int bytesRead, bytesAvailable, bufferSize;
			byte[] buffer;
			int maxBufferSize = 1*1024*1024;

			try {
				FileInputStream fileInputStream = new FileInputStream(new File(pathToOurFile) );
	
				URL url = new URL(urlServer);
				connection = (HttpURLConnection) url.openConnection();
	
				// Allow Inputs & Outputs
				connection.setDoInput(true);
				connection.setDoOutput(true);
				connection.setUseCaches(false);
	
				// Enable POST method
				connection.setRequestMethod("POST");
	
				connection.setRequestProperty("Connection", "Keep-Alive");
				connection.setRequestProperty("Content-Type", "multipart/form-data;boundary="+boundary);
	
				outputStream = new DataOutputStream( connection.getOutputStream() );
				outputStream.writeBytes(twoHyphens + boundary + lineEnd);
				outputStream.writeBytes("Content-Disposition: form-data; name=\"uploadedfile\";filename=\"" + pathToOurFile +"\"" + lineEnd);
				outputStream.writeBytes(lineEnd);
	
				bytesAvailable = fileInputStream.available();
				bufferSize = Math.min(bytesAvailable, maxBufferSize);
				buffer = new byte[bufferSize];
	
				// Read file
				bytesRead = fileInputStream.read(buffer, 0, bufferSize);
	
				while (bytesRead > 0) {
					outputStream.write(buffer, 0, bufferSize);
					bytesAvailable = fileInputStream.available();
					bufferSize = Math.min(bytesAvailable, maxBufferSize);
					bytesRead = fileInputStream.read(buffer, 0, bufferSize);
				}
	
				outputStream.writeBytes(lineEnd);
				outputStream.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);
	
				fileInputStream.close();
				outputStream.flush();
				outputStream.close();
				
				Log.i(TAG, "Upload complete. Status: " + connection.getResponseCode() + " " + connection.getResponseMessage());
				
				try {
					if(connection.getInputStream() != null)
						reader = new BufferedReader(new InputStreamReader(connection.getInputStream()), 512);
				} catch (IOException e) {
					if(connection.getErrorStream() != null)
						reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()), 512);
				}

				if(reader != null) {
					response = readerToString(reader);
					reader.close();
				}
				
				Log.i(TAG, "Response: " + response);
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
	};
	
	public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) w / h;
        List<Size> sizes = camera.getParameters().getSupportedPreviewSizes();
        if (sizes == null) return;

        Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        int targetHeight = h;

        // Try to find an size match aspect ratio and size
        for (Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.height - targetHeight) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - targetHeight);
            }
        }

        // Cannot find the one match the aspect ratio, ignore the requirement
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Size size : sizes) {
                if (Math.abs(size.height - targetHeight) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - targetHeight);
                }
            }
        }
        
        Log.i("Garden", "Creating camera preview size: " + optimalSize.width + "x" + optimalSize.height);
        
        Camera.Parameters parameters = camera.getParameters();
		parameters.setPreviewSize(optimalSize.width, optimalSize.height);
		camera.setParameters(parameters);
		camera.startPreview();
	}
	
	public void surfaceCreated(SurfaceHolder holder) {
		try {
			camera = Camera.open();
			camera.setPreviewDisplay(holder);

		    int rotation = getWindowManager().getDefaultDisplay().getRotation();
		    int degrees = 0;
		    switch (rotation) {
		        case Surface.ROTATION_0: degrees = 0; break;
		        case Surface.ROTATION_90: degrees = 90; break;
		        case Surface.ROTATION_180: degrees = 180; break;
		        case Surface.ROTATION_270: degrees = 270; break;
		    }
		    
		    android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
		    android.hardware.Camera.getCameraInfo(0, info);
		    camera.setDisplayOrientation((info.orientation - degrees + 360) % 360);
	        Camera.Parameters parameters = camera.getParameters();
	        parameters.setRotation((info.orientation - degrees + 360) % 360);
	        parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
	        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
	        parameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_FLUORESCENT);
			camera.setParameters(parameters);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void surfaceDestroyed(SurfaceHolder holder) {
		camera.stopPreview();
		camera.release();
	}
}