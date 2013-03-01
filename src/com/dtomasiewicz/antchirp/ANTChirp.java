package com.dtomasiewicz.antchirp;

import java.text.DecimalFormat;

import com.dsi.ant.AntDefine;

import android.app.Activity;
import android.app.Dialog;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;


/**
 * ANT+ Demo Activity.
 * 
 * This class implements the GUI functionality and basic interaction with the AntPlusManager class.
 * For the code that does the Ant interfacing see the AntPlusManager class.
 */
public class ANTChirp extends Activity implements View.OnClickListener, AntPlusManager.Callbacks
{
   
   /** The Log Tag. */
   public static final String TAG = "ANTApp";   
   
   /**
    * The possible menu items (when pressed menu key).
    */
   private enum MyMenu
   {
      /** No menu item. */
      MENU_NONE,
      
      /** Exit menu item. */
      MENU_EXIT,      
      
      /** Pair GEO menu item. */
      MENU_PAIR_GEO,
      
      /** Sensor Configuration menu item. */
      MENU_CONFIG,
      
      /** Configure GEO menu item. */
      MENU_CONFIG_GEO,
      
      /** Configure Proximity menu item. */
      MENU_CONFIG_PROXIMITY,
      
      /** Configure Buffer Threshold menu item. */
      MENU_CONFIG_BUFFER_THRESHOLD,
      
      /** Send a request to claim the ANT Interface. */
      MENU_REQUEST_CLAIM_INTERFACE,
   }
   
   /** Displays ANT state. */
   private TextView mAntStateText;
   
   /** Button for enabling/disabling ANT */
   private ImageButton mAntPlusButton;

   /** Formatter used during printing of data */
   private DecimalFormat mOutputFormatter;
   
   /** Pair to any device. */
   static final short WILDCARD = 0;
   
   /** The default proximity search bin. */
   private static final byte DEFAULT_BIN = 7;
   
   /** The default event buffering buffer threshold. */
   private static final short DEFAULT_BUFFER_THRESHOLD = 0;
   
   /** Device ID valid value range. */
   private static final String mDeviceIdHint = AntDefine.MIN_DEVICE_ID +" - "+ (AntDefine.MAX_DEVICE_ID & 0xFFFF);
   
   /** Proximity Bin valid value range. */
   private static final String mBinHint = AntDefine.MIN_BIN +" - "+ AntDefine.MAX_BIN;
   
   /** Buffer Threshold valid value range. */
   private static final String mBufferThresholdHint = AntDefine.MIN_BUFFER_THRESHOLD +" - "+ AntDefine.MAX_BUFFER_THRESHOLD;

   /** Shared preferences data filename. */
   public static final String PREFS_NAME = "ANTDemoPrefs";
   
   /** Class to manage all the ANT messaging and setup */
   private AntPlusManager mAntManager;
   
   private boolean mBound;
   
   private PairingDialog mShownDialog = null;
   
   private final ServiceConnection mConnection = new ServiceConnection()
   {
        @Override
        public void onServiceDisconnected(ComponentName name)
        {
            //This is very unlikely to happen with a local service (ie. one in the same process)
            mAntManager.setCallbacks(null);
            mAntManager = null;
        }
        
        @Override
        public void onServiceConnected(ComponentName name, IBinder service)
        {
            mAntManager = ((ANTPlusService.LocalBinder)service).getManager();
            mAntManager.setCallbacks(ANTChirp.this);
            loadConfiguration();
            notifyAntStateChanged();
            
            if(mShownDialog != null)
            {
                mShownDialog.setEnabled(true);
                switch(mShownDialog.getId())
                {
                    case PairingDialog.GEO_ID:
                        mShownDialog.setDeviceNumber(mAntManager.getDeviceNumberGEO());
                        break;
                    case PairingDialog.BUFF_ID:
                        mShownDialog.setDeviceNumber(mAntManager.getBufferThreshold());
                        break;
                    case PairingDialog.PROX_ID:
                        mShownDialog.setProximityThreshold(mAntManager.getProximityThreshold());
                        break;
                    default:
                        //Other ID's don't matter
                        break;
                }
            }
        }
   };

   
   @Override
   public void onCreate(Bundle savedInstanceState) 
   {
       super.onCreate(savedInstanceState);
       Log.d(TAG, "onCreate enter");
       
       setContentView(R.layout.main);  
       initControls();
       
       mOutputFormatter = new DecimalFormat(getString(R.string.DataFormat));

       Log.d(TAG, "onCreate exit");
   }


