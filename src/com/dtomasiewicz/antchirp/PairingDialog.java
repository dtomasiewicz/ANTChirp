package com.dtomasiewicz.antchirp;

import com.dsi.ant.AntDefine;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class PairingDialog extends Dialog
{
   public static final int MIN_ID = 0;
   public static final int GEO_ID = 0;
   public static final int PROX_ID = 1;
   public static final int BUFF_ID = 2;
   public static final int MAX_ID = 2;
   
   public interface PairingListener
   {
      public void updateID(int id, short deviceNumber);
      public void updateThreshold(int id, byte proxThreshold);
      public void started(PairingDialog dialog);
      public void stopped();
   }

   
   public int getId()
   {
      return mId;
   }

   public void setId(int id)
   {
      this.mId = id;
   }

   public short getDeviceNumber()
   {
      return mDeviceNumber;
   }

   public void setDeviceNumber(short deviceNumber)
   {
      this.mDeviceNumber = deviceNumber;
      if(mInput != null)
      {
          mInput.setText(String.valueOf(mDeviceNumber & 0xFFFF));
          mInput.selectAll();
      }
   }
   
   public byte getProximityThreshold()
   {
      return mProximityThreshold;
   }
   
   public void setProximityThreshold(byte proximityThreshold)
   {
      this.mProximityThreshold = proximityThreshold;
      if(mInput != null)
      {
          mInput.setText(String.valueOf(mProximityThreshold & 0xFF));
          mInput.selectAll();
      }
   }

   public String getHint()
   {
      return mHint;
   }
   
   public void setHint(String hint)
   {
      if(null == hint)
      {
         this.mHint = "";
      }
      else
      {   
         this.mHint = hint;
      }
   }
   
   public void setEnabled(boolean enabled)
   {
       ((Button) findViewById(R.id.dialog_button)).setEnabled(enabled);
       mInput.setEnabled(enabled);
   }

   private int mId;
   private short mDeviceNumber;
   private byte mProximityThreshold;
   private String mHint;
   private PairingListener mPairingListener;
   private EditText mInput;
     
   
   public PairingDialog(Context context, int id, short deviceNumber, String hint, PairingListener pairingListener)
   {
      super(context);
      mId = id;
      mDeviceNumber = deviceNumber;
      setHint(hint);
      mPairingListener = pairingListener;
   }
   
   public PairingDialog(Context context, int id, byte proximityThreshold, String hint, PairingListener pairingListener)
   {
      super(context);
      mId = id;
      mProximityThreshold = proximityThreshold;
      setHint(hint);
      mPairingListener = pairingListener;
   }
   
   @Override
   public void onCreate(Bundle savedInstanceState)
   {
      super.onCreate(savedInstanceState);
      
      if(mId >= MIN_ID && mId <= MAX_ID)
      {
         setContentView(R.layout.pairing_dialog);
         
         if(mId == GEO_ID)
            setTitle(getContext().getResources().getString(R.string.Dialog_Pair_GEO));
         else if(mId == PROX_ID)
            setTitle(getContext().getResources().getString(R.string.Dialog_Proximity));
         else if(mId == BUFF_ID)
             setTitle(getContext().getResources().getString(R.string.Dialog_Buffer_Threshold));
         
         TextView descr = (TextView) findViewById(R.id.dialog_text);
         mInput = (EditText) findViewById(R.id.dialog_input);
         if(mId == PROX_ID)
         {
            descr.setText(getContext().getResources().getString(R.string.Dialog_Prox_Text));
            mInput.setText("" + (mProximityThreshold & 0xFF));
         }
         else if(mId == BUFF_ID)
         {
            descr.setText(getContext().getResources().getString(R.string.Dialog_Buffer_Threshold_Text));
            mInput.setText("" + (int) mDeviceNumber);
         }
         else
         {
            descr.setText(getContext().getResources().getString(R.string.Dialog_Pair));
            mInput.setText("" + (int) mDeviceNumber);
         }
         mInput.setHint(mHint);
         
         Button buttonOK = (Button) findViewById(R.id.dialog_button);
         buttonOK.setOnClickListener(new OKListener());
      }      
   }
    
   @Override 
   protected void onStart()
   {
      super.onStart();
      if(mId == PROX_ID)
      {
          mInput.setText(String.valueOf(mProximityThreshold & 0xFF));
      }
      else
      {
          mInput.setText(String.valueOf(mDeviceNumber & 0xFFFF));
      }
      mInput.selectAll();
      mPairingListener.started(this);
   }
   
   @Override
   protected void onStop()
   {
      mPairingListener.stopped();
   }

   private class OKListener implements android.view.View.OnClickListener
   {  
      private void resetInput()
      {
          if(PROX_ID == mId)
          {
              mInput.setText(String.valueOf(mProximityThreshold & 0xFF));
          }
          else
          {
              mInput.setText(String.valueOf(mDeviceNumber & 0xFFFF));
          }
      }
       
      public void onClick(View v)
      {
         try
         {
             
             Integer tempInt = Integer.parseInt(mInput.getText().toString());         
             if(tempInt != null && (mId == GEO_ID) && tempInt >= AntDefine.MIN_DEVICE_ID && tempInt <= (AntDefine.MAX_DEVICE_ID & 0xFFFF))
             {
                 int temp = tempInt.intValue();
                 mDeviceNumber = (short) (temp & 0xFFFF);
                 mPairingListener.updateID(mId, mDeviceNumber);  // Let main activity know about update
                 PairingDialog.this.dismiss();
             }
             else if(tempInt != null && mId == PROX_ID && tempInt >= AntDefine.MIN_BIN && tempInt <= AntDefine.MAX_BIN)
             {
                 int temp = tempInt.intValue();
                 mProximityThreshold = (byte) (temp & 0xFF);
                 mPairingListener.updateThreshold(mId, mProximityThreshold);
                 PairingDialog.this.dismiss();
             }
             else if(tempInt != null && mId == BUFF_ID && tempInt >= AntDefine.MIN_BUFFER_THRESHOLD && tempInt <= AntDefine.MAX_BUFFER_THRESHOLD)
             {
                 int temp = tempInt.intValue();
                 mDeviceNumber = (short) (temp & 0xFFFF);
                 mPairingListener.updateID(mId, mDeviceNumber);
                 PairingDialog.this.dismiss();
             }
             else
             {
                 resetInput();           
             }
         }
         catch(NumberFormatException e)
         {
             resetInput();
         }
      }
   }
}
