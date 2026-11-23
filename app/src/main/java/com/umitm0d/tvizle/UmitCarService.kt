package com.umitm0d.tvizle

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/** Android Auto'nun "Navigasyon" kategorisinde ÜmitTV'yi gösteren servis (Fermata'nın FS modu gibi). */
class UmitCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = UmitCarSession()
    override fun onCreateSession(sessionInfo: SessionInfo): Session = UmitCarSession()
}

class UmitCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        routeVoice(intent)
        return UmitCarScreen(carContext)
    }

    /** Asistan "… git/navigasyon" olarak yönlendirirse (geo:0,0?q=Show TV) kanal adı araç ekranındaki TV İzle'ye iletilir. */
    override fun onNewIntent(intent: Intent) {
        routeVoice(intent)
    }

    private fun routeVoice(intent: Intent) {
        val u = intent.data?.takeIf { it.scheme == "geo" } ?: return
        // geo: opak bir URI'dir (getQueryParameter çalışmaz) → şema-özel kısım elle ayrıştırılır
        val ssp = u.schemeSpecificPart ?: return
        var q = Regex("[?&]q=([^&]*)").find(ssp)?.groupValues?.get(1)?.replace('+', ' ')?.trim() ?: return
        Regex("\\(([^)]*)\\)").find(q)?.let { q = it.groupValues[1].trim() }   // "q=lat,lng(Etiket)" biçimi
        if (q.isEmpty()) return
        CarMainActivity.current?.deliverExternalVoice(q)
    }
}
