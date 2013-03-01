package com.dtomasiewicz.antchirp;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.dsi.ant.exception.*;
import com.dsi.ant.AntInterface;
import com.dsi.ant.AntInterfaceIntent;
import com.dsi.ant.AntMesg;
import com.dsi.ant.AntDefine;

/**
 * This class handles connecting to the AntRadio service, setting up the channels,
 * and processing Ant events.
 */
public class AntPlusManager {
    
    /**
     * Defines the interface needed to work with all call backs this class makes
     */
    public interface Callbacks
    {
        public void errorCallback();
        public void notifyAntStateChanged();
        public void notifyChannelStateChanged(byte channel);
        public void notifyChannelDataChanged(byte channel);
    }
    
    /** The Log Tag. */
    public static final String TAG = "ANTApp";
    
    /** The interface to the ANT radio. */
    private AntInterface mAntReceiver;
    
    /** Is the ANT background service connected. */
    private boolean mServiceConnected = false;
    
    /** Stores which ANT status Intents to receive. */
    private IntentFilter statusIntentFilter;
    
    /** Flag to know if an ANT Reset was triggered by this application. */
    private boolean mAntResetSent = false;
    
    /** Flag if waiting for ANT_ENABLED. Default is now false, We assume ANT is disabled until told otherwise.*/
    private boolean mEnabling = false;
    
    /** Flag if waiting for ANT_DISABLED. Default is false, will be set to true when a disable is attempted. */
    private boolean mDisabling = false;
    
    // ANT Channels
    /** The ANT channel for the Geocache */
    public static final byte GEO_CHANNEL = (byte) 0;
    
    /** ANT+ device type for a Geocache */
    private static final byte GEO_DEVICE_TYPE = 0x13;
    
    /** ANT+ channel period for a Geocache */
    private static final short GEO_PERIOD = 8192;
    
    //TODO: This string will eventually be provided by the system or by AntLib
    /** String used to represent ant in the radios list. */
    private static final String RADIO_ANT = "ant";
    
    /** Description of ANT's current state */
    private String mAntStateText = "";
    
    /** Possible states of a device channel */
    public enum ChannelStates
    {
       /** Channel was explicitly closed or has not been opened */
       CLOSED,
       
       /** User has requested we open the channel, but we are waiting for a reset */
       PENDING_OPEN,
       
       /** Channel is opened, but we have not received any data yet */
       SEARCHING,
       
       /** Channel is opened and has received status data from the device most recently */
       TRACKING_STATUS,
       
       /** Channel is opened and has received measurement data most recently */
       TRACKING_DATA,
       
       /** Channel is closed as the result of a search timeout */
       OFFLINE
    }

    /** Current state of the Geocache channel */
    private ChannelStates mGeoState = ChannelStates.CLOSED;
    
    /** Last measured parameters from Geocache device */
    private String mGeoID = "";
    private long mGeoPIN = 0;
    private int mGeoTotalPages = 1;
    private int mGeoLatitude = 0;
    private int mGeoLongitude = 0;
    private String mGeoHint = "";
    private int mGeoLoggedVisits = 0;
    
    //Flags used for deferred opening of channels
    /** Flag indicating that opening of the GEO channel was deferred */
    private boolean mDeferredGeoStart = false;
    
    /** GEO device number. */
    private short mDeviceNumberGEO;
    
    /** Devices must be within this bin to be found during (proximity) search. */
    private byte mProximityThreshold;
    
    private ChannelConfiguration channelConfig[];
    
    //TODO You will want to set a separate threshold for screen off and (if desired) screen on.
    /** Data buffered for event buffering before flush. */
    private short mBufferThreshold;
    
    /** If this application has control of the ANT Interface. */
    private boolean mClaimedAntInterface;
    
    private static final byte GEO_PAGE_ID = 0; // 5.3
    private static final byte GEO_PAGE_PIN = 1; // 5.4
    private static final byte GEO_PAGE_PROG_MIN = 2; // 5.5
    private static final byte GEO_PAGE_PROG_MAX = 31; // 5.5
    private static final byte GEO_PAGE_AUTH = 32; // 5.11
    
    private static final byte GEO_PROG_LATITUDE = 0; // 5.6
    private static final byte GEO_PROG_LONGITUDE = 1; // 5.7
    private static final byte GEO_PROG_HINT = 2; // 5.8
    private static final byte GEO_PROG_LOGGED_VISITS = 4; // 5.10
    
    private Context mContext;
    
    private Callbacks mCallbackSink;
    
