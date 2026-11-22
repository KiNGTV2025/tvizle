package com.umitm0d.tvizle

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/** Android Auto'nun "Navigasyon" kategorisinde ÜmitTV'yi gösteren servis (Fermata'nın FS modu gibi). */
class UmitCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = UmitCarSession()
}

class UmitCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = UmitCarScreen(carContext)
}