    @Override
    protected void onStart()
    {
        mBound = bindService(new Intent(this, ANTPlusService.class), mConnection, BIND_AUTO_CREATE);
        super.onStart();
    }
    
    @Override
    protected void onStop()
    {
        if(mAntManager != null)
        {
            saveState();
            mAntManager.setCallbacks(null);
        }
        if(mBound)
        {
            unbindService(mConnection);
        }
        super.onStop();
    }


    @Override
   public boolean onPrepareOptionsMenu(Menu menu) 
   {      
       boolean result = super.onPrepareOptionsMenu(menu);
       
       if(mAntManager != null && mAntManager.isServiceConnected())
       {
           if(menu.findItem(MyMenu.MENU_PAIR_GEO.ordinal()) == null)
               menu.add(Menu.NONE, MyMenu.MENU_PAIR_GEO.ordinal(), 0, this.getResources().getString(R.string.Menu_Wildcard_GEO));

           if(menu.findItem(MyMenu.MENU_CONFIG.ordinal()) == null)
           {
               SubMenu configMenu = menu.addSubMenu(Menu.NONE, MyMenu.MENU_CONFIG.ordinal(), 3, this.getResources().getString(R.string.Menu_Sensor_Config));
               configMenu.add(Menu.NONE,MyMenu.MENU_CONFIG_GEO.ordinal(), 0, this.getResources().getString(R.string.Menu_GEO));
               configMenu.add(Menu.NONE,MyMenu.MENU_CONFIG_PROXIMITY.ordinal(), 3, this.getResources().getString(R.string.Menu_Proximity));
               configMenu.add(Menu.NONE,MyMenu.MENU_CONFIG_BUFFER_THRESHOLD.ordinal(), 4, this.getResources().getString(R.string.Menu_Buffer_Threshold));
           }
           
           if(menu.findItem(MyMenu.MENU_REQUEST_CLAIM_INTERFACE.ordinal()) == null)
               menu.add(Menu.NONE, MyMenu.MENU_REQUEST_CLAIM_INTERFACE.ordinal(), 4, this.getResources().getString(R.string.Menu_Claim_Interface));
       }
       
       else
       {
           if(menu.findItem(MyMenu.MENU_PAIR_GEO.ordinal()) != null)
               menu.removeItem(MyMenu.MENU_PAIR_GEO.ordinal());

           if(menu.findItem(MyMenu.MENU_CONFIG.ordinal()) != null)
           {
               SubMenu configMenu = (SubMenu) menu.getItem(MyMenu.MENU_CONFIG_GEO.ordinal());
               configMenu.removeItem(MyMenu.MENU_CONFIG_GEO.ordinal());
               configMenu.removeItem(MyMenu.MENU_CONFIG_PROXIMITY.ordinal());
               configMenu.removeItem(MyMenu.MENU_CONFIG_BUFFER_THRESHOLD.ordinal());
               menu.removeItem(MyMenu.MENU_CONFIG_GEO.ordinal());
           }
           
           if(menu.findItem(MyMenu.MENU_REQUEST_CLAIM_INTERFACE.ordinal()) != null)
               menu.removeItem(MyMenu.MENU_REQUEST_CLAIM_INTERFACE.ordinal());
       }
      
       if(menu.findItem(MyMenu.MENU_EXIT.ordinal()) == null)
           menu.add(Menu.NONE, MyMenu.MENU_EXIT.ordinal(), 99, this.getResources().getString(R.string.Menu_Exit));
       
       return result;
   }
   
   
   @Override
   public boolean onOptionsItemSelected(MenuItem item) 
   {
      MyMenu selectedItem = MyMenu.values()[item.getItemId()];
      switch (selectedItem) 
      {         
         case MENU_EXIT:            
            exitApplication();
            break;
         case MENU_PAIR_GEO:
            mAntManager.setDeviceNumberGEO(WILDCARD);
            break;
         case MENU_CONFIG_GEO:
            showDialog(PairingDialog.GEO_ID);
            break;
         case MENU_CONFIG_PROXIMITY:
            showDialog(PairingDialog.PROX_ID);
            break;
         case MENU_CONFIG_BUFFER_THRESHOLD:
            showDialog(PairingDialog.BUFF_ID);
            break;
         case MENU_REQUEST_CLAIM_INTERFACE:
             mAntManager.tryClaimAnt();
             break;
         case MENU_CONFIG:
             //fall through to do nothing, as this represents a submenu, not a menu option
         case MENU_NONE:
             //Do nothing for these, as they shouldn't even be registered as menu options
             break;
      }
      return super.onOptionsItemSelected(item);
   }

   
   @Override
   protected PairingDialog onCreateDialog(int id)
   {
      PairingDialog theDialog = null;
      
      if(id == PairingDialog.GEO_ID)
         theDialog = new PairingDialog(this, id, (short) 0, mDeviceIdHint, new OnPairingListener());
      else if(id == PairingDialog.PROX_ID)
         theDialog = new PairingDialog(this, id, (short) 0, mBinHint, new OnPairingListener());
      else if(id == PairingDialog.BUFF_ID)
          theDialog = new PairingDialog(this, id, (short) 0, mBufferThresholdHint, new OnPairingListener());
      return theDialog;
   }
   
