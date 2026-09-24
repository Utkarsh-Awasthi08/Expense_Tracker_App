package com.myexpensetracker

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap

class SmsModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String {
        return "SmsModule"
    }

    @ReactMethod
    fun getRecentSms(limit: Int, promise: Promise) {
        try {
            val contentResolver: ContentResolver = reactApplicationContext.contentResolver
            val uri: Uri = Telephony.Sms.Inbox.CONTENT_URI
            
            // Define the columns we want to extract
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            )
            
            val sortOrder = "${Telephony.Sms.DATE} DESC LIMIT $limit"
            
            val cursor: Cursor? = contentResolver.query(uri, projection, null, null, sortOrder)
            val smsArray: WritableArray = Arguments.createArray()
            
            if (cursor != null) {
                val indexId = cursor.getColumnIndex(Telephony.Sms._ID)
                val indexAddress = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val indexBody = cursor.getColumnIndex(Telephony.Sms.BODY)
                val indexDate = cursor.getColumnIndex(Telephony.Sms.DATE)

                while (cursor.moveToNext()) {
                    val smsMap: WritableMap = Arguments.createMap()
                    smsMap.putString("id", cursor.getString(indexId))
                    smsMap.putString("address", cursor.getString(indexAddress))
                    smsMap.putString("body", cursor.getString(indexBody))
                    smsMap.putString("date", cursor.getString(indexDate))
                    smsArray.pushMap(smsMap)
                }
                cursor.close()
            }
            promise.resolve(smsArray)
        } catch (e: Exception) {
            promise.reject("SMS_ERROR", e.message, e)
        }
    }
}