    /**
     * Default Constructor
     */
    public AntPlusManager()
    {
        Log.d(TAG, "AntChannelManager: enter Constructor");
        
        channelConfig = new ChannelConfiguration[3];
        
        //Set initial state values
        mGeoState = ChannelStates.CLOSED;
        channelConfig[GEO_CHANNEL] = new ChannelConfiguration();
        
        mClaimedAntInterface = false;
        
        // ANT intent broadcasts.
        statusIntentFilter = new IntentFilter();
        statusIntentFilter.addAction(AntInterfaceIntent.ANT_ENABLED_ACTION);
        statusIntentFilter.addAction(AntInterfaceIntent.ANT_ENABLING_ACTION);
        statusIntentFilter.addAction(AntInterfaceIntent.ANT_DISABLED_ACTION);
        statusIntentFilter.addAction(AntInterfaceIntent.ANT_DISABLING_ACTION);
        statusIntentFilter.addAction(AntInterfaceIntent.ANT_RESET_ACTION);
        statusIntentFilter.addAction(AntInterfaceIntent.ANT_INTERFACE_CLAIMED_ACTION);
        statusIntentFilter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        
        mAntReceiver = new AntInterface();
    }
    
    
    /**
     * Creates the connection to the ANT service back-end.
     */
    public boolean start(Context context)
    {
        boolean initialised = false;
        
        mContext = context;
        
        if(AntInterface.hasAntSupport(mContext))
        {
            mContext.registerReceiver(mAntStatusReceiver, statusIntentFilter);
            
            if(!mAntReceiver.initService(mContext, mAntServiceListener))
            {
                // Need the ANT Radio Service installed.
                Log.e(TAG, "AntChannelManager Constructor: No ANT Service.");
                requestServiceInstall();
            }
            else
            {
                mServiceConnected = mAntReceiver.isServiceConnected();

                if(mServiceConnected)
                {
                    try
                    {
                        mClaimedAntInterface = mAntReceiver.hasClaimedInterface();
                        if(mClaimedAntInterface)
                        {
                            receiveAntRxMessages(true);
                        }
                    }
                    catch (AntInterfaceException e)
                    {
                        antError();
                    }
                }
                
                initialised = true;
            }
        }
        
        return initialised;
    }
    
    /**
     * Requests that the user install the needed service for ant
     */
    private void requestServiceInstall()
    {
        Toast installNotification = Toast.makeText(mContext, mContext.getResources().getString(R.string.Notify_Service_Required), Toast.LENGTH_LONG);
        installNotification.show();

        AntInterface.goToMarket(mContext);
    }
    
    public void setCallbacks(Callbacks callbacks)
    {
        mCallbackSink = callbacks;
    }
    
    //Getters and setters
    
    public boolean isServiceConnected()
    {
        return mServiceConnected;
    }

    public short getDeviceNumberGEO()
    {
        return mDeviceNumberGEO;
    }

    public void setDeviceNumberGEO(short deviceNumberGEO)
    {
        this.mDeviceNumberGEO = deviceNumberGEO;
    }
    
    public byte getProximityThreshold()
    {
        return mProximityThreshold;
    }

    public void setProximityThreshold(byte proximityThreshold)
    {
        this.mProximityThreshold = proximityThreshold;
    }

    public short getBufferThreshold()
    {
        return mBufferThreshold;
    }

    public void setBufferThreshold(short bufferThreshold)
    {
        this.mBufferThreshold = bufferThreshold;
    }

    public ChannelStates getGeoState()
    {
        return mGeoState;
    }
    
    public String getGeoID() {
    	return mGeoID;
    }
    
    public long getGeoPIN() {
    	return mGeoPIN;
    }
    
    public int getGeoLatitude() {
    	return mGeoLatitude;
    }
    
    public int getGeoLongitude() {
    	return mGeoLongitude;
    }
    
    public String getGeoHint() {
    	return mGeoHint;
    }
    
    public int getGeoLoggedVisits() {
    	return mGeoLoggedVisits;
    }

    public String getAntStateText()
    {
        return mAntStateText;
    }
    
    /**
     * Checks if ANT can be used by this application
     * Sets the AntState string to reflect current status.
     * @return true if this application can use the ANT chip, false otherwise.
     */
    public boolean checkAntState()
    {
        try
        {
            if(!AntInterface.hasAntSupport(mContext))
            {
                Log.w(TAG, "updateDisplay: ANT not supported");

                mAntStateText = mContext.getString(R.string.Text_ANT_Not_Supported);
                return false;
            }
            else if(isAirPlaneModeOn())
            {
                mAntStateText = mContext.getString(R.string.Text_Airplane_Mode);
                return false;
            }
            else if(mEnabling)
            {
                mAntStateText = mContext.getString(R.string.Text_Enabling);
                return false;
            }
            else if(mDisabling)
            {
                mAntStateText = mContext.getString(R.string.Text_Disabling);
                return false;
            }
            else if(mServiceConnected)
            {
                if(!mAntReceiver.isEnabled())
                {
                    mAntStateText = mContext.getString(R.string.Text_Disabled);
                    return false;
                }
                if(mAntReceiver.hasClaimedInterface() || mAntReceiver.claimInterface())
                {
                    return true;
                }
                else
                {
                    mAntStateText = mContext.getString(R.string.Text_ANT_In_Use);
                    return false;
                }
            }
            else
            {
                Log.w(TAG, "updateDisplay: Service not connected");

                mAntStateText = mContext.getString(R.string.Text_Disabled);
                return false;
            }
        }
        catch(AntInterfaceException e)
        {
            antError();
            return false;
        }
    }

    /**
     * Attempts to claim the Ant interface
     */
    public void tryClaimAnt()
    {
        try
        {
            mAntReceiver.requestForceClaimInterface(mContext.getResources().getString(R.string.app_name));
        }
        catch(AntInterfaceException e)
        {
            antError();
        }
    }