   /**
    * Listener for updates to device number settings from the pairing dialog.
    */
   private class OnPairingListener implements PairingDialog.PairingListener 
   {
      
      /* (non-Javadoc)
       * @see com.dsi.ant.antplusdemo.PairingDialog.PairingListener#updateID(int, short)
       */
      public void updateID(int id, short deviceNumber)
      {
         if(id == PairingDialog.GEO_ID)
            mAntManager.setDeviceNumberGEO(deviceNumber);
         else if(id == PairingDialog.BUFF_ID)
         {
             mAntManager.setBufferThreshold(deviceNumber);
             
             mAntManager.setAntConfiguration();
         }
      }
      
      /* (non-Javadoc)
       * @see com.dsi.ant.antplusdemo.PairingDialog.PairingListener#updateThreshold(int, byte)
       */
      public void updateThreshold(int id, byte proxThreshold)
      {
         if(id == PairingDialog.PROX_ID)
            mAntManager.setProximityThreshold(proxThreshold);
      }
      
      @Override
      public void started(PairingDialog dialog)
      {
          mShownDialog = dialog;
      }

      @Override
      public void stopped()
      {
          mShownDialog = null;
      }
   }
  

   @Override
   protected void onPrepareDialog(int id, Dialog theDialog)
   {
      super.onPrepareDialog(id, theDialog);
      PairingDialog dialog = (PairingDialog) theDialog;
      dialog.setId(id);
      if(mAntManager == null)
      {
          dialog.setEnabled(false);
          return;
      }
      else
      {
          dialog.setEnabled(true);
      }
      
      if(id == PairingDialog.GEO_ID)
      {
         dialog.setDeviceNumber(mAntManager.getDeviceNumberGEO());
      }
      else if(id == PairingDialog.PROX_ID)
      {
         dialog.setProximityThreshold(mAntManager.getProximityThreshold());
      }
      else if(id == PairingDialog.BUFF_ID)
      {
         dialog.setDeviceNumber(mAntManager.getBufferThreshold());
      }
   }
   
   /**
    * Store application persistent data.
    */
   private void saveState()
   {
      // Save current Channel Id in preferences
      // We need an Editor object to make changes
      SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
      SharedPreferences.Editor editor = settings.edit();
      editor.putInt("DeviceNumberGEO", mAntManager.getDeviceNumberGEO());
      editor.putInt("ProximityThreshold", mAntManager.getProximityThreshold());
      editor.putInt("BufferThreshold", mAntManager.getBufferThreshold());
      editor.commit();
   }
   
   /**
    * Retrieve application persistent data.
    */
   private void loadConfiguration()
   {
      // Restore preferences
      SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
      mAntManager.setDeviceNumberGEO((short) settings.getInt("DeviceNumberGEO", WILDCARD));
      mAntManager.setProximityThreshold((byte) settings.getInt("ProximityThreshold", DEFAULT_BIN));
      mAntManager.setBufferThreshold((short) settings.getInt("BufferThreshold", DEFAULT_BUFFER_THRESHOLD));
   }
   
