package cn.tsh520.cocjson

import android.app.Service
import android.content.Intent
import android.os.IBinder

class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