    /**
     * Unregisters all our receivers in preparation for application shutdown
     */
    public void shutDown()
    {
        try
        {
            mContext.unregisterReceiver(mAntStatusReceiver);
        }
        catch(IllegalArgumentException e)
        {
            // Receiver wasn't registered, ignore as that's what we wanted anyway
        }
        
        receiveAntRxMessages(false);
        
        if(mServiceConnected)
        {
            try
            {
                if(mClaimedAntInterface)
                {
                    Log.d(TAG, "AntChannelManager.shutDown: Releasing interface");

                    mAntReceiver.releaseInterface();
                }

                mAntReceiver.stopRequestForceClaimInterface();
            }
            catch(AntServiceNotConnectedException e)
            {
                // Ignore as we are disconnecting the service/closing the app anyway
            }
            catch(AntInterfaceException e)
            {
               Log.w(TAG, "Exception in AntChannelManager.shutDown", e);
            }
            
            mAntReceiver.releaseService();
        }
    }

    /**
     * Class for receiving notifications about ANT service state.
     */
    private AntInterface.ServiceListener mAntServiceListener = new AntInterface.ServiceListener()
    {
        public void onServiceConnected()
        {
            Log.d(TAG, "mAntServiceListener onServiceConnected()");

            mServiceConnected = true;

            try
            {

                mClaimedAntInterface = mAntReceiver.hasClaimedInterface();

                if (mClaimedAntInterface)
                {
                    // mAntMessageReceiver should be registered any time we have
                    // control of the ANT Interface
                    receiveAntRxMessages(true);
                } else
                {
                    // Need to claim the ANT Interface if it is available, now
                    // the service is connected
                    mClaimedAntInterface = mAntReceiver.claimInterface();
                }
            } catch (AntInterfaceException e)
            {
                antError();
            }

            Log.d(TAG, "mAntServiceListener Displaying icons only if radio enabled");
            if(mCallbackSink != null)
                mCallbackSink.notifyAntStateChanged();
        }

        public void onServiceDisconnected()
        {
            Log.d(TAG, "mAntServiceListener onServiceDisconnected()");

            mServiceConnected = false;
            mEnabling = false;
            mDisabling = false;

            if (mClaimedAntInterface)
            {
                receiveAntRxMessages(false);
            }

            if(mCallbackSink != null)
                mCallbackSink.notifyAntStateChanged();
        }
    };
    
    /**
     * Configure the ANT radio to the user settings.
     */
    public void setAntConfiguration()
    {
        try
        {
            if(mServiceConnected && mClaimedAntInterface && mAntReceiver.isEnabled())
            {
                try
                {
                    // Event Buffering Configuration
                    if(mBufferThreshold > 0)
                    {
                        //TODO For easy demonstration will set screen on and screen off thresholds to the same value.
                        // No buffering by interval here.
                        mAntReceiver.ANTConfigEventBuffering((short)0xFFFF, mBufferThreshold, (short)0xFFFF, mBufferThreshold);
                    }
                    else
                    {
                        mAntReceiver.ANTDisableEventBuffering();
                    }
                }
                catch(AntInterfaceException e)
                {
                    Log.e(TAG, "Could not configure event buffering", e);
                }
            }
            else
            {
                Log.i(TAG, "Can't set event buffering right now.");
            }
        } catch (AntInterfaceException e)
        {
            Log.e(TAG, "Problem checking enabled state.");
        }
    }
    
    /**
     * Display to user that an error has occured communicating with ANT Radio.
     */
    private void antError()
    {
        mAntStateText = mContext.getString(R.string.Text_ANT_Error);
        if(mCallbackSink != null)
            mCallbackSink.errorCallback();
    }
    
    /**
     * Opens a given channel using the proper configuration for the channel's sensor type.
     * @param channel The channel to Open.
     * @param deferToNextReset If true, channel will not open until the next reset.
     */
    public void openChannel(byte channel, boolean deferToNextReset)
    {
        Log.i(TAG, "Starting service.");
        mContext.startService(new Intent(mContext, ANTPlusService.class));
        if (!deferToNextReset)
        {
            channelConfig[channel].deviceNumber = 0;
            channelConfig[channel].deviceType = 0;
            channelConfig[channel].TransmissionType = 0; // Set to 0 for wild card search
            channelConfig[channel].period = 0;
            channelConfig[channel].freq = 57; // 2457Mhz (ANT+ frequency)
            channelConfig[channel].proxSearch = mProximityThreshold;
            switch (channel)
            {
                case GEO_CHANNEL:
                    channelConfig[channel].deviceNumber = mDeviceNumberGEO;
                    channelConfig[channel].deviceType = GEO_DEVICE_TYPE;
                    channelConfig[channel].period = GEO_PERIOD;
                    mGeoState = ChannelStates.PENDING_OPEN;
                    break;
            }
            if(mCallbackSink != null)
                mCallbackSink.notifyChannelStateChanged(channel);
            // Configure and open channel
            antChannelSetup(
                    (byte) 0x01, // Network: 1 (ANT+)
                    channel // channelConfig[channel] holds all the required info
                    );
        }
        else
        {
            switch(channel)
            {
                case GEO_CHANNEL:
                    mDeferredGeoStart = true;
                    mGeoState = ChannelStates.PENDING_OPEN;
                    break;
            }
        }
    }
    
