package com.umitm0d.tvizle

import android.graphics.Color
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class UmitCarScreen(ctx: CarContext) : Screen(ctx) {
    private val mirror = CarMirror(ctx)

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(mirror)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                mirror.release()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val exit = Action.Builder()
            .setTitle("Çıkış")
            .setOnClickListener { carContext.finishCarApp() }
            .build()
        return NavigationTemplate.Builder()
            .setBackgroundColor(CarColor.createCustom(Color.BLACK, Color.BLACK))
            .setActionStrip(ActionStrip.Builder().addAction(exit).build())
            // Kaydırma hareketi için "pan" modu düğmesi (liste kaydırmak için önce buna dokun)
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .build()
    }
}