   /**
    * Initialize GUI elements.
    */
   private void initControls()
   {
      mAntStateText = (TextView)findViewById(R.id.text_status);
      mAntPlusButton = (ImageButton)findViewById(R.id.button_antplus);

      
      // Set up button listeners and scaling 
      ((ImageButton)findViewById(R.id.button_geo)).setOnClickListener(this);
      ((ImageButton)findViewById(R.id.button_geo)).setScaleType(ImageView.ScaleType.CENTER_INSIDE);
      ((ImageButton)findViewById(R.id.button_geo)).setBackgroundColor(Color.TRANSPARENT);
      mAntPlusButton.setOnClickListener(this);
      mAntPlusButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
      mAntPlusButton.setBackgroundColor(Color.TRANSPARENT);
   }
   
   /**
    * Shows/hides the channels based on the state of the ant service
    */
   private void drawWindow()
   {
       boolean showChannels = mAntManager.checkAntState();
       setDisplay(showChannels);
       if(showChannels)
       {
           drawChannel(AntPlusManager.GEO_CHANNEL);
       }
       else
       {
           mAntStateText.setText(mAntManager.getAntStateText());
       }
       
       mAntPlusButton.setImageResource(mAntManager.isEnabled() ?
               R.drawable.antplus : R.drawable.antplus_gray); //Button reflects enabled state.
   }
   
   /**
    * Sets the channel button image and status strings according to the specified channel's state
    * @param channel
    */
   private void drawChannel(byte channel)
   {
       switch(channel)
       {
           case AntPlusManager.GEO_CHANNEL:
               switch (mAntManager.getGeoState()) {
                   case CLOSED:
                       ((ImageButton)findViewById(R.id.button_geo)).setImageResource(R.drawable.ant_geo_gray);
                       ((TextView)findViewById(R.id.text_status_geo)).setText(getString(R.string.Closed));
                       break;
                   case OFFLINE:
                       ((ImageButton)findViewById(R.id.button_geo)).setImageResource(R.drawable.ant_geo_gray);
                       ((TextView)findViewById(R.id.text_status_geo)).setText(getString(R.string.NoSensor_txt));
                       break;
                   case SEARCHING:
                       ((ImageButton)findViewById(R.id.button_geo)).setImageResource(R.drawable.ant_geo);
                       ((TextView)findViewById(R.id.text_status_geo)).setText(getString(R.string.Search));
                       break;
                   case PENDING_OPEN:
                       ((ImageButton)findViewById(R.id.button_geo)).setImageResource(R.drawable.ant_geo_gray);
                       ((TextView)findViewById(R.id.text_status_geo)).setText(getString(R.string.Opening));
                       break;
                   case TRACKING_STATUS:
                       //This state should not show up for this channel, but in the case it does
                       //We can consider it equivalent to showing the data.
                   case TRACKING_DATA:
                       ((ImageButton)findViewById(R.id.button_geo)).setImageResource(R.drawable.ant_geo);
                       ((TextView)findViewById(R.id.text_status_geo)).setText(getString(R.string.Connected));
                       break;
               }
               break;
       }
       drawChannelData(channel);
   }
   
   /**
    * Fills in the data fields for the specified ant channel's data display 
    * @param channel
    */
   private void drawChannelData(byte channel)
   {
       switch(channel)
       {
           case AntPlusManager.GEO_CHANNEL:
               switch (mAntManager.getGeoState()) {
                   case CLOSED:
                   case OFFLINE:
                   case SEARCHING:
                   case PENDING_OPEN:
                       //For all these cases we don't have any incoming data, so they all show '--'
                       ((TextView)findViewById(R.id.text_geo_id)).setText(getString(R.string.noData));
                       ((TextView)findViewById(R.id.text_geo_pin)).setText(getString(R.string.noData));
                       ((TextView)findViewById(R.id.text_geo_latitude)).setText(getString(R.string.noData));
                       ((TextView)findViewById(R.id.text_geo_longitude)).setText(getString(R.string.noData));
                       ((TextView)findViewById(R.id.text_geo_hint)).setText(getString(R.string.noData));
                       ((TextView)findViewById(R.id.text_geo_logged_visits)).setText(getString(R.string.noData));
                       break;
                   case TRACKING_STATUS:
                       //There is no Status state for the Geo channel, so we will attempt to show latest data instead
                   case TRACKING_DATA:
                       ((TextView)findViewById(R.id.text_geo_id)).setText(mAntManager.getGeoID());
                       ((TextView)findViewById(R.id.text_geo_pin)).setText(""+mAntManager.getGeoPIN());
                       ((TextView)findViewById(R.id.text_geo_latitude)).setText(""+mAntManager.getGeoLatitude());
                       ((TextView)findViewById(R.id.text_geo_longitude)).setText(""+mAntManager.getGeoLongitude());
                       ((TextView)findViewById(R.id.text_geo_hint)).setText(mAntManager.getGeoHint());
                       ((TextView)findViewById(R.id.text_geo_logged_visits)).setText(""+mAntManager.getGeoLoggedVisits());
                       break;
               }
               break;
       }
   }
   