    /**
     * Attempts to cleanly close a specified channel 
     * @param channel The channel to close.
     */
    public void closeChannel(byte channel)
    {
        channelConfig[channel].isInitializing = false;
        channelConfig[channel].isDeinitializing = true;

        switch(channel)
        {
            case GEO_CHANNEL:
                mGeoState = ChannelStates.CLOSED;
                break;
        }
        if(mCallbackSink != null)
            mCallbackSink.notifyChannelStateChanged(channel);
        try
        {
           mAntReceiver.ANTCloseChannel(channel);
           // Unassign channel after getting channel closed event
        }
        catch (AntInterfaceException e)
        {
           Log.w(TAG, "closeChannel: could not cleanly close channel " + channel + ".");
           antError();
        }
        if(mGeoState == ChannelStates.CLOSED || mGeoState == ChannelStates.OFFLINE)
        {
            Log.i(TAG, "Stopping service.");
            mContext.stopService(new Intent(mContext, ANTPlusService.class));
        }
    }
    
    /**
     * Resets the channel state machines, used in error recovery.
     */
    public void clearChannelStates()
    {
        Log.i(TAG, "Stopping service.");
        mContext.stopService(new Intent(mContext, ANTPlusService.class));
        mGeoState = ChannelStates.CLOSED;
        if(mCallbackSink != null)
        {
            mCallbackSink.notifyChannelStateChanged(GEO_CHANNEL);
        }
    }
    
    /** check to see if a channel is open */
    public boolean isChannelOpen(byte channel)
    {
        switch(channel)
        {
            case GEO_CHANNEL:
                if(mGeoState == ChannelStates.CLOSED || mGeoState == ChannelStates.OFFLINE)
                    return false;
                break;
            default:
                return false;
        }
        return true;
    }
    
    /** request an ANT reset */
    public void requestReset()
    {
        try
        {
            mAntResetSent = true;
            mAntReceiver.ANTResetSystem();
            setAntConfiguration();
        } catch (AntInterfaceException e) {
            Log.e(TAG, "requestReset: Could not reset ANT", e);
            mAntResetSent = false;
            //Cancel pending channel open requests
            if(mDeferredGeoStart)
            {
                mDeferredGeoStart = false;
                mGeoState = ChannelStates.CLOSED;
                if(mCallbackSink != null)
                    mCallbackSink.notifyChannelStateChanged(GEO_CHANNEL);
            }
        }
    }
    
    /**
     * Check if ANT is enabled
     * @return True if ANT is enabled, false otherwise.
     */
    public boolean isEnabled()
    {
        if(mAntReceiver == null || !mAntReceiver.isServiceConnected())
            return false;
        try
        {
            return mAntReceiver.isEnabled();
        } catch (AntInterfaceException e)
        {
            Log.w(TAG, "Problem checking enabled state.");
            return false;
        }
    }
    
    /**
     * Attempt to enable the ANT chip.
     */
    public void doEnable()
    {
        if(mAntReceiver == null || mDisabling || isAirPlaneModeOn())
            return;
        try
        {
            mAntReceiver.enable();
        } catch (AntInterfaceException e)
        {
            //Not much error recovery possible.
            Log.e(TAG, "Could not enable ANT.");
            return;
        }
    }
    
    /**
     * Attempt to disable the ANT chip.
     */
    public void doDisable()
    {
        if(mAntReceiver == null || mEnabling)
            return;
        try
        {
            mAntReceiver.disable();
        } catch (AntInterfaceException e)
        {
            //Not much error recovery possible.
            Log.e(TAG, "Could not enable ANT.");
            return;
        }
    }
    
    /** Receives all of the ANT status intents. */
    private final BroadcastReceiver mAntStatusReceiver = new BroadcastReceiver() 
    {      
       public void onReceive(Context context, Intent intent) 
       {
          String ANTAction = intent.getAction();

          Log.d(TAG, "enter onReceive: " + ANTAction);
          if (ANTAction.equals(AntInterfaceIntent.ANT_ENABLING_ACTION))
          {
              Log.i(TAG, "onReceive: ANT ENABLING");
              mEnabling = true;
              mDisabling = false;
              mAntStateText = mContext.getString(R.string.Text_Enabling);
              if(mCallbackSink != null)
                  mCallbackSink.notifyAntStateChanged();
          }
          else if (ANTAction.equals(AntInterfaceIntent.ANT_ENABLED_ACTION)) 
          {
             Log.i(TAG, "onReceive: ANT ENABLED");
             
             mEnabling = false;
             mDisabling = false;
             if(mCallbackSink != null)
                 mCallbackSink.notifyAntStateChanged();
          }
          else if (ANTAction.equals(AntInterfaceIntent.ANT_DISABLING_ACTION))
          {
              Log.i(TAG, "onReceive: ANT DISABLING");
              mEnabling = false;
              mDisabling = true;
              mAntStateText = mContext.getString(R.string.Text_Disabling);
              if(mCallbackSink != null)
                  mCallbackSink.notifyAntStateChanged();
          }
          else if (ANTAction.equals(AntInterfaceIntent.ANT_DISABLED_ACTION)) 
          {
             Log.i(TAG, "onReceive: ANT DISABLED");
             mGeoState = ChannelStates.CLOSED;
             mAntStateText = mContext.getString(R.string.Text_Disabled);
             
             mEnabling = false;
             mDisabling = false;
             
             if(mCallbackSink != null)
             {
                 mCallbackSink.notifyChannelStateChanged(GEO_CHANNEL);
                 mCallbackSink.notifyAntStateChanged();
             }
             Log.i(TAG, "Stopping service.");
             mContext.stopService(new Intent(mContext, ANTPlusService.class));
          }
          else if (ANTAction.equals(AntInterfaceIntent.ANT_RESET_ACTION))
          {
             Log.d(TAG, "onReceive: ANT RESET");
             
             Log.i(TAG, "Stopping service.");
             mContext.stopService(new Intent(mContext, ANTPlusService.class));
             
             if(false == mAntResetSent)
             {
                //Someone else triggered an ANT reset
                Log.d(TAG, "onReceive: ANT RESET: Resetting state");
                
                if(mGeoState != ChannelStates.CLOSED)
                {
                   mGeoState = ChannelStates.CLOSED;
                   if(mCallbackSink != null)
                       mCallbackSink.notifyChannelStateChanged(GEO_CHANNEL);
                }
             }
             else
             {
                mAntResetSent = false;
                //Reconfigure event buffering
                setAntConfiguration();
                //Check if opening a channel was deferred, if so open it now.
                if(mDeferredGeoStart)
                {
                    openChannel(GEO_CHANNEL, false);
                    mDeferredGeoStart = false;
                }
             }
          }
          else if (ANTAction.equals(AntInterfaceIntent.ANT_INTERFACE_CLAIMED_ACTION)) 
          {
             Log.i(TAG, "onReceive: ANT INTERFACE CLAIMED");
             
             boolean wasClaimed = mClaimedAntInterface;
             
             // Could also read ANT_INTERFACE_CLAIMED_PID from intent and see if it matches the current process PID.
             try
             {
                 mClaimedAntInterface = mAntReceiver.hasClaimedInterface();

                 if(mClaimedAntInterface)
                 {
                     Log.i(TAG, "onReceive: ANT Interface claimed");

                     receiveAntRxMessages(true);
                 }
                 else
                 {
                     // Another application claimed the ANT Interface...
                     if(wasClaimed)
                     {
                         // ...and we had control before that.  
                         Log.i(TAG, "onReceive: ANT Interface released");
                         
                         Log.i(TAG, "Stopping service.");
                         mContext.stopService(new Intent(mContext, ANTPlusService.class));

                         receiveAntRxMessages(false);
                         
                         mAntStateText = mContext.getString(R.string.Text_ANT_In_Use);
                         if(mCallbackSink != null)
                             mCallbackSink.notifyAntStateChanged();
                     }
                 }
             }
             catch(AntInterfaceException e)
             {
                 antError();
             }
          }
          else if (ANTAction.equals(Intent.ACTION_AIRPLANE_MODE_CHANGED))
          {
              Log.i(TAG, "onReceive: AIR_PLANE_MODE_CHANGED");
              if(isAirPlaneModeOn())
              {
                  mGeoState = ChannelStates.CLOSED;
                  mAntStateText = mContext.getString(R.string.Text_Airplane_Mode);
                  
                  Log.i(TAG, "Stopping service.");
                  mContext.stopService(new Intent(mContext, ANTPlusService.class));
                  
                  if(mCallbackSink != null)
                  {
                      mCallbackSink.notifyChannelStateChanged(GEO_CHANNEL);
                      mCallbackSink.notifyAntStateChanged();
                  }
              }
              else
              {
                  if(mCallbackSink != null)
                      mCallbackSink.notifyAntStateChanged();
              }
          }
          if(mCallbackSink != null)
              mCallbackSink.notifyAntStateChanged();
       }
    };
    
    public static String getHexString(byte[] data)
    {
        if(null == data)
        {
            return "";
        }

        StringBuffer hexString = new StringBuffer();
        for(int i = 0;i < data.length; i++)
        {
           hexString.append("[").append(String.format("%02X", data[i] & 0xFF)).append("]");
        }

        return hexString.toString();
    }
    