   /**
    * Set whether buttons etc are visible.
    *
    * @param pVisible buttons visible, status text shown when they are not.
    */
   private void setDisplay(boolean pVisible)
   {
       Log.d(TAG, "setDisplay: visible = "+ pVisible);
       
       int visibility = (pVisible ? View.VISIBLE : View.INVISIBLE);

       View v = findViewById(R.id.button_geo);
       if(v != null) v.setVisibility(visibility);
       v = findViewById(R.id.geo_layout);
       if(v != null) v.setVisibility(visibility);
       
       if(!pVisible)
       {
           mAntManager.clearChannelStates();
       }

       mAntStateText.setVisibility(pVisible ? TextView.INVISIBLE : TextView.VISIBLE); // Visible when buttons aren't
   }
   
   // OnClickListener implementation.
   public void onClick(View v)
   {
       if(mAntManager == null)
           return;
       if(v.getId() == R.id.button_antplus)
       {
           if(mAntManager.isEnabled())
               mAntManager.doDisable();
           else
               mAntManager.doEnable();
           return;
       }
        // If no channels are open, reset ANT
        if (!mAntManager.isChannelOpen(AntPlusManager.GEO_CHANNEL))
        {
            Log.d(TAG, "onClick: No channels open, reseting ANT");
            // Defer opening the channel until an ANT_RESET has been
            // received
            switch (v.getId())
            {
                case R.id.button_geo:
                    mAntManager.openChannel(AntPlusManager.GEO_CHANNEL, true);
                    break;
            }
            mAntManager.requestReset();
        }
        else {
            switch (v.getId()) {
                case R.id.button_geo:
                    if (!mAntManager.isChannelOpen(AntPlusManager.GEO_CHANNEL))
                    {
                        // Configure and open channel
                        Log.d(TAG, "onClick (GEO): Open channel");
                        mAntManager.openChannel(AntPlusManager.GEO_CHANNEL, false);
                    } else
                    {
                        // Close channel
                        Log.d(TAG, "onClick (GEO): Close channel");
                        mAntManager.closeChannel(AntPlusManager.GEO_CHANNEL);
                    }
                    break;
            }
        }
   }
   
   //Implementations of the AntPlusManager call backs

   @Override
   public void errorCallback()
   {
       setDisplay(false);
   }

   @Override
   public void notifyAntStateChanged()
   {
       drawWindow();
   }
   
   @Override
   public void notifyChannelStateChanged(byte channel)
   {
       drawChannel(channel);
   }
   
   @Override
   public void notifyChannelDataChanged(byte channel)
   {
       drawChannelData(channel);
   }
   

   /**
    * Exit application.
    */
   private void exitApplication()
   {
      Log.d(TAG, "exitApplication enter");
      
      AlertDialog.Builder builder = new AlertDialog.Builder(this);
      
      builder.setMessage(this.getResources().getString(R.string.Dialog_Exit_Check));
      builder.setCancelable(false);

      builder.setPositiveButton(this.getResources().getString(R.string.Dialog_Confirm), new DialogInterface.OnClickListener() {
                 public void onClick(DialogInterface dialog, int id) {
                     Log.i(TAG, "exitApplication: Exit");
                     finish();
                 }
             });

      builder.setNegativeButton(this.getResources().getString(R.string.Dialog_Cancel), new DialogInterface.OnClickListener() {
                 public void onClick(DialogInterface dialog, int id) {
                     Log.i(TAG, "exitApplication: Cancelled");
                     dialog.cancel();
                 }
             });

      AlertDialog exitDialog = builder.create();
      exitDialog.show();
   }
}