    /** Receives all of the ANT message intents and dispatches to the proper handler. */
    private final BroadcastReceiver mAntMessageReceiver = new BroadcastReceiver() 
    {      
       Context mContext;

       public void onReceive(Context context, Intent intent) 
       {
          mContext = context;
          String ANTAction = intent.getAction();

          Log.d(TAG, "enter onReceive: " + ANTAction);
          if (ANTAction.equals(AntInterfaceIntent.ANT_RX_MESSAGE_ACTION)) 
          {
             Log.d(TAG, "onReceive: ANT RX MESSAGE");

             byte[] ANTRxMessage = intent.getByteArrayExtra(AntInterfaceIntent.ANT_MESSAGE);

             Log.d(TAG, "Rx:"+ getHexString(ANTRxMessage));

             switch(ANTRxMessage[AntMesg.MESG_ID_OFFSET])
             {
                 case AntMesg.MESG_STARTUP_MESG_ID:
                     break;
                 case AntMesg.MESG_BROADCAST_DATA_ID:
                 case AntMesg.MESG_ACKNOWLEDGED_DATA_ID:
                     byte channelNum = ANTRxMessage[AntMesg.MESG_DATA_OFFSET];
                     switch(channelNum)
                     {
                         case GEO_CHANNEL:
                             antDecodeGEO(ANTRxMessage);
                             break;
                     }
                     break;
                 case AntMesg.MESG_BURST_DATA_ID:
                     break;
                 case AntMesg.MESG_RESPONSE_EVENT_ID:
                     responseEventHandler(ANTRxMessage);
                     break;
                 case AntMesg.MESG_CHANNEL_STATUS_ID:
                     break;
                 case AntMesg.MESG_CHANNEL_ID_ID:
                     short deviceNum = (short) ((ANTRxMessage[AntMesg.MESG_DATA_OFFSET + 1]&0xFF | ((ANTRxMessage[AntMesg.MESG_DATA_OFFSET + 2]&0xFF) << 8)) & 0xFFFF);
                     switch(ANTRxMessage[AntMesg.MESG_DATA_OFFSET]) //Switch on channel number
                     {
                         case GEO_CHANNEL:
                             Log.i(TAG, "onRecieve: Received GEO device number ("+deviceNum+")");
                             mDeviceNumberGEO = deviceNum;
                             break;
                     }
                     break;
                 case AntMesg.MESG_VERSION_ID:
                     break;
                 case AntMesg.MESG_CAPABILITIES_ID:
                     break;
                 case AntMesg.MESG_GET_SERIAL_NUM_ID:
                     break;
                 case AntMesg.MESG_EXT_ACKNOWLEDGED_DATA_ID:
                     break;
                 case AntMesg.MESG_EXT_BROADCAST_DATA_ID:
                     break;
                 case AntMesg.MESG_EXT_BURST_DATA_ID:
                     break;
             }
          }
       }
       
       /**
        * Handles response and channel event messages
        * @param ANTRxMessage
        */
       private void responseEventHandler(byte[] ANTRxMessage)
       {
           // For a list of possible message codes
           // see ANT Message Protocol and Usage section 9.5.6.1
           // available from thisisant.com
           byte channelNumber = ANTRxMessage[AntMesg.MESG_DATA_OFFSET];

           if ((ANTRxMessage[AntMesg.MESG_DATA_OFFSET + 1] == AntMesg.MESG_EVENT_ID) && (ANTRxMessage[AntMesg.MESG_DATA_OFFSET + 2] == AntDefine.EVENT_RX_SEARCH_TIMEOUT))
           {
               // A channel timed out searching, unassign it
               channelConfig[channelNumber].isInitializing = false;
               channelConfig[channelNumber].isDeinitializing = false;

               switch(channelNumber)
               {
                   case GEO_CHANNEL:
                       try
                       {
                           Log.i(TAG, "responseEventHandler: Received search timeout on GEO channel");

                           mGeoState = ChannelStates.OFFLINE;
                           if(mCallbackSink != null)
                               mCallbackSink.notifyChannelStateChanged(GEO_CHANNEL);
                           mAntReceiver.ANTUnassignChannel(GEO_CHANNEL);
                       }
                       catch(AntInterfaceException e)
                       {
                           antError();
                       }
                       break;
               }
               if(mGeoState == ChannelStates.CLOSED || mGeoState == ChannelStates.OFFLINE)
               {
                   Log.i(TAG, "Stopping service.");
                   mContext.stopService(new Intent(mContext, ANTPlusService.class));
               }
           }
           
           if (channelConfig[channelNumber].isInitializing)
           {
               if (ANTRxMessage[AntMesg.MESG_DATA_OFFSET + 2] != 0) // Error response
               {
                   Log.e(TAG, String.format("Error code(%#02x) on message ID(%#02x) on channel %d", ANTRxMessage[AntMesg.MESG_DATA_OFFSET + 2], ANTRxMessage[AntMesg.MESG_DATA_OFFSET + 1], channelNumber));
               }
               else
               {
                   switch (ANTRxMessage[AntMesg.MESG_DATA_OFFSET + 1]) // Switch on Message ID
                   {
                       case AntMesg.MESG_ASSIGN_CHANNEL_ID:
                           try
                           {
                               mAntReceiver.ANTSetChannelId(channelNumber, channelConfig[channelNumber].deviceNumber, channelConfig[channelNumber].deviceType, channelConfig[channelNumber].TransmissionType);
                           }
                           catch (AntInterfaceException e)
                           {
                               antError();
                           }
                           break;
                       case AntMesg.MESG_CHANNEL_ID_ID:
                           try
                           {
                               mAntReceiver.ANTSetChannelPeriod(channelNumber, channelConfig[channelNumber].period);
                           }
                           catch (AntInterfaceException e)
                           {
                               antError();
                           }
                           break;
                       case AntMesg.MESG_CHANNEL_MESG_PERIOD_ID:
                           try
                           {
                               mAntReceiver.ANTSetChannelRFFreq(channelNumber, channelConfig[channelNumber].freq);
                           }
                           catch (AntInterfaceException e)
                           {
                               antError();
                           }
                           break;
                       case AntMesg.MESG_CHANNEL_RADIO_FREQ_ID:
                           try
                           {
                               mAntReceiver.ANTSetChannelSearchTimeout(channelNumber, (byte)0); // Disable high priority search
                           }
                           catch (AntInterfaceException e)
                           {
                               antError();
                           }
                           break;
                       case AntMesg.MESG_CHANNEL_SEARCH_TIMEOUT_ID:
                           try
                           {
                               mAntReceiver.ANTSetLowPriorityChannelSearchTimeout(channelNumber,(byte) 12); // Set search timeout to 30 seconds (low priority search)
                           }
                           catch (AntInterfaceException e)
                           {
                               antError();
                           }
                           break;
                       case AntMesg.MESG_SET_LP_SEARCH_TIMEOUT_ID:
                           if (channelConfig[channelNumber].deviceNumber == ANTChirp.WILDCARD)
                           {
                               try
                               {
                                   mAntReceiver.ANTSetProximitySearch(channelNumber, channelConfig[channelNumber].proxSearch);   // Configure proximity search, if using wild card search
                               }
                               catch (AntInterfaceException e)
                               {
                                   antError();
                               }
                           }
                           else
                           {
                               try
                               {
                                   mAntReceiver.ANTOpenChannel(channelNumber);
                               }
                               catch (AntInterfaceException e)
                               {
                                   antError();
                               }
                           }
                           break;
                       case AntMesg.MESG_PROX_SEARCH_CONFIG_ID:
                           try
                           {
                               mAntReceiver.ANTOpenChannel(channelNumber);
                           }
                           catch (AntInterfaceException e)
                           {
                               antError();
                           }
                           break;
                       case AntMesg.MESG_OPEN_CHANNEL_ID:
                           channelConfig[channelNumber].isInitializing = false;
                           switch (channelNumber)
                           {
                               case GEO_CHANNEL:
                                   mGeoState = ChannelStates.SEARCHING;
                                   if(mCallbackSink != null)
                                       mCallbackSink.notifyChannelStateChanged(GEO_CHANNEL);
                                   break;
                           }
                   }
               }
           }
           else if (channelConfig[channelNumber].isDeinitializing)
           {
               if ((ANTRxMessage[AntMesg.MESG_DATA_OFFSET + 1] == AntMesg.MESG_EVENT_ID) && (ANTRxMessage[AntMesg.MESG_DATA_OFFSET + 2] == AntDefine.EVENT_CHANNEL_CLOSED))
               {
                   try
                   {
                       mAntReceiver.ANTUnassignChannel(channelNumber);
                   }
                   catch (AntInterfaceException e)
                   {
                       antError();
                   }
               }
               else if ((ANTRxMessage[AntMesg.MESG_DATA_OFFSET + 1] == AntMesg.MESG_UNASSIGN_CHANNEL_ID) && (ANTRxMessage[AntMesg.MESG_DATA_OFFSET + 2] == AntDefine.RESPONSE_NO_ERROR))
               {
                   channelConfig[channelNumber].isDeinitializing = false;
               }
           }
       }
       
       /**
        * Decode ANT+ Geocache messages.
        *
        * @param ANTRxMessage the received ANT message.
        */
       private void antDecodeGEO(byte[] ANTRxMessage)
       {
         Log.d(TAG, "antDecodeGEO start");
          
         Log.d(TAG, "antDecodeGEO: Received broadcast");
         
         if(mGeoState != ChannelStates.CLOSED)
         {
            Log.d(TAG, "antDecodeGEO: Tracking data");

            mGeoState = ChannelStates.TRACKING_DATA;
            if(mCallbackSink != null)
                mCallbackSink.notifyChannelStateChanged(GEO_CHANNEL);
         }

         if(mDeviceNumberGEO == ANTChirp.WILDCARD)
         {
             try
             {
                 Log.i(TAG, "antDecodeGEO: Requesting device number");
                 mAntReceiver.ANTRequestMessage(GEO_CHANNEL, AntMesg.MESG_CHANNEL_ID_ID);
             }
             catch(AntInterfaceException e)
             {
                 antError();
             }
         }
         
         byte pageNum = ANTRxMessage[AntMesg.MESG_DATA_OFFSET+1];
         Log.i(TAG, "Received Geo page "+((int)pageNum));
         
        
         if(pageNum >= GEO_PAGE_PROG_MIN && pageNum <= GEO_PAGE_PROG_MAX) {
        	 byte dataId = ANTRxMessage[AntMesg.MESG_DATA_OFFSET+2];
        	 byte[] data = new byte[6];
        	 System.arraycopy(ANTRxMessage, AntMesg.MESG_DATA_OFFSET+3, data, 0, 6);
        	 switch(dataId) {
        	 case GEO_PROG_LATITUDE:
        		 antDecodeGeoLatitude(data);
        		 break;
        	 case GEO_PROG_LONGITUDE:
        		 antDecodeGeoLongitude(data);
        		 break;
        	 case GEO_PROG_HINT:
        		 antDecodeGeoHint(data);
        		 break;
        	 case GEO_PROG_LOGGED_VISITS:
        		 antDecodeGeoLoggedVisits(data);
        		 break;
        	 }
         } else {
        	 byte[] data = new byte[7];
        	 System.arraycopy(ANTRxMessage, AntMesg.MESG_DATA_OFFSET+2, data, 0, 7);
        	 if(pageNum == GEO_PAGE_ID) {
            	 antDecodeGeoID(data);
            	 requestGeoPage(GEO_PAGE_PIN);
             } else if(pageNum == GEO_PAGE_PIN) {
            	 antDecodeGeoPIN(data);
             } else if(pageNum == GEO_PAGE_AUTH) {
        	 antDecodeGeoAuth(data);
	         } else {
	        	 // TODO ERROR!
	         }
         }
             
          Log.d(TAG, "antDecodeGEO end");
       }
    };
    
    private void requestGeoPage(byte page) {
    	Log.d(TAG, "Requesting Geo page "+((int)page));
    	
    	byte[] request = {
    		GeoMesg.MESG_REQUEST_DATA_PAGE,
    		(byte) 0xFF, // Reserved
    		(byte) 0xFF, // Reserved
    		(byte) 0x00, // Subfield 1
    		(byte) 0x00, // Subfield 2
    		(byte) 0x01, // Requested Transmission Response
    		page,        // Requested Page Number
    		(byte) 0x01  // Command Type
    	};
    	
		try {
			mAntReceiver.ANTSendAcknowledgedData(GEO_CHANNEL, request);
		} catch (AntInterfaceException e) {
			antError();
		}
    }
    
    private void antDecodeGeoID(byte[] message) {
    	mGeoID = decodeAscii6(message, 9);
    }
    
    private String decodeAscii6(byte[] str, int length) {
    	Log.d(TAG, "decoding Rx:"+getHexString(str));
    	byte[] chars = new byte[length];
    	for(int i = 0; i < chars.length; i++) {
    		int left = 6*i;
    		byte leftByte = str[left/8];
    		byte rightByte = str[(left+5)/8];
    		int nLeftBits = (2*i)%8;
    		int nRightBits = 6-nLeftBits;
    		byte leftMask = (byte) (0x3F >> nRightBits);
    		byte leftBits = (byte) ((leftByte & leftMask) << nRightBits);
    		byte rightBits = (byte) ((rightByte & 0xFF) >> 2+nLeftBits);
    		chars[i] = (byte) ((leftBits | rightBits) + 0x20);
    	}
    	try {
    		// US-ASCII is a 7-bit encoding scheme, so to simplify a bit, we
    		// translate to 8-bit then decode with UTF-8 which is compatible
    		// with ASCII8.
    		return new String(chars, "UTF-8");
    	} catch(UnsupportedEncodingException e) {
    		return "";
    	}
    }
    
    private void antDecodeGeoPIN(byte[] message) {
    	mGeoPIN = ByteBuffer.wrap(message).order(ByteOrder.LITTLE_ENDIAN).getInt(1);
    	mGeoTotalPages = message[5] & 0xFF;
    	
    	// update data from all other pages
    	for(int i = GEO_PAGE_PIN+1; i < mGeoTotalPages; i++) {
    		requestGeoPage((byte) i);
    	}
    }
    
    private void antDecodeGeoLatitude(byte[] message) {
    	mGeoLatitude = ByteBuffer.wrap(message).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
    
    private void antDecodeGeoLongitude(byte[] message) {
    	mGeoLongitude = ByteBuffer.wrap(message).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
    
    private void antDecodeGeoHint(byte[] message) {
    	// TODO
    }
    
    private void antDecodeGeoLoggedVisits(byte[] message) {
    	// TODO
    	
    }
    
    private void antDecodeGeoAuth(byte[] message) {
    	// TODO
    	
    }
    
    /**
     * ANT Channel Configuration.
     *
     * @param networkNumber the network number
     * @param channelNumber the channel number
     * @param deviceNumber the device number
     * @param deviceType the device type
     * @param txType the tx type
     * @param channelPeriod the channel period
     * @param radioFreq the radio freq
     * @param proxSearch the prox search
     * @return true, if successfully configured and opened channel
     */   
    private void antChannelSetup(byte networkNumber, byte channel)
    {
       try
       {
           channelConfig[channel].isInitializing = true;
           channelConfig[channel].isDeinitializing = false;

           mAntReceiver.ANTAssignChannel(channel, AntDefine.PARAMETER_RX_NOT_TX, networkNumber);  // Assign as slave channel on selected network (0 = public, 1 = ANT+, 2 = ANTFS)
           // The rest of the channel configuration will occur after the response is received (in responseEventHandler)
       }
       catch(AntInterfaceException aie)
       {
           antError();
       }
    }
    
    /**
     * Enable/disable receiving ANT Rx messages.
     *
     * @param register If want to register to receive the ANT Rx Messages
     */
    private void receiveAntRxMessages(boolean register)
    {
        if(register)
        {
            Log.i(TAG, "receiveAntRxMessages: START");
            mContext.registerReceiver(mAntMessageReceiver, new IntentFilter(AntInterfaceIntent.ANT_RX_MESSAGE_ACTION));
        }
        else
        {
            try
            {
                mContext.unregisterReceiver(mAntMessageReceiver);
            }
            catch(IllegalArgumentException e)
            {
                // Receiver wasn't registered, ignore as that's what we wanted anyway
            }

            Log.i(TAG, "receiveAntRxMessages: STOP");
        }
    }
    
    /**
     * Checks if ANT is sensitive to airplane mode, if airplane mode is on and if ANT is not toggleable in airplane
     * mode. Only returns true if all 3 criteria are met.
     * @return True if airplane mode is stopping ANT from being enabled, false otherwise.
     */
    private boolean isAirPlaneModeOn()
    {
        if(!Settings.System.getString(mContext.getContentResolver(),
                Settings.System.AIRPLANE_MODE_RADIOS).contains(RADIO_ANT))
            return false;
        if(Settings.System.getInt(mContext.getContentResolver(), Settings.System.AIRPLANE_MODE_ON, 0) == 0)
            return false;
        
        try
        {
            Field field = Settings.System.class.getField("AIRPLANE_MODE_TOGGLEABLE_RADIOS");
            if(Settings.System.getString(mContext.getContentResolver(),
                    (String) field.get(null)).contains(RADIO_ANT))
                return false;
            else
                return true;
        } catch(Exception e)
        {
            return true; //This is expected if the list does not yet exist so we just assume we would not be on it.
        }
    }
}
